// 反馈接口端到端测试：本地假 SMTP + 真实 server.js（临时端口、临时日志文件）
// 注意：限流按 IP 计，且对**所有**请求（含校验失败）生效。
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const { startSmtp, decodeBody, hasHeader } = require('./test-feedback');

let pass = 0;
const fails = [];
function check(cond, name, extra) {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fails.push(name + (extra ? ' -> ' + extra : '')); console.log('  FAIL ' + name + (extra ? ' -> ' + extra : '')); }
}

function post(port, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(typeof body === 'string' ? body : JSON.stringify(body));
    const req = http.request({ host: '127.0.0.1', port, path: '/api/feedback', method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': data.length, ...headers } },
    (res) => {
      let out = '';
      res.on('data', (c) => out += c);
      res.on('end', () => {
        let json = null;
        try { json = JSON.parse(out); } catch {}
        resolve({ status: res.statusCode, json, out, headers: res.headers });
      });
    });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

async function waitHealthy(port) {
  for (let i = 0; i < 100; i++) {
    try {
      await new Promise((res, rej) => http.get({ host: '127.0.0.1', port, path: '/health' },
        (r) => { r.resume(); r.on('end', res); }).on('error', rej));
      return true;
    } catch { await new Promise(r => setTimeout(r, 100)); }
  }
  return false;
}

let nextPort = 15000 + Math.floor(Math.random() * 1000);

/** 起一个新的服务实例，返回 { post, stop } */
async function newServer(extraEnv) {
  const port = nextPort++;
  const logFile = path.join(os.tmpdir(), `feedback-${port}-${Date.now()}.log`);
  const child = spawn(process.execPath, ['server.js'], {
    cwd: __dirname,
    env: {
      ...process.env, PORT: String(port), HOST: '127.0.0.1',
      ADMIN_TOKEN: 'test-token',
      FEEDBACK_LOG: logFile,
      ...extraEnv,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let log = '';
  child.stdout.on('data', (d) => log += d);
  child.stderr.on('data', (d) => log += d);
  if (!await waitHealthy(port)) {
    child.kill();
    throw new Error('服务未启动:\n' + log);
  }
  return {
    port,
    logFile,
    log: () => log,
    post: (body, headers) => post(port, body, headers),
    stop: () => { child.kill(); try { fs.rmSync(logFile, { force: true }); } catch {} },
  };
}

const SMTP_ENV = (smtp) => ({
  SMTP_HOST: '127.0.0.1',
  SMTP_PORT: String(smtp.port),
  SMTP_SECURE: 'false',
  SMTP_USER: 'bot@example.com',
  SMTP_PASS: 'dummy-pass',
  TARGET_EMAIL: 'owner@example.com',
});
const NO_SMTP_ENV = { SMTP_HOST: '', SMTP_PORT: '', SMTP_USER: '', SMTP_PASS: '', TARGET_EMAIL: '' };

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

(async () => {
  const smtp = await startSmtp();
  const cleanups = [];

  try {
    // ---------- 正常提交 ----------
    console.log('—— 正常提交 ——');
    {
      const s = await newServer(SMTP_ENV(smtp));
      cleanups.push(s);
      const before = smtp.mails.length;
      const r = await s.post({
        type: 'bug',
        content: '启动游戏后黑屏，复现步骤：1. 选 1.20.1  2. 点开始',
        contact: 'QQ 123456',
        systemInfo: { os: 'Windows 11', osVersion: '10.0', arch: 'amd64', java: '17.0.20' },
      });
      check(r.status === 200 && r.json && r.json.success === true && r.json.data && r.json.data.id,
          '返回 200 + 反馈编号', r.out);
      await sleep(500);
      check(smtp.mails.length === before + 1, 'SMTP 收到一封邮件', 'mails=' + (smtp.mails.length - before));
      const mail = smtp.mails[smtp.mails.length - 1] || { data: '', to: [] };
      check(mail.to.includes('<owner@example.com>'), '收件人是 TARGET_EMAIL', JSON.stringify(mail.to));
      const decoded = decodeBody(mail.data);
      check(decoded.includes('Bug') && decoded.includes('黑屏'), '邮件正文包含类型与内容', decoded.slice(0, 160));
      check(decoded.includes('17.0.20'), '邮件正文包含系统环境');
      check(fs.existsSync(s.logFile) && fs.readFileSync(s.logFile, 'utf8').includes('黑屏'), '本地日志已写入');
    }

    // ---------- 校验 ----------
    console.log('—— 校验 ——');
    {
      const s = await newServer(SMTP_ENV(smtp));
      cleanups.push(s);
      check((await s.post({ type: 'bug', content: '   ' })).status === 400, '空 content 返回 400');
      check((await s.post({ type: 'hack', content: 'x' })).status === 400, '非法 type 返回 400');
      check((await s.post({ type: 'bug', content: '字'.repeat(2001) })).status === 400, '超过 2000 字返回 400');
      // 每个实例 1 分钟只能 3 条：超过的会被限流，换一个新实例继续测
      const s2 = await newServer(SMTP_ENV(smtp));
      cleanups.push(s2);
      check((await s2.post({ type: 'bug', content: '字'.repeat(2000) })).status === 200, '正好 2000 字可以提交');
      check((await s2.post('not json')).status === 400, '非 JSON 返回 400');
      check((await s2.post({ type: 'bug', content: 'x', systemInfo: 'not-object' })).status === 400,
          'systemInfo 非对象返回 400');

      const s3 = await newServer(SMTP_ENV(smtp));
      cleanups.push(s3);
      check((await s3.post({ type: 'bug', content: 'x'.repeat(40_000) })).status === 413,
          '超大请求体返回 413');
    }

    // ---------- XSS / 头注入 ----------
    console.log('—— XSS / 头注入 ——');
    {
      const s = await newServer(SMTP_ENV(smtp));
      cleanups.push(s);
      const before = smtp.mails.length;
      const r = await s.post({
        type: 'other',
        content: '<script>alert(1)</script>\r\nBcc: evil@evil.com\r\n\u0000注入',
      });
      check(r.status === 200, '含特殊字符的反馈可以提交', r.status);
      await sleep(500);
      const m = smtp.mails[smtp.mails.length - 1];
      check(smtp.mails.length === before + 1 && m, '邮件数 +1');
      const d = decodeBody(m.data);
      // 纯文本段里 <script> 是普通文字（正常）；只要 HTML 段里没有未转义的标签
      const htmlPart = d.split('<div style="font-family').pop();
      check(!/<script>/.test(htmlPart), 'HTML 段里没有未转义的 <script>');
      check(d.includes('&lt;script&gt;'), 'HTML 已转义', d.slice(0, 160));
      check(!hasHeader(m.data, 'Bcc'), '没有邮件头注入（头区无 Bcc 行）');
      check(!m.data.includes('\u0000'), '控制字符被清除');
    }

    // ---------- SMTP 未配置 ----------
    console.log('—— SMTP 未配置 ——');
    {
      const s = await newServer(NO_SMTP_ENV);
      cleanups.push(s);
      const r = await s.post({ type: 'other', content: '无 SMTP 配置时也要落日志' });
      check(r.status === 503, '未配置 SMTP 返回 503', r.status);
      check(fs.existsSync(s.logFile) && fs.readFileSync(s.logFile, 'utf8').includes('无 SMTP 配置'),
          '未配置 SMTP 时仍写日志');
    }

    // ---------- .env 读取（直接 node server.js 的场景） ----------
    console.log('—— .env 读取 ——');
    {
      // 写一个临时 .env，然后**不带**任何 SMTP_* 环境变量启动，
      // 模拟运维直接在服务器上 `node server.js`。
      const dotenv = path.join(__dirname, '.env.e2e-test');
      fs.writeFileSync(dotenv, [
        'SMTP_HOST=127.0.0.1',
        'SMTP_PORT=' + smtp.port,
        'SMTP_SECURE=false',
        'SMTP_USER=bot@example.com',
        'SMTP_PASS=dummy-pass',
        'TARGET_EMAIL=owner@example.com',
        'ADMIN_TOKEN=from-dotenv',
      ].join('\n'), 'utf8');

      // 临时把测试用的 .env 命名为 server.js 默认读取的名字
      const realEnv = path.join(__dirname, '.env');
      const hadRealEnv = fs.existsSync(realEnv);
      if (hadRealEnv) fs.renameSync(realEnv, realEnv + '.e2e-backup');
      fs.renameSync(dotenv, realEnv);

      const port = nextPort++;
      const child = spawn(process.execPath, ['server.js'], {
        cwd: __dirname,
        env: (() => {
          const e = { ...process.env, PORT: String(port), HOST: '127.0.0.1' };
          for (const k of Object.keys(e)) {
            if (k.startsWith('SMTP_') || k === 'TARGET_EMAIL' || k === 'ADMIN_TOKEN'
                || k === 'FEEDBACK_LOG') delete e[k];
          }
          e.FEEDBACK_LOG = path.join(os.tmpdir(), `feedback-env-${port}.log`);
          return e;
        })(),
        stdio: ['ignore', 'pipe', 'pipe'],
      });
      let log = '';
      child.stdout.on('data', (d) => log += d);
      child.stderr.on('data', (d) => log += d);
      try {
        if (!await waitHealthy(port)) throw new Error('服务未启动:\n' + log);
        check(!log.includes('SMTP 配置不完整'), '直接 node server.js 时读到了 .env（无告警）', log.slice(0, 300));
        const before = smtp.mails.length;
        const r = await post(port, { type: 'other', content: '.env 读取测试' });
        check(r.status === 200, '.env 中的 SMTP 真正生效（能发信）', r.out);
        await sleep(400);
        check(smtp.mails.length === before + 1, 'SMTP 收到了这封信');
      } finally {
        child.kill();
        try { fs.rmSync(path.join(os.tmpdir(), `feedback-env-${port}.log`), { force: true }); } catch {}
        fs.rmSync(realEnv, { force: true });
        if (hadRealEnv) fs.renameSync(realEnv + '.e2e-backup', realEnv);
      }
    }

    // ---------- 限流（放最后：会耗尽本实例的额度） ----------
    console.log('—— 限流 ——');
    {
      const s = await newServer(SMTP_ENV(smtp));
      cleanups.push(s);
      const codes = [];
      for (let i = 0; i < 5; i++) {
        codes.push((await s.post({ type: 'other', content: 'flood ' + i })).status);
      }
      check(codes.slice(0, 3).every(c => c === 200), '前 3 条正常（' + codes + '）');
      check(codes[3] === 429 && codes[4] === 429, '第 4 条起返回 429（' + codes + '）');
      const limited = await s.post({ type: 'other', content: 'x' });
      check(String(limited.headers['retry-after'] || '').length > 0, '429 带 Retry-After');
    }
  } catch (e) {
    fails.push('异常: ' + e.message);
    console.log(e.message);
  } finally {
    cleanups.forEach(s => s.stop());
    smtp.server.close();
  }

  console.log('\n通过 ' + pass + '，失败 ' + fails.length);
  if (fails.length) { fails.forEach(f => console.log('  - ' + f)); process.exit(1); }
  process.exit(0);
})();