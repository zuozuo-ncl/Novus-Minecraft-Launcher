/**
 * feedback.js —— 意见反馈：校验、限流、邮件转发、本地日志
 * 为什么放在服务端
 */
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

// ---------- 配置 ----------

const CFG = {
  host: process.env.SMTP_HOST || '',
  port: Number(process.env.SMTP_PORT) || 465,
  secure: (process.env.SMTP_SECURE || 'true').toLowerCase() !== 'false',
  user: process.env.SMTP_USER || '',
  pass: process.env.SMTP_PASS || '',
  target: (process.env.TARGET_EMAIL || '').split(',').map(s => s.trim()).filter(Boolean),
  logFile: process.env.FEEDBACK_LOG
      || path.join(__dirname, 'data', 'feedback.log'),
};

const MAX_CONTENT = 2000;
const MAX_CONTACT = 100;
const MAX_SYSTEM_INFO_BYTES = 2048;

const TYPES = {
  bug: 'Bug 反馈',
  suggestion: '功能建议',
  other: '其他',
};

// ---------- 文本清洗 ----------

/** 去掉控制字符（含 \r），并压缩多余空行。 */
function sanitizeText(s) {
  if (typeof s !== 'string') return '';
  return s
    .replace(/\r\n?/g, '\n')
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/g, '')
    .replace(/\n{4,}/g, '\n\n\n')
    .trim();
}

/** 邮件主题里的单行值：不允许换行（防 SMTP 头注入） */
function oneLine(s) {
  return String(s || '').replace(/[\r\n\u2028\u2029]+/g, ' ').trim();

}

/** HTML 转义 —— 反馈内容会拼进 HTML 邮件 */
function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** 按码点计长度（与客户端 String.length 可能差 1 个 UTF-16 代理对，这里更严格） */
function codePoints(s) {
  return Array.from(s).length;
}

// ---------- 速率限制 ----------

/** 固定窗口限流（按 IP）。 */
function createRateLimiter(rules) {
  const buckets = new Map(); // ip -> { hits: [{ rule, at }] }

  /** 清理过期记录（同时把空桶删掉） */
  function prune(now) {
    const maxWindow = Math.max(...rules.map(r => r.windowMs));
    for (const [ip, b] of buckets) {
      b.hits = b.hits.filter(h => now - h.at < maxWindow);
      if (b.hits.length === 0) buckets.delete(ip);
    }
  }
  const timer = setInterval(() => prune(Date.now()), 60_000);
  timer.unref();

  /**
   * @returns {{ ok: true } | { ok: false, retryAfterSec: number }}
   */
  function check(ip) {
    const now = Date.now();
    // 桶太多时（被扫 IP）先清一遍，避免无限增长
    if (buckets.size > 10_000) prune(now);

    const b = buckets.get(ip) || { hits: [] };
    buckets.set(ip, b);

    for (const rule of rules) {
      const recent = b.hits.filter(h => h.rule === rule.name && now - h.at < rule.windowMs);
      if (recent.length >= rule.max) {
        const oldest = Math.min(...recent.map(h => h.at));
        const retryAfterSec = Math.max(1, Math.ceil((rule.windowMs - (now - oldest)) / 1000));
        return { ok: false, retryAfterSec };
      }
    }
    for (const rule of rules) {
      b.hits.push({ rule: rule.name, at: now });
    }
    return { ok: true };
  }

  return { check, buckets };
}

// 同一 IP：1 分钟最多 3 条，1 小时最多 10 条，1 天最多 30 条
const limiter = createRateLimiter([
  { name: 'min', windowMs: 60_000, max: 3 },
  { name: 'hour', windowMs: 60 * 60_000, max: 10 },
  { name: 'day', windowMs: 24 * 60 * 60_000, max: 30 },
]);

/**
 * 取客户端真实 IP（用于限流与邮件里的“来源 IP”）。
 * 安全前提：**只有当服务本身不对公网直接开放**时才读这些头，否则任何人都能
 */
function clientIp(req) {
  const direct = req.ip || req.socket?.remoteAddress || 'unknown';
  if (process.env.TRUST_PROXY !== '1') {
    return direct;
  }

  const cfIp = req.headers['cf-connecting-ip'];
  if (cfIp && isTrustedOrigin(direct)) {
    return String(cfIp).trim();
  }

  const xff = req.headers['x-forwarded-for'];
  if (xff) {
    // 取最左（客户端原始地址）；中间若有多级代理，第一项就是最初发起方
    const first = String(xff).split(',')[0].trim();
    if (first) return first;
  }

  return direct;
}

/** 直连来源是否属于可信代理（未配置 CF_TRUSTED 时全部信任，保持旧行为） */
function isTrustedOrigin(direct) {
  const list = (process.env.CF_TRUSTED || '').split(',').map(s => s.trim()).filter(Boolean);
  if (list.length === 0) {
    return true;
  }
  const ip = String(direct).replace(/^::ffff:/, '');
  return list.some(entry => ip === entry);
}

// ---------- 本地日志 ----------

/** 追加写日志（mkdir -p + appendFile）；失败只记 stderr，不影响接口返回 */
function appendLog(entry) {
  try {
    fs.mkdirSync(path.dirname(CFG.logFile), { recursive: true });
    fs.appendFileSync(CFG.logFile, JSON.stringify(entry) + '\n', 'utf8');
    return true;
  } catch (err) {
    console.error('[feedback] 写本地日志失败:', err.message);
    return false;
  }
}

// ---------- 邮件 ----------

let transporter = null;
let nodemailerMissing = false;

/** 懒加载 nodemailer：没装/没配置时接口返回 503，而不是让整个服务起不来 */
function getTransporter() {
  if (transporter) return transporter;
  try {
    // eslint-disable-next-line global-require
    const nodemailer = require('nodemailer');
    transporter = nodemailer.createTransport({
      host: CFG.host,
      port: CFG.port,
      secure: CFG.secure,
      auth: { user: CFG.user, pass: CFG.pass },
      // 国内 SMTP 服务器常见自签/企业证书问题，这里不做跳过（保持默认严格校验）
      connectionTimeout: 10_000,
      greetingTimeout: 10_000,
      socketTimeout: 20_000,
    });
    return transporter;
  } catch (err) {
    nodemailerMissing = true;
    console.error('[feedback] nodemailer 未安装（npm i nodemailer）:', err.message);
    return null;
  }
}

function smtpConfigured() {
  return Boolean(CFG.host && CFG.user && CFG.pass && CFG.target.length > 0);
}

function buildMail({ id, typeLabel, content, contact, systemInfo, ip }) {
  const subject = `[NCL 反馈] ${typeLabel} · ${oneLine(content).slice(0, 40)}`;

  const rows = [
    ['反馈编号', id],
    ['类型', typeLabel],
    ['联系方式', contact || '（未填写）'],
    ['来源 IP', ip],
    ['时间', new Date().toISOString()],
  ];

  const sysRows = Object.entries(systemInfo || {})
      .map(([k, v]) => `<tr><td style="padding:4px 12px;color:#666">${escapeHtml(k)}</td>`
          + `<td style="padding:4px 12px">${escapeHtml(v)}</td></tr>`)
      .join('');

  const html = `
<div style="font-family:system-ui,'Segoe UI',sans-serif;font-size:14px;color:#222">
  <h3 style="margin:0 0 12px">NCL 启动器 · ${escapeHtml(typeLabel)}</h3>
  <table style="border-collapse:collapse;margin-bottom:16px">
    ${rows.map(([k, v]) => `<tr><td style="padding:4px 12px;color:#666">${escapeHtml(k)}</td>`
        + `<td style="padding:4px 12px">${escapeHtml(v)}</td></tr>`).join('')}
  </table>
  <div style="white-space:pre-wrap;border-left:3px solid #4a90d9;padding:8px 14px;background:#f7f9fb">
${escapeHtml(content)}</div>
  <h4 style="margin:20px 0 8px">系统环境</h4>
  <table style="border-collapse:collapse">${sysRows || '<tr><td>（无）</td></tr>'}</table>
</div>`.trim();

  const text = [
    `反馈编号：${id}`,
    `类型：${typeLabel}`,
    `联系方式：${contact || '（未填写）'}`,
    `来源 IP：${ip}`,
    `时间：${new Date().toISOString()}`,
    '',
    content,
    '',
    '—— 系统环境 ——',
    ...Object.entries(systemInfo || {}).map(([k, v]) => `${k}: ${v}`),
  ].join('\n');

  return {
    from: `"NCL 反馈" <${CFG.user}>`,
    to: CFG.target,
    subject,
    text,
    html,
  };
}

// ---------- 处理函数 ----------

/**
 * 校验并规范化请求体。
 *
 * @returns {{ error: string, status?: number } | { value: object }}
 */
function validate(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    return { error: '请求体必须是 JSON 对象', status: 400 };
  }

  const type = typeof body.type === 'string' ? body.type.trim().toLowerCase() : '';
  if (!TYPES[type]) {
    return { error: 'type 必须是 bug / suggestion / other 之一', status: 400 };
  }

  const content = sanitizeText(body.content);
  if (!content) {
    return { error: 'content 不能为空', status: 400 };
  }
  if (codePoints(content) > MAX_CONTENT) {
    return { error: `content 不能超过 ${MAX_CONTENT} 字`, status: 400 };
  }

  const contact = sanitizeText(body.contact).slice(0, MAX_CONTACT);

  let systemInfo = body.systemInfo;
  if (systemInfo == null) {
    systemInfo = {};
  } else if (typeof systemInfo !== 'object' || Array.isArray(systemInfo)) {
    return { error: 'systemInfo 必须是对象', status: 400 };
  }

  // systemInfo 只允许少量短字符串字段：防止客户端塞进大段内容或嵌套结构
  const cleanSys = {};
  for (const [k, v] of Object.entries(systemInfo)) {
    if (typeof k !== 'string' || v == null) continue;
    const key = sanitizeText(k).slice(0, 40);
    const val = sanitizeText(typeof v === 'string' ? v : String(v)).slice(0, 200);
    if (key && val) cleanSys[key] = val;
    if (Object.keys(cleanSys).length >= 20) break;
  }
  if (Buffer.byteLength(JSON.stringify(cleanSys), 'utf8') > MAX_SYSTEM_INFO_BYTES) {
    return { error: 'systemInfo 过大', status: 400 };
  }

  return { value: { type, content, contact, systemInfo: cleanSys } };
}

/**
 * Express 路由处理函数。
 * 调用方需先挂 express.json({ limit: '32kb' })。
 */
async function handleFeedback(req, res) {
  // 1) 限流（先于任何处理，避免被刷爆）
  const ip = clientIp(req);
  const rl = limiter.check(ip);
  if (!rl.ok) {
    res.setHeader('Retry-After', String(rl.retryAfterSec));
    return res.status(429).json({ error: `提交太频繁，请 ${rl.retryAfterSec} 秒后再试` });
  }

  // 2) 校验
  const v = validate(req.body);
  if (v.error) {
    return res.status(v.status || 400).json({ error: v.error });
  }
  const { type, content, contact, systemInfo } = v.value;

  const id = crypto.randomBytes(6).toString('hex');
  const typeLabel = TYPES[type];

  // 3) 先落本地日志：即使下面邮件失败，这条反馈也不会丢
  appendLog({
    id,
    at: new Date().toISOString(),
    type,
    content,
    contact,
    systemInfo,
    ip,
  });

  // 4) 发送邮件
  if (!smtpConfigured()) {
    console.warn('[feedback] SMTP 未配置完整，反馈只写入本地日志:', id);
    return res.status(503).json({
      error: '服务端暂未开通邮件反馈，请联系作者（反馈已记录）',
      data: { id },
    });
  }

  const mailer = getTransporter();
  if (!mailer) {
    // 区分「没装 nodemailer」和「装了但初始化失败」，否则线上只看到一句
    // 笼统的「邮件组件不可用」，根本没法判断该去查什么
    const why = nodemailerMissing ? '未安装 nodemailer（npm i）' : 'SMTP 传输初始化失败';
    console.error(`[feedback] 邮件组件不可用（${why}），反馈只写入本地日志: ${id}`);
    return res.status(503).json({
      error: `服务端邮件组件不可用：${why}（反馈已记录）`,
      data: { id },
    });
  }

  try {
    await mailer.sendMail(buildMail({ id, typeLabel, content, contact, systemInfo, ip }));
    console.log(`[feedback] 已发送 ${id}（${typeLabel}）来自 ${ip}`);
    return res.json({ success: true, data: { id } });
  } catch (err) {
    // 邮件失败但日志已在：返回 502 让用户知道没送到，同时把编号给出来以便人工查找
    console.error(`[feedback] 邮件发送失败 ${id}:`, err.message);
    return res.status(502).json({
      error: '邮件发送失败，反馈已记录，我们会尽快处理',
      data: { id },
    });
  }
}

module.exports = {
  handleFeedback,
  // 导出内部函数便于单测
  _internals: { sanitizeText, escapeHtml, oneLine, codePoints, validate, createRateLimiter, clientIp, CFG },
};