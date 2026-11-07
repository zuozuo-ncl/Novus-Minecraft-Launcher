#!/usr/bin/env node
/**
 * MC 联机 —— 房主端命令行工具（调试/应急用）
 * 注意：这里不跑虚拟网卡，只验证中继侧协议；真实联机请用启动器的「虚拟局域网」。
 * 用法：
 */
'use strict';

const net = require('net');
const http = require('http');

const argv = process.argv.slice(2);
const arg = (k, d) => {
  const i = argv.indexOf('--' + k);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : d;
};

const SERVER = arg('server', process.env.MC_RELAY_HOST || '');
const HTTP_PORT = Number(arg('http-port', 8080));
const VPN_PORT = Number(arg('vpn-port', HTTP_PORT + 1));
const ROOM_NAME = arg('name', '我的联机房间');
const API_KEY = arg('api-key', process.env.MC_RELAY_API_KEY || '');
const MAGIC = 'MCRELAY';

const log = (...a) => console.log(new Date().toISOString(), ...a);

if (!SERVER) {
  console.error('用法：node relay-host.js --server <中继地址> [--http-port 8080] [--api-key ...]');
  process.exit(2);
}

function requestRoom() {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({ name: ROOM_NAME });
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

/** 读一行 JSON 消息的轻量客户端 */
function lineClient(port, handshake) {
  return new Promise((resolve) => {
    const sock = net.connect(port, SERVER);
    sock.setNoDelay(true);
    const msgs = [];
    let buf = '';
    sock.on('data', (c) => {
      buf += c.toString('utf8');
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i);
        buf = buf.slice(i + 1);
        try { msgs.push(JSON.parse(line)); } catch { /* 非 JSON */ }
      }
    });
    sock.on('close', () => log('连接已断开', handshake.type));
    sock.on('error', (e) => log('连接错误:', e.message));
    sock.on('connect', () => {
      sock.write(MAGIC + JSON.stringify(handshake) + '\n');
      resolve({ sock, msgs });
    });
  });
}

(async () => {
  log(`向 ${SERVER}:${HTTP_PORT} 申请房间...`);
  const room = await requestRoom();
  log(`房间已创建：${room.room}`);
  if (room.port !== undefined) {
    log('注意：服务端仍返回了 port 字段 —— 说明中继还是旧版（端口转发），请升级');
  }

  const ctrl = await lineClient(HTTP_PORT, { type: 'hello', room: room.room, token: room.token });
  log('控制通道已建立');
  const vhost = await lineClient(VPN_PORT, { type: 'vpn-host', room: room.room, token: room.token });
  log(`VPN 通道已建立（${VPN_PORT}）`);

  // 申请一个玩家邀请（真实场景由启动器在房主 MC 有人接入时自动发起）
  let nextId = 1;
  setInterval(() => {
    ctrl.sock.write(JSON.stringify({ type: 'vpn-token', id: nextId }) + '\n');
    vhost.sock.write(JSON.stringify({ type: 'ping' }) + '\n');
    const t = ctrl.msgs.filter((m) => m.type === 'vpn-token' && m.id === nextId).pop();
    if (t) {
      log(`邀请 #${nextId}：虚拟地址 ${t.ip}，口令 ${t.code}`);
      log(`  玩家在启动器里粘贴：MCRELAY|<中继分配的地址>:<端口>#${room.room}#${t.code}`);
      nextId++;
    }
  }, 5000);

  ctrl.sock.write(JSON.stringify({ type: 'vpn-token', id: nextId }) + '\n');
  log('按 Ctrl+C 退出');
})().catch((e) => {
  log('启动失败：', e.message);
  process.exit(1);
});