#!/usr/bin/env node
/**
 * mc-relay（VPN 专用中继）回归测试
 *
 * 用法：node test-regression.js
 *
 * 覆盖：
 *   - /api/host 只返回房间号 + token，不再分配端口
 *   - VPN 房间必须建立控制通道才能签发邀请
 *   - 邀请口令一次性、错误口令被拒、不带 token 的 join 被拒
 *   - 虚拟 IP 由服务器分配且互不重复
 *   - 房主↔玩家双向帧按虚拟 IP 正确转发
 *   - 旧版端口转发握手（hello/data）在 VPN 端口上被拒绝
 *   - /health 指标
 */
'use strict';

const net = require('net');
const http = require('http');
const { spawn } = require('child_process');
const path = require('path');
const { frame, frameToHost } = require('./vpn-tunnel');

const HTTP_PORT = 18090;
const VPN_PORT = HTTP_PORT + 1;    // 与 relay-server.js 一致：HTTP_PORT + 1
const MAGIC = 'MCRELAY';

let pass = 0, fail = 0;
const check = (n, c, e) => {
  if (c) { pass++; console.log(`  ✓ ${n}`); }
  else { fail++; console.log(`  ✗ ${n}${e !== undefined ? '  -> ' + e : ''}`); }
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function post(pathname, body) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body || {});
    const req = http.request({
      host: '127.0.0.1', port: HTTP_PORT, path: pathname, method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) },
    }, (res) => {
      let d = '';
      res.on('data', (c) => { d += c; });
      res.on('end', () => {
        let j = null;
        try { j = JSON.parse(d); } catch { /* 非 JSON */ }
        resolve({ status: res.statusCode, json: j, raw: d });
      });
    });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

function get(pathname) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: HTTP_PORT, path: pathname }, (res) => {
      let d = '';
      res.on('data', (c) => { d += c; });
      res.on('end', () => {
        let j = null;
        try { j = JSON.parse(d); } catch { /* 非 JSON */ }
        resolve({ status: res.statusCode, json: j, raw: d });
      });
    }).on('error', reject);
  });
}

/** 建一条到 VPN 端口的长连接，返回 { sock, msgs, frames } */
function vpnConnect(handshake) {
  return new Promise((resolve) => {
    const sock = net.connect(VPN_PORT, '127.0.0.1');
    sock.on('close', () => { if (process.env.RT_DEBUG) console.log('  [dbg] 连接关闭', handshake.type); });
    sock.setNoDelay(true);
    const msgs = [];
    const frames = [];
    let text = '';
    let bin = Buffer.alloc(0);
    let binaryMode = false;
    sock.on('data', (chunk) => {
      if (binaryMode) {
        bin = Buffer.concat([bin, chunk]);
        while (bin.length >= 4) {
          const len = bin.readUInt32LE(0);
          if (bin.length < 4 + len) break;
          frames.push(bin.slice(4, 4 + len));
          bin = bin.slice(4 + len);
        }
        return;
      }
      if (process.env.RT_DEBUG) console.log('  [dbg] 收到', chunk.length, '字节:', JSON.stringify(chunk.toString().slice(0, 60)));
      text += chunk.toString('utf8');
      let i;
      while ((i = text.indexOf('\n')) >= 0) {
        const line = text.slice(0, i);
        text = text.slice(i + 1);
        try { msgs.push(JSON.parse(line)); } catch { /* 非 JSON */ }
      }
    });
    sock.on('error', () => {});
    sock.on('connect', () => {
      sock.write(MAGIC + JSON.stringify(handshake) + '\n');
      resolve({ sock, msgs, frames, binary: () => { binaryMode = true; } });
    });
  });
}

