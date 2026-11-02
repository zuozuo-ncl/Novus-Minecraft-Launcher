#!/usr/bin/env node
/**
 * MC 联机 —— 房主端（原型）
 *
 * 用法：
 *     node relay-host.js --server 39.106.60.15 --local 25565 [--name "我的世界"]
 *
 * 做的事：
 *   1. 向中转申请房间，拿到端口
 *   2. 建立控制通道（保活 + 接收新玩家通知）
 *   3. 每来一个新玩家，就新建一条数据通道并接到本地 MC
 *
 * 这个脚本先用来验证链路；跑通后同样的逻辑会移植进 Java 启动器。
 */
'use strict';

const net = require('net');
const http = require('http');

// ---------- 参数 ----------
const argv = process.argv.slice(2);
const arg = (k, d) => {
  const i = argv.indexOf('--' + k);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : d;
};

const SERVER = arg('server', '39.106.60.15');
const HTTP_PORT = Number(arg('http-port', 8080));
const LOCAL_PORT = Number(arg('local', 25565));
const LOCAL_HOST = arg('local-host', '127.0.0.1');
const ROOM_NAME = arg('name', '我的联机房间');
// 中转配置了 API_KEY 时必填（见 deploy-relay.sh）
const API_KEY = arg('api-key', process.env.MC_RELAY_API_KEY || '');

const log = (...a) => console.log(new Date().toISOString(), ...a);

// 隧道握手魔数：与 relay-server.js 中的 MAGIC 必须一致
const MAGIC = 'MCRELAY';

// ---------- 1. 申请房间 ----------
function requestRoom() {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({ name: ROOM_NAME, maxPlayers: 5 });
    const req = http.request({
      host: SERVER,
      port: HTTP_PORT,
      path: '/api/host',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
        ...(API_KEY ? { 'X-Api-Key': API_KEY } : {}),
      },
      timeout: 15000,
    }, (res) => {
      let d = '';
      res.on('data', (c) => { d += c; });
      res.on('end', () => {
        if (res.statusCode !== 200) return reject(new Error(`申请失败 HTTP ${res.statusCode}: ${d}`));
        try { resolve(JSON.parse(d)); } catch (e) { reject(e); }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error('申请房间超时')));
    req.write(body);
    req.end();
  });
}

// ---------- 2. 建立到本地的连接 ----------
function connectLocal() {
  return new Promise((resolve, reject) => {
    const s = net.connect(LOCAL_PORT, LOCAL_HOST);
    s.setNoDelay(true);
    s.once('connect', () => resolve(s));
    s.once('error', reject);
  });
}

// ---------- 3. 数据通道 ----------
function openDataChannel(info, id) {
  const s = net.connect(info.port, SERVER);
  s.setNoDelay(true);

  s.once('connect', async () => {
    s.write(MAGIC + JSON.stringify({ type: 'data', room: info.room, token: info.token, id }) + '\n');
    try {
      const local = await connectLocal();
      local.setNoDelay(true);

      // 双向转发
      s.pipe(local);
      local.pipe(s);

      const close = () => { try { s.destroy(); } catch {} ; try { local.destroy(); } catch {} };
      s.on('close', close); local.on('close', close);
      s.on('error', () => {}); local.on('error', () => {});

      log(`玩家 #${id} 已接入本地 ${LOCAL_HOST}:${LOCAL_PORT}`);
    } catch (e) {
      log(`玩家 #${id} 连接本地 MC 失败：${e.message}（MC 服务端开了吗？）`);
      s.destroy();
    }
  });
  s.on('error', (e) => log(`数据通道 #${id} 错误: ${e.message}`));
}

// ---------- 4. 主流程 ----------
let heartbeat = null;
// 控制通道断开后的重连退避（毫秒）
let backoff = 2000;

/**
 * 用**同一个房间**重连控制通道
 *
 * 之前的做法是重新调用 main()：会申请到新端口，旧房间里的玩家
 * 全部掉线，而且旧房间要等心跳超时才被回收。
 */
async function reconnect(info) {
  log(`重连房间 ${info.room} ...`);
  await openControl(info);
}

async function main() {
  log(`向 ${SERVER}:${HTTP_PORT} 申请房间...`);
  const info = await requestRoom();
  log(`✓ 房间已创建：${SERVER}:${info.port}  (房间号 ${info.room})`);
  console.log('');
  console.log('  ┌─────────────────────────────────────────');
  console.log(`  │  联机地址： ${SERVER}:${info.port}`);
  console.log(`  │  把这个地址发给朋友即可`);
  console.log('  └─────────────────────────────────────────');
  console.log('');
  await openControl(info);
}

/**
 * 建立/重建控制通道
 *
 * 房间信息（room + token + port）不变，所以重连后朋友用的地址依然有效。
 */
function openControl(info) {
  return new Promise((resolve, reject) => {
    const ctrl = net.connect(info.port, SERVER);
    ctrl.setNoDelay(true);
    let buf = '';
    let settled = false;

    ctrl.once('connect', () => {
      ctrl.write(MAGIC + JSON.stringify({ type: 'hello', room: info.room, token: info.token }) + '\n');
      log('控制通道已建立，房间在线');
    });

    ctrl.on('data', (chunk) => {
      buf += chunk.toString('utf8');
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i);
        buf = buf.slice(i + 1);
        if (!line.trim()) continue;
        let msg;
        try { msg = JSON.parse(line); } catch { continue; }
        if (msg.type === 'join') {
          log(`新玩家 #${msg.id} 正在接入...`);
          openDataChannel(info, msg.id);
        } else if (msg.type === 'hello-ok') {
          backoff = 2000;   // 连接成功：退避重置
          log(`服务端确认，公网端口 ${msg.port}`);
          if (!settled) { settled = true; resolve(); }
        }
      }
    });

    ctrl.on('error', (e) => {
      log(`控制通道错误: ${e.message}`);
      if (!settled) { settled = true; reject(e); }
    });
    ctrl.on('close', () => {
      if (heartbeat) clearInterval(heartbeat);
      if (!settled) { settled = true; reject(new Error('控制通道在确认前断开')); return; }
      // 指数退避重连（上限 5 分钟）。以前固定 5 秒且永不放弃：房间已被回收时
      // 每次都「重连失败 → 重新申请房间」，联机地址频繁变化，还持续消耗开房限流
      backoff = Math.min(backoff * 2, 300000);
      log(`控制通道断开，${Math.round(backoff / 1000)} 秒后用同一个房间重连...`);
      setTimeout(() => reconnect(info).catch((e) => {
        log('重连失败（房间可能已被回收）：' + e.message);
        setTimeout(() => main().catch((err) => log('启动失败：' + err.message)),
                    Math.min(backoff, 60000));
      }), backoff);
    });

    // 心跳，防止中转把房间当成死房间；重连时要清掉，否则每重连一次多一个定时器
    heartbeat = setInterval(() => {
      if (!ctrl.destroyed) ctrl.write(JSON.stringify({ type: 'ping' }) + '\n');
    }, 5000);   // 中转判定超时是 5s*3=15s；以前 10s 一跳，丢一次心跳就会被误回收
    heartbeat.unref();
  });
}

main().catch((e) => {
  log('启动失败：', e.message);
  process.exit(1);
});