/**
 * 对已运行的服务做一次接口冒烟测试
 *
 * 用法：
 *   PORT=5003 ADMIN_TOKEN=xxxx node test-api.js
 *
 * 说明：写接口现在需要管理令牌（server.js 的 requireAdmin），
 * 旧版本这里不带令牌直接写、并且没有任何断言 —— 只会打印 401 还提示「测试完成」，
 * 容易让人以为服务正常。现在每个请求都会断言状态码，失败以非 0 退出。
 */
const http = require('http');

const PORT = Number(process.env.PORT || 5003);
const HOST = process.env.HOST_IP || '127.0.0.1';
const TOKEN = process.env.ADMIN_TOKEN || '';

let pass = 0;
let fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log(`  ✓ ${name}`); }
  else { fail++; console.log(`  ✗ ${name}${extra !== undefined ? ' -> ' + JSON.stringify(extra).slice(0, 200) : ''}`); }
}

function request(method, path, data) {
  return new Promise((resolve) => {
    const postData = data ? JSON.stringify(data) : '';
    const headers = { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(postData) };
    if (TOKEN) headers['X-Admin-Token'] = TOKEN;
    const req = http.request({ hostname: HOST, port: PORT, path, method, headers }, (res) => {
      let body = '';
      res.on('data', (c) => { body += c; });
      res.on('end', () => {
        let json = null;
        try { json = JSON.parse(body); } catch { /* 非 JSON */ }
        resolve({ status: res.statusCode, json, body });
      });
    });
    req.on('error', (e) => resolve({ status: 0, error: e.message }));
    if (postData) req.write(postData);
    req.end();
  });
}

async function main() {
  console.log(`=== 接口冒烟测试 ${HOST}:${PORT} ===\n`);

  const h = await request('GET', '/health');
  check('/health 返回 200', h.status === 200, h.status);
  check('/health 带 latest 字段', h.json && typeof h.json.latest === 'string', h.json);

  const v = await request('GET', '/api/versions');
  check('/api/versions 返回 200', v.status === 200, v.status);
  check('/api/versions 有 latest 与 versions 数组',
        v.json && typeof v.json.latest === 'string' && Array.isArray(v.json.versions), v.json);

  const c = await request('POST', '/api/check-update', { currentVersion: v.json && v.json.latest });
  check('/api/check-update 返回 200', c.status === 200, c.status);
  check('当前已是最新时不提示更新', c.json && c.json.updateAvailable === false, c.json);
  check('无更新时 latestVersion 为 null', c.json && c.json.latestVersion === null, c.json);

  const bad = await request('POST', '/api/check-update', { currentVersion: 123 });
  check('currentVersion 非字符串 -> 400', bad.status === 400, bad.status);

  const tooBig = await new Promise((resolve) => {
    const body = JSON.stringify({ version: '1.0.0', pad: 'x'.repeat(200 * 1024) });
    const req = http.request({ hostname: HOST, port: PORT, path: '/api/versions', method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body),
                 'X-Admin-Token': TOKEN } }, (res) => {
      res.resume();
      res.on('end', () => resolve(res.statusCode));
    });
    req.on('error', () => resolve(0));
    req.write(body);
    req.end();
  });
  check('200KB 请求体被拒绝（413）', tooBig === 413, tooBig);

  const noAuth = await request('POST', '/api/versions', { version: '9.9.9' });
  const expectUnauthorized = TOKEN ? 401 : 503;
  check(`写接口未带令牌 -> ${expectUnauthorized}`, noAuth.status === expectUnauthorized, noAuth.status);

  console.log(`\n=== 通过 ${pass} / 失败 ${fail} ===`);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });