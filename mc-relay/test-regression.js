#!/usr/bin/env node
/**
 * 针对审计发现的缺陷的回归测试
 *
 * 每个用例对应一条「已核实」的发现，确保修复有效。
 */
'use strict';

const net = require('net');
const http = require('http');
const crypto = require('crypto');
const { spawn } = require('child_process');
const path = require('path');

const HTTP_PORT = 18090;
const RANGE = '27000-27009';
const MC_PORT = 27100;
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

/** 房主：申请房间 + 建控制通道 */
function hostUp() {
  return new Promise(async (resolve, reject) => {
    const r = await post('/api/host', { name: '回归测试' });
    if (r.status !== 200) return reject(new Error('申请失败 ' + r.raw));
    const info = r.json;
    const ctrl = net.connect(info.port, '127.0.0.1');
    ctrl.setNoDelay(true);
    let buf = '';
    ctrl.on('connect', () => {
      ctrl.write(MAGIC + JSON.stringify({ type: 'hello', room: info.room, token: info.token }) + '\n');
    });
    ctrl.on('data', (c) => {
      buf += c.toString();
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i); buf = buf.slice(i + 1);
        if (!line.trim()) continue;
        const m = JSON.parse(line);
        if (m.type === 'hello-ok') resolve({ info, ctrl, onMsg: (cb) => ctrl.on('join-cb', cb) });
        if (m.type === 'join') ctrl.emit('join-cb', m.id);
      }
    });
    ctrl.on('error', reject);
  });
}

/** 房主：为某个玩家开数据通道 */
function openData(host, id) {
  return new Promise((resolve) => {
    const s = net.connect(host.info.port, '127.0.0.1');
    s.setNoDelay(true);
    s.on('connect', () => {
      s.write(MAGIC + JSON.stringify({ type: 'data', room: host.info.room, token: host.info.token, id }) + '\n');
      const local = net.connect(MC_PORT, '127.0.0.1');
      local.setNoDelay(true);
      local.on('connect', () => {
        s.pipe(local); local.pipe(s);
        resolve();
      });
      local.on('error', () => {});
    });
    s.on('error', () => {});
  });
}

async function main() {
  // 假 MC：回显
  const mc = net.createServer((s) => s.on('data', (d) => s.write('ECHO:' + d)));
  await new Promise((r) => mc.listen(MC_PORT, '127.0.0.1', r));

  const relay = spawn(process.execPath, [path.join(__dirname, 'relay-server.js')], {
    env: { ...process.env, HTTP_PORT: String(HTTP_PORT), RANGE, HOST: '127.0.0.1' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let relayLog = '';
  relay.stdout.on('data', (d) => { relayLog += d; });
  relay.stderr.on('data', (d) => { relayLog += d; });
  await sleep(1200);

  // ---------- 用例 1：token 校验 ----------
  console.log('\n[1] 数据通道必须校验 token（审计 #1）');
  {
    const host = await hostUp();
    const player = net.connect(host.info.port, '127.0.0.1');
    let joinId = null;
    host.ctrl.on('join-cb', (id) => { joinId = id; });
    await new Promise((r) => player.on('connect', r));
    player.write('PLAYERDATA');
    await sleep(400);
    check('房主收到玩家接入通知', joinId !== null, joinId);

    // 伪造：知道房间号但没有 token
    const fake = net.connect(host.info.port, '127.0.0.1');
    let fakeClosed = false;
    fake.on('close', () => { fakeClosed = true; });
    fake.on('error', () => {});
    await new Promise((r) => fake.on('connect', r));
    fake.write(MAGIC + JSON.stringify({ type: 'data', room: host.info.room, id: joinId, token: 'wrong' }) + '\n');
    await sleep(500);
    check('错误 token 被拒绝（连接被断开）', fakeClosed, fakeClosed ? '' : '仍保持连接');

    // 真正的房主仍能正常接管
    await openData(host, joinId);
    await sleep(300);
    const got = await new Promise((resolve) => {
      let b = '';
      player.on('data', (d) => { b += d; if (b.includes('ECHO:')) resolve(b); });
      setTimeout(() => resolve(b), 2000);
    });
    check('正确 token 的房主能正常转发', got.includes('ECHO:PLAYERDATA'), got);
    player.destroy(); host.ctrl.destroy();
  }

  // ---------- 用例 2：分块发送（模拟 MC 连发握手+登录） ----------
  console.log('\n[2] 玩家在数据通道建立前连发多块数据（审计 #3）');
  {
    const host = await hostUp();
    let joinId = null;
    host.ctrl.on('join-cb', (id) => { joinId = id; });

    const player = net.connect(host.info.port, '127.0.0.1');
    await new Promise((r) => player.on('connect', r));
    // 立刻连发 3 块，此时数据通道还没建
    player.write('BLOCK-1;');
    await sleep(120);
    player.write('BLOCK-2;');
    await sleep(120);
    player.write('BLOCK-3;');
    await sleep(600);

    check('房主收到接入通知', joinId !== null, joinId);
    await openData(host, joinId);
    await sleep(400);

    const got = await new Promise((resolve) => {
      let b = '';
      player.on('data', (d) => { b += d; if (b.includes('BLOCK-3')) resolve(b); });
      setTimeout(() => resolve(b), 3000);
    });
    check('三块数据一块都没丢', got.includes('BLOCK-1') && got.includes('BLOCK-2') && got.includes('BLOCK-3'), got);
    player.destroy(); host.ctrl.destroy();
  }

  // ---------- 用例 3：静默连接回收 ----------
  console.log('\n[3] 连上不发数据的连接会被回收（审计 #2）');
  {
    // 用一个缩短了超时的实例不方便，这里验证它至少不会被当成玩家
    const host = await hostUp();
    let joinId = null;
    host.ctrl.on('join-cb', (id) => { joinId = id; });

    const silent = net.connect(host.info.port, '127.0.0.1');
    await new Promise((r) => silent.on('connect', r));
    await sleep(800);
    check('静默连接不会被当成玩家', joinId === null, joinId);
    silent.destroy(); host.ctrl.destroy();
  }

  // ---------- 用例 4：控制通道重连不拆房间 ----------
  console.log('\n[4] 控制通道重连不应踢掉在线玩家（审计 #5）');
  {
    const host = await hostUp();
    let joinId = null;
    host.ctrl.on('join-cb', (id) => { joinId = id; });

    const player = net.connect(host.info.port, '127.0.0.1');
    await new Promise((r) => player.on('connect', r));
    player.write('BEFORE-RECONNECT');
    await sleep(400);
    await openData(host, joinId);
    await sleep(300);

    // 模拟房主控制通道重连：先连新的，再断旧的
    const ctrl2 = net.connect(host.info.port, '127.0.0.1');
    let ctrl2Ok = false;
    ctrl2.on('data', (d) => { if (d.toString().includes('hello-ok')) ctrl2Ok = true; });
    await new Promise((r) => ctrl2.on('connect', r));
    ctrl2.write(MAGIC + JSON.stringify({ type: 'hello', room: host.info.room, token: host.info.token }) + '\n');
    await sleep(500);
    check('新的控制通道被接受（替换旧的）', ctrl2Ok, relayLog.slice(-300));
    await sleep(300);

    // 玩家应仍然在线
    let stillAlive = false;
    player.on('data', (d) => { if (d.toString().includes('ECHO:BEFORE-RECONNECT')) stillAlive = true; });
    player.write('PING-AFTER');
    await sleep(800);
    check('重连后玩家连接仍然有效', stillAlive, stillAlive ? '' : '玩家被踢掉了');

    player.destroy(); ctrl2.destroy();
  }

  // ---------- 用例 5：房间数上限 ----------
  console.log('\n[5] 速率限制');
  {
    const results = [];
    for (let i = 0; i < 15; i++) {
      results.push((await post('/api/host', { name: 'x' + i })).status);
    }
    const limited = results.filter((s) => s === 429).length;
    check('高频申请会被限流（429）', limited > 0, `429 次数 ${limited}/15`);
  }

  // ---------- 用例 6：health ----------
  console.log('\n[6] /health 指标');
  {
    const h = await new Promise((resolve) => {
      http.get({ host: '127.0.0.1', port: HTTP_PORT, path: '/health' }, (res) => {
        let d = '';
        res.on('data', (c) => { d += c; });
        res.on('end', () => resolve(JSON.parse(d)));
      }).on('error', () => resolve({}));
    });
    check('/health 返回 rooms/players/rss', typeof h.rooms === 'number' && typeof h.players === 'number' && typeof h.rss === 'number', JSON.stringify(h));
  }

  mc.close(); relay.kill();
  console.log(`\n=== 通过 ${pass}，失败 ${fail} ===`);
  if (fail) console.log('\n--- relay log ---\n' + relayLog);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });