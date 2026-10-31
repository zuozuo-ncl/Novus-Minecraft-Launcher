#!/usr/bin/env node

'use strict';

const net = require('net');
const http = require('http');

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

const log = (...a) => console.log(new Date().toISOString(), ...a);

const MAGIC = 'MCRELAY';

function requestRoom() {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({ name: ROOM_NAME, maxPlayers: 5 });
    const req = http.request({
      host: SERVER,
      port: HTTP_PORT,
      path: '/api/host',
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
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

function connectLocal() {
  return new Promise((resolve, reject) => {
    const s = net.connect(LOCAL_PORT, LOCAL_HOST);
    s.setNoDelay(true);
    s.once('connect', () => resolve(s));
    s.once('error', reject);
  });
}

function openDataChannel(info, id) {
  const s = net.connect(info.port, SERVER);
  s.setNoDelay(true);

  s.once('connect', async () => {
    s.write(MAGIC + JSON.stringify({ type: 'data', room: info.room, token: info.token, id }) + '\n');
    try {
      const local = await connectLocal();
      local.setNoDelay(true);

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

let heartbeat = null;

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
      log('控制通道断开，5 秒后用同一个房间重连...');
      setTimeout(() => reconnect(info).catch((e) => {
        log('重连失败，重新申请房间：' + e.message);
        main().catch((err) => log('启动失败：' + err.message));
      }), 5000);
    });

    heartbeat = setInterval(() => {
      if (!ctrl.destroyed) ctrl.write(JSON.stringify({ type: 'ping' }) + '\n');
    }, 10000);
    heartbeat.unref();
  });
}

main().catch((e) => {
  log('启动失败：', e.message);
  process.exit(1);
});