/** 控制通道走 HTTP 端口（魔数 + hello），与 VPN 帧端口分开 */
function controlConnect(room) {
  return new Promise((resolve) => {
    const sock = net.connect(HTTP_PORT, '127.0.0.1');
    sock.setNoDelay(true);
    const msgs = [];
    let text = '';
    sock.on('data', (chunk) => {
      text += chunk.toString('utf8');
      let i;
      while ((i = text.indexOf('\n')) >= 0) {
        const line = text.slice(0, i);
        text = text.slice(i + 1);
        try { msgs.push(JSON.parse(line)); } catch { /* 非 JSON */ }
      }
    });
    sock.on('error', () => {});
    sock.on('connect', () => {
      sock.write(MAGIC + JSON.stringify({ type: 'hello', room: room.room, token: room.token }) + '\n');
      resolve({ sock, msgs });
    });
  });
}

/** 建房 + 控制通道 */
async function newRoom(name) {
  const r = await post('/api/host', { name: name || 'VPN 回归' });
  if (r.status !== 200) throw new Error('开房失败 ' + r.raw);
  const room = r.json;
  const ctrl = await controlConnect(room);
  await sleep(300);
  return { room, ctrl };
}

async function main() {
  // 由环境变量决定被测实现：RELAY_BIN 指向 Go 二进制时测 Go 版
  const relay = process.env.RELAY_BIN
    ? spawn(process.env.RELAY_BIN, [], {
        env: { ...process.env, HTTP_PORT: String(HTTP_PORT), VPN_PORT: String(HTTP_PORT + 1), HOST: '127.0.0.1' },
        stdio: ['ignore', 'pipe', 'pipe'],
      })
    : spawn(process.execPath, [path.join(__dirname, 'relay-server.js')], {
        env: { ...process.env, HTTP_PORT: String(HTTP_PORT), HOST: '127.0.0.1' },
        stdio: ['ignore', 'pipe', 'pipe'],
      });
  let relayLog = '';
  relay.stdout.on('data', (d) => { relayLog += d; });
  relay.stderr.on('data', (d) => { relayLog += d; });
  await sleep(1200);

  // ---------- 1：/api/host 不再分配端口 ----------
  console.log('\n[1] /api/host 只返回房间号与 token');
  {
    const r = await post('/api/host', { name: '接口' });
    check('开房返回 200', r.status === 200, r.raw);
    check('返回 room 与 token', !!r.json && typeof r.json.room === 'string'
          && typeof r.json.token === 'string', r.raw);
    check('不再返回端口号', !!r.json && r.json.port === undefined, r.raw);
  }

  // ---------- 2：未建控制通道前不能签发邀请 ----------
  console.log('\n[2] 房主尚未建立控制通道时拒绝签发邀请');
  {
    const r = await post('/api/host', { name: '无控制' });
    // token 错误的控制通道应被直接断开
    const ctrl = await controlConnect({ room: r.json.room, token: 'wrong-token' });
    await sleep(300);
    check('错误的控制通道 token 被拒绝', ctrl.sock.destroyed, relayLog.slice(-200));
    const again = await post('/api/host', { name: '无控制2' });
    // 正确的控制通道，但还没有 vpn-host 连接：签发邀请应报错
    const ok = await controlConnect(again.json);
    await sleep(200);
    ok.sock.write(JSON.stringify({ type: 'vpn-token', id: 1 }) + '\n');
    await sleep(300);
    const err = ok.msgs.find((m) => m.type === 'vpn-token-error');
    check('没有 vpn-host 连接时返回 vpn-token-error', !!err, JSON.stringify(ok.msgs));
    ok.sock.destroy();
    ctrl.sock.destroy();
  }

  // ---------- 3：VPN 完整流程 ----------
  console.log('\n[3] VPN 房间：邀请 → 领取 → 加入 → 双向转发');
  const { room, ctrl } = await newRoom('完整流程');
  const vhost = await vpnConnect({ type: 'vpn-host', room: room.room, token: room.token });
  await sleep(300);
  check('房主 VPN 连接被接受', !vhost.sock.destroyed, relayLog.slice(-200));

  ctrl.sock.write(JSON.stringify({ type: 'vpn-token', id: 1 }) + '\n');
  await sleep(300);
  const tok = ctrl.msgs.find((m) => m.type === 'vpn-token' && m.id === 1);
  check('房主拿到邀请（token + 口令）', !!tok && typeof tok.token === 'string'
        && typeof tok.code === 'string', JSON.stringify(ctrl.msgs));
  check('服务器分配 10.77.0.x 地址', !!tok && /^10\.77\.0\.[2-9]$/.test(tok.ip), tok && tok.ip);

  ctrl.sock.write(JSON.stringify({ type: 'vpn-token', id: 2 }) + '\n');
  await sleep(300);
  const tok2 = ctrl.msgs.find((m) => m.type === 'vpn-token' && m.id === 2);
  check('第二位玩家分到不同地址', !!tok && !!tok2 && tok.ip !== tok2.ip,
        tok && tok2 && `${tok.ip} vs ${tok2.ip}`);
  check('Token 不重复', !!tok && !!tok2 && tok.token !== tok2.token);

  // 错误口令
  const badCode = await new Promise((resolve) => {
    const s = net.connect(VPN_PORT, '127.0.0.1', () => {
      s.write(MAGIC + JSON.stringify({ type: 'vpn-claim', room: room.room, code: 'deadbeef' }) + '\n');
    });
    let d = '';
    s.on('data', (x) => { d += x; });
    s.on('close', () => resolve(d));
    s.on('error', () => resolve(''));
  });
  check('错误口令被拒绝', badCode.includes('口令无效'), badCode);

  // 凭口令领取
  const claim = await new Promise((resolve) => {
    const s = net.connect(VPN_PORT, '127.0.0.1', () => {
      s.write(MAGIC + JSON.stringify({ type: 'vpn-claim', room: room.room, code: tok.code }) + '\n');
    });
    let d = '';
    s.on('data', (x) => { d += x; });
    s.on('close', () => { try { resolve(JSON.parse(d)); } catch { resolve({}); } });
    s.on('error', () => resolve({}));
  });
  check('凭口令拿到 token/uuid/ip', claim.ip === tok.ip && !!claim.token && !!claim.uuid,
        JSON.stringify(claim));

  // 同一口令不能再用
  const reuseCode = await new Promise((resolve) => {
    const s = net.connect(VPN_PORT, '127.0.0.1', () => {
      s.write(MAGIC + JSON.stringify({ type: 'vpn-claim', room: room.room, code: tok.code }) + '\n');
    });
    let d = '';
    s.on('data', (x) => { d += x; });
    s.on('close', () => resolve(d));
    s.on('error', () => resolve(''));
  });
  check('口令一次性', reuseCode.includes('口令无效'), reuseCode);

  // 不带 token 的 join
  const badJoin = await new Promise((resolve) => {
    const s = net.connect(VPN_PORT, '127.0.0.1', () => {
      s.write(MAGIC + JSON.stringify({ type: 'vpn-join', room: room.room, uuid: claim.uuid }) + '\n');
    });
    let closed = false;
    s.on('close', () => { closed = true; resolve(closed); });
    s.on('error', () => resolve(true));
    setTimeout(() => resolve(closed), 800);
  });
  check('不带 token 的 vpn-join 被拒绝', badJoin === true);

  // 正式加入
  // 玩家的 VPN 连接会收到裸 IP 帧（服务器 → 玩家），先切到二进制模式
  const peer = await vpnConnect({ type: 'vpn-join', room: room.room, uuid: claim.uuid, token: claim.token });
  peer.binary();
  await sleep(300);
  check('玩家带 token 加入成功', !peer.sock.destroyed);
  check('房主收到 vpn-peer 通知',
        ctrl.msgs.some((m) => m.type === 'vpn-peer' && m.uuid === claim.uuid),
        JSON.stringify(ctrl.msgs).slice(0, 300));

  // 上行：玩家 → 房主（帧前缀 = 玩家虚拟 IP）
  const ipPkt = Buffer.alloc(20);
  ipPkt[0] = 0x45; ipPkt.writeUInt16BE(20, 2); ipPkt[9] = 17;
  ipPkt.writeUInt8(10, 12);
  vhost.binary();
  peer.sock.write(frame(ipPkt));
  await sleep(400);
  const up = vhost.frames.find((f) => f.length === 1 + claim.ip.length + 20);
  check('房主收到的上行帧带玩家虚拟 IP',
        !!up && up.slice(1, 1 + claim.ip.length).toString() === claim.ip,
        vhost.frames.map((f) => f.slice(0, 20).toString('hex')).join('|'));

  // 下行：房主 → 玩家
  vhost.sock.write(frameToHost(claim.ip, ipPkt));
  await sleep(500);
  check('玩家收到房主下发的帧', peer.frames.some((f) => f.length === 20 && f[0] === 0x45),
        peer.frames.length + ' 帧');

  // 同一 token 重复使用
  const reuseJoin = await new Promise((resolve) => {
    const s = net.connect(VPN_PORT, '127.0.0.1', () => {
      s.write(MAGIC + JSON.stringify({ type: 'vpn-join', room: room.room, uuid: claim.uuid, token: claim.token }) + '\n');
    });
    let closed = false;
    s.on('close', () => { closed = true; resolve(true); });
    s.on('error', () => resolve(true));
    setTimeout(() => resolve(closed), 800);
  });
  check('同一 token 不能重复加入', reuseJoin === true);

  // ---------- 4：HTTP 端口不接受 VPN 帧 ----------
  console.log('\n[4] HTTP 端口与 VPN 端口分离');
  {
    const r = await new Promise((resolve) => {
      const s = net.connect(HTTP_PORT, '127.0.0.1', () => {
        s.write(MAGIC + JSON.stringify({ type: 'vpn-host', room: room.room, token: room.token }) + '\n');
      });
      let d = '';
      s.on('data', (x) => { d += x; });
      s.on('close', () => resolve({ closed: true, raw: d }));
      s.on('error', () => resolve({ closed: true, raw: d }));
      setTimeout(() => resolve({ closed: false, raw: d }), 1500);
    });
    check('HTTP 端口上的 VPN 握手不会被当成 VPN（连接被关）', r.closed, r.raw.slice(0, 120));
  }

  // ---------- 5：旧协议（hello/data）不被接受 ----------
  console.log('\n[5] 旧版端口转发协议已移除');
  {
    const closed = await new Promise((resolve) => {
      const s = net.connect(VPN_PORT, '127.0.0.1', () => {
        s.write(MAGIC + JSON.stringify({ type: 'data', room: room.room, token: room.token, id: 1 }) + '\n');
      });
      let c = false;
      s.on('close', () => { c = true; resolve(true); });
      s.on('error', () => resolve(true));
      setTimeout(() => resolve(c), 1200);
    });
    check('data 握手在 VPN 端口被拒绝', closed === true);
  }

  // ---------- 6：/health ----------
  console.log('\n[6] /health');
  {
    const h = await get('/health');
    check('/health 返回 200', h.status === 200, h.status);
    check('/health 不再暴露端口池', !!h.json && h.json.ports === undefined, h.raw);
    check('/health 带 rooms/players', !!h.json && typeof h.json.rooms === 'number'
          && typeof h.json.players === 'number', h.raw);
  }

  // ---------- 7：速率限制 ----------
  console.log('\n[7] 开房限流');
  {
    const results = [];
    for (let i = 0; i < 15; i++) {
      results.push((await post('/api/host', { name: 'x' + i })).status);
    }
    check('高频开房会被限流（429）', results.includes(429), results.join(','));
  }

  for (const s of [ctrl.sock, vhost.sock, peer.sock]) {
    try { s.destroy(); } catch { /* 忽略 */ }
  }
  relay.kill();
  if (fail) {
    console.log('\n--- relay log ---\n' + relayLog.slice(-3000));
  }
  console.log(`\n=== 通过 ${pass}，失败 ${fail} ===`);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });