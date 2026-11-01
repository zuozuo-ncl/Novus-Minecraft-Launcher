#!/usr/bin/env node
/**
 * 端到端测试：不依赖 Minecraft，用一个假 MC 服务端验证隧道链路
 *
 * 模拟：
 *   假MC服务端(:26001)  ←  房主端  ←→  中转  ←→  假朋友客户端
 *
 * 验证点：
 *   1. 房主能申请到房间端口
 *   2. 朋友连上后，数据能双向到达
 *   3. 多个朋友可以同时在线，数据互不串台
 *   4. 房主断开后，房间被回收
 */
'use strict';

const net = require('net');
const http = require('http');
const { spawn } = require('child_process');
const path = require('path');

const HTTP_PORT = 18080;
const RANGE = '26000-26009';
const LOCAL_MC_PORT = 26100;

let pass = 0, fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log(`  ✓ ${name}`); }
  else { fail++; console.log(`  ✗ ${name}${extra !== undefined ? '  -> ' + extra : ''}`); }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- 假 MC 服务端：收到什么就回显 "ECHO:<内容>" ----------
function startFakeMc() {
  return new Promise((resolve) => {
    const srv = net.createServer((sock) => {
      sock.on('data', (d) => sock.write('ECHO:' + d.toString()));
      sock.on('error', () => {});
    });
    srv.listen(LOCAL_MC_PORT, '127.0.0.1', () => resolve(srv));
  });
}

// ---------- 朋友客户端：连中转端口，发消息，等回复 ----------
function friendConnect(port, msg, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const s = net.connect(port, '127.0.0.1');
    let buf = '';
    const t = setTimeout(() => { s.destroy(); reject(new Error('超时')); }, timeoutMs);
    s.on('connect', () => s.write(msg));
    s.on('data', (d) => {
      buf += d.toString();
      if (buf.includes('ECHO:')) {
        clearTimeout(t);
        s.destroy();
        resolve(buf);
      }
    });
    s.on('error', (e) => { clearTimeout(t); reject(e); });
  });
}

async function main() {
  console.log('=== 启动假 MC 服务端 ===');
  const mc = await startFakeMc();

  console.log('=== 启动中转服务器 ===');
  const relay = spawn(process.execPath, [path.join(__dirname, 'relay-server.js')], {
    env: { ...process.env, HTTP_PORT: String(HTTP_PORT), RANGE, HOST: '127.0.0.1' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let relayLog = '';
  relay.stdout.on('data', (d) => { relayLog += d; });
  relay.stderr.on('data', (d) => { relayLog += d; });
  await sleep(1200);

  console.log('=== 启动房主端 ===');
  const host = spawn(process.execPath, [path.join(__dirname, 'relay-host.js'),
    '--server', '127.0.0.1', '--http-port', String(HTTP_PORT),
    '--local', String(LOCAL_MC_PORT), '--name', '测试房间'], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let hostLog = '';
  host.stdout.on('data', (d) => { hostLog += d; });
  host.stderr.on('data', (d) => { hostLog += d; });

  // 等房间建立
  let port = null;
  for (let i = 0; i < 30; i++) {
    await sleep(300);
    // 只匹配「联机地址」那一行（注意不能匹配到 HTTP API 端口）
    const m = hostLog.match(/联机地址：\s*127\.0\.0\.1:(\d+)/);
    if (m) { port = Number(m[1]); break; }
  }
  check('房主成功申请到端口', port !== null, hostLog.slice(-200));
  if (port === null) return finish();

  check('分配的端口在池内', port >= 26000 && port <= 26009, port);

  // 等控制通道就绪
  await sleep(1000);

  console.log('\n=== 测试单向数据 ===');
  try {
    const r = await friendConnect(port, 'hello-relay');
    check('朋友数据到达假 MC 并回显', r.includes('ECHO:hello-relay'), r);
  } catch (e) {
    check('朋友数据到达假 MC 并回显', false, e.message);
  }

  console.log('\n=== 测试多个朋友并发 ===');
  try {
    const results = await Promise.all([
      friendConnect(port, 'player-A-111'),
      friendConnect(port, 'player-B-222'),
      friendConnect(port, 'player-C-333'),
    ]);
    check('3 个朋友同时在线', results.length === 3);
    check('玩家A 数据正确', results[0].includes('ECHO:player-A-111'), results[0]);
    check('玩家B 数据正确（没有串台）', results[1].includes('ECHO:player-B-222'), results[1]);
    check('玩家C 数据正确', results[2].includes('ECHO:player-C-333'), results[2]);
  } catch (e) {
    check('多玩家并发', false, e.message);
  }

  console.log('\n=== 测试大包（MC 的区块数据约 20KB）===');
  try {
    const big = 'X'.repeat(20000);
    const r = await friendConnect(port, big, 15000);
    check('20KB 数据完整转发', r.length >= 20005, `收到 ${r.length} 字节`);
  } catch (e) {
    check('20KB 数据完整转发', false, e.message);
  }

  console.log('\n=== 测试房间列表 API ===');
  try {
    const list = await new Promise((resolve, reject) => {
      http.get({ host: '127.0.0.1', port: HTTP_PORT, path: '/api/rooms' }, (res) => {
        let d = '';
        res.on('data', (c) => { d += c; });
        res.on('end', () => resolve(JSON.parse(d)));
      }).on('error', reject);
    });
    check('/api/rooms 能列出当前房间', list.rooms && list.rooms.length === 1, JSON.stringify(list));
    check('房间名正确', list.rooms[0].name === '测试房间', list.rooms[0]);
  } catch (e) {
    check('/api/rooms', false, e.message);
  }

  console.log('\n=== 测试端口回收 ===');
  host.kill();
  await sleep(2500);
  try {
    await friendConnect(port, 'should-fail', 2500);
    check('房主断开后端口不可用', false, '仍然连上了');
  } catch {
    check('房主断开后端口拒绝连接', true);
  }

  const health = await new Promise((resolve) => {
    http.get({ host: '127.0.0.1', port: HTTP_PORT, path: '/health' }, (res) => {
      let d = '';
      res.on('data', (c) => { d += c; });
      res.on('end', () => resolve(JSON.parse(d)));
    }).on('error', () => resolve({}));
  });
  check('房间已从列表中移除', health.rooms === 0, health);

  mc.close();
  relay.kill();
  finish();
}

function finish() {
  console.log(`\n=== 通过 ${pass}，失败 ${fail} ===`);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); finish(); });