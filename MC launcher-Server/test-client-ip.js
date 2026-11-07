// 反馈接口的单元测试：clientIp 对 CF-Connecting-IP / X-Forwarded-For / 伪造的取舍
//
// 运行：node test-client-ip.js
const { _internals } = require('./feedback');

let pass = 0;
const fails = [];

function withEnv(env, fn) {
  const backup = {};
  for (const k of Object.keys(env).concat(['TRUST_PROXY', 'CF_TRUSTED'])) {
    backup[k] = process.env[k];
  }
  for (const k of ['TRUST_PROXY', 'CF_TRUSTED']) delete process.env[k];
  Object.assign(process.env, env);
  try {
    return fn();
  } finally {
    for (const [k, v] of Object.entries(backup)) {
      if (v === undefined) delete process.env[k]; else process.env[k] = v;
    }
  }
}

function check(name, cond, detail) {
  if (cond) { pass++; console.log('  ok   ' + name); }
  else { fails.push(name + (detail ? ' -> ' + detail : '')); console.log('  FAIL ' + name + (detail ? ' -> ' + detail : '')); }
}

function ip(env, headers, direct = '127.0.0.1') {
  return withEnv(env, () => _internals.clientIp({ headers, ip: direct, socket: { remoteAddress: direct } }));
}

console.log('—— clientIp ——');

check('默认（TRUST_PROXY 未开）用直连地址，忽略任何头',
    ip({}, { 'cf-connecting-ip': '1.1.1.1', 'x-forwarded-for': '2.2.2.2' }) === '127.0.0.1');

check('开启信任后 CF-Connecting-IP 优先',
    ip({ TRUST_PROXY: '1' }, { 'cf-connecting-ip': '1.1.1.1', 'x-forwarded-for': '2.2.2.2' }) === '1.1.1.1');

check('没有 CF 头时取 X-Forwarded-For 第一项',
    ip({ TRUST_PROXY: '1' }, { 'x-forwarded-for': '2.2.2.2, 3.3.3.3' }) === '2.2.2.2');

check('两种头都没有时退回直连地址',
    ip({ TRUST_PROXY: '1' }, {}) === '127.0.0.1');

check('CF_TRUSTED 不含直连来源时忽略 CF 头（防伪造）',
    ip({ TRUST_PROXY: '1', CF_TRUSTED: '10.0.0.1' }, { 'cf-connecting-ip': '9.9.9.9' }) === '127.0.0.1');

check('CF_TRUSTED 含直连来源时采用 CF 头（兼容 ::ffff: 前缀）',
    ip({ TRUST_PROXY: '1', CF_TRUSTED: '127.0.0.1,10.0.0.1' },
       { 'cf-connecting-ip': '1.1.1.1' }, '::ffff:127.0.0.1') === '1.1.1.1');

check('CF_TRUSTED 匹配时不误伤普通 XFF',
    ip({ TRUST_PROXY: '1', CF_TRUSTED: '127.0.0.1' }, { 'x-forwarded-for': '5.5.5.5' }) === '5.5.5.5');

console.log('\n通过 ' + pass + '，失败 ' + fails.length);
if (fails.length) { fails.forEach(f => console.log('  - ' + f)); process.exit(1); }