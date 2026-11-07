#!/usr/bin/env node
/** MC 联机中继（VPN 专用） */
'use strict';

const net = require('net');
const http = require('http');
const crypto = require('crypto');
const {
  handleVpnConnection,
  looksLikeVpnHandshake,
  issueJoinToken,
  MAX_VPN_PEERS,
} = require('./vpn-room');

// ============ 配置 ============
const HTTP_PORT = Number(process.env.HTTP_PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const API_KEY = process.env.API_KEY || ''; // 可选：API 密钥，用于保护写接口

/** 房主握手魔数 */
const MAGIC = 'MCRELAY';

/** 同时允许存在的房间数 */
const MAX_ROOMS = Number(process.env.MAX_ROOMS || 100);

/** 建房间后多久内必须建立控制通道 */
const HANDSHAKE_TIMEOUT_MS = 30_000;
/** 心跳间隔与容忍倍数 */
// 5 秒：房主每 5 秒 ping 一次，超过 15 秒没收到才判定断开。
// 这个间隔同时也是「与 NAT 保持活跃」的节奏，太大会被部分路由器提前回收表项。
const HEARTBEAT_MS = 5_000;
const HEARTBEAT_MISS = 3;
/** 建房间的速率限制：每个 IP 每分钟最多几次 */
const HOST_RATE_LIMIT = 10;
const HOST_RATE_WINDOW_MS = 60_000;

/** CORS 允许的来源。 */
const CORS_ORIGINS = new Set(
  (process.env.CORS_ORIGINS || '')
    .split(',').map((v) => v.trim()).filter(Boolean)
);
const API_KEY_REQUIRED = API_KEY !== '';

const log = (...a) => console.log(new Date().toISOString(), ...a);

// ============ 房间状态 ============

/** room = { */
const rooms = new Map();
const hostRate = new Map();     // ip -> { count, windowStart }

// ============ 工具 ============

function corsOrigin(req) {
  const origin = req && req.headers ? req.headers.origin || '' : '';
  return origin && CORS_ORIGINS.has(origin) ? origin : null;
}

function json(req, res, code, obj) {
  const s = JSON.stringify(obj);
  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(s),
    'Vary': 'Origin',
  };
  const origin = corsOrigin(req);
  if (origin) headers['Access-Control-Allow-Origin'] = origin;
  res.writeHead(code, headers);
  res.end(s);
}

/** 控制通道排队上限：房主不读时不无限占内存 */
const MAX_CONTROL_BUFFER = 256 * 1024;

function safeWrite(sock, data) {
  if (sock && !sock.destroyed && sock.writable) {
    try {
      // 房主长时间不读（卡死/断网未察觉）时，写缓冲会无限增长。
      // 超过上限说明这条通道已经废了，直接断开让房主重连。
      if (sock.writableLength > MAX_CONTROL_BUFFER) {
        log(`控制通道写缓冲过大（${sock.writableLength}），断开`);
        sock.destroy();
        return false;
      }
      sock.write(data);
      return true;
    } catch {
      return false;
    }
  }
  return false;
}

// ============ 房间生命周期 ============

/**
 * 创建房间（VPN 模式：不分配端口、不监听玩家端口）
 */
function createRoom(name) {
  const roomId = crypto.randomBytes(3).toString('hex');
  const token = crypto.randomBytes(16).toString('hex');
  const room = {
    roomId,
    token,
    name: String(name || '未命名房间').replace(/[\u0000-\u001f\u007f<>]/g, '').slice(0, 32)
          || '未命名房间',
    control: null,
    vpn: null,
    createdAt: Date.now(),
    lastSeen: Date.now(),
    missedPings: 0,
  };
  rooms.set(roomId, room);
  log(`创建 VPN 房间 ${roomId}`);
  return room;
}

/** 回收房间：清超时定时器、断控制通道、关 VPN 连接 */
function closeRoom(roomId, reason) {
  const room = rooms.get(roomId);
  if (!room) return;
  log(`回收房间 ${roomId} (${reason})`);
  rooms.delete(roomId);
  if (room.handshakeTimer) {
    clearTimeout(room.handshakeTimer);
    room.handshakeTimer = null;
  }
  try { room.control && room.control.destroy(); } catch { /* 忽略 */ }
  if (room.vpn) {
    room.vpn.close('房间回收', null);
    room.vpn = null;
  }
}

// ============ 控制通道 ============

/** 房主的控制通道（魔数 + hello）。 */
function attachControl(sock, msg) {
  const room = rooms.get(msg.room);
  if (!room || room.token !== msg.token) {
    sock.destroy();
    return;
  }

  const old = room.control;
  room.control = sock;
  room.lastSeen = Date.now();
  room.missedPings = 0;

  if (old && old !== sock) {
    old.__isControl = false;
    old.removeAllListeners('close');
    try { old.destroy(); } catch { /* 忽略 */ }
  }

  sock.__isControl = true;
  sock.__room = room;

  sock.on('close', () => {
    if (sock.__room && sock.__room.control === sock) {
      closeRoom(sock.__room.roomId, '控制通道断开');
    }
  });

  // 心跳：房主定期发 ping，超时回收。同一通道也承载 VPN 信令（vpn-token）
  let hb = '';
  sock.on('data', (chunk) => {
    room.lastSeen = Date.now();
    room.missedPings = 0;
    hb += chunk.toString('utf8');
    let i;
    while ((i = hb.indexOf('\n')) >= 0) {
      const line = hb.slice(0, i);
      hb = hb.slice(i + 1);
      if (!line.trim()) continue;
      let m = null;
      try { m = JSON.parse(line); } catch { /* 忽略 */ }
      const type = m && typeof m.type === 'string' ? m.type : '';
      if (type === 'ping') {
        safeWrite(sock, JSON.stringify({ type: 'pong' }) + '\n');
      } else if (type === 'vpn-token') {
        const id = Number(m.id);
        if (Number.isInteger(id)) issueJoinToken(room, id, log);
      }
    }
    if (hb.length > 64 * 1024) hb = '';
  });

  log(`房间 ${room.roomId} 控制通道就绪`);
  safeWrite(sock, JSON.stringify({ type: 'hello-ok' }) + '\n');
}

function maybeHandleControl(sock, headBuf) {
  const nl = headBuf.indexOf(0x0a);
  if (nl <= 0 || nl > 1024) return false;
  const line = headBuf.slice(0, nl).toString('utf8').trim();
  if (!line.startsWith(MAGIC)) return false;
  const body = line.slice(MAGIC.length);
  if (!body.includes('"hello"')) return false;
  let msg;
  try {
    msg = JSON.parse(body);
  } catch {
    return false;
  }
  if (!msg || msg.type !== 'hello') return false;
  attachControl(sock, msg);
  return true;
}

/** HTTP 端口上的 TCP 嗅探：hello 交控制通道，其余交给 HTTP 解析器 */
const controlServer = net.createServer((sock) => {
  sock.setNoDelay(true);
  sock.pause();
  const timer = setTimeout(() => { try { sock.destroy(); } catch { /* 忽略 */ } }, 10_000);
  timer.unref();
  let head = Buffer.alloc(0);
  const onData = (chunk) => {
    head = Buffer.concat([head, chunk]);
    if (head.length < 4) return;
    clearTimeout(timer);
    sock.removeListener('data', onData);
    if (maybeHandleControl(sock, head)) {
      // 已交给控制通道
    } else {
      sock.setTimeout(30_000, () => { try { sock.destroy(); } catch { /* 忽略 */ } });
      sock.unshift(head);
      httpServer.emit('connection', sock);
    }
  };
  sock.on('data', onData);
  sock.on('error', () => clearTimeout(timer));
  sock.on('close', () => clearTimeout(timer));
  sock.resume();
});

controlServer.on('error', (e) => {
  log('HTTP 监听失败: ' + e.message);
  process.exit(1);
});

// ============ HTTP API ============

function rateLimited(ip) {
  const now = Date.now();
  const rec = hostRate.get(ip);
  if (!rec || now - rec.start > HOST_RATE_WINDOW_MS) {
    hostRate.set(ip, { count: 1, start: now });
    return false;
  }
  rec.count++;
  return rec.count > HOST_RATE_LIMIT;
}

function handleHost(req, res, body) {
  const ip = req.socket.remoteAddress || 'unknown';
  if (rateLimited(ip)) {
    return json(req, res, 429, { error: '请求过于频繁，请稍后再试' });
  }
  if (rooms.size >= MAX_ROOMS) {
    return json(req, res, 503, { error: '房间数量已达上限，请稍后再试' });
  }

  // VPN 模式：不分配端口。房主随后用 vpn-host 长连接把房间激活。
  const room = createRoom(String((body && body.name) || '未命名房间'));
  room.handshakeTimer = setTimeout(() => {
    const r = rooms.get(room.roomId);
    if (r && !r.control) closeRoom(room.roomId, '握手超时');
  }, HANDSHAKE_TIMEOUT_MS);
  room.handshakeTimer.unref();

  json(req, res, 200, { room: room.roomId, token: room.token });
}

function handleList(req, res) {
  const list = [];
  for (const r of rooms.values()) {
    if (!r.control) continue;
    list.push({
      room: r.roomId,
      name: r.name,
      players: r.vpn ? r.vpn.peers.size : 0,
      maxPlayers: MAX_VPN_PEERS,
      uptime: Math.floor((Date.now() - r.createdAt) / 1000),
    });
  }
  json(req, res, 200, { rooms: list });
}

const httpServer = http.createServer((req, res) => {
  const path = (req.url || '/').split('?')[0];

  if (req.method === 'OPTIONS') {
    // 只回显白名单里的来源；不在名单里的请求拿到的是「没有 CORS 头」，
    // 浏览器会自行拦截，服务端不做任何跨域授权。
    const headers = {
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, X-Api-Key',
      'Vary': 'Origin',
    };
    const origin = corsOrigin(req);
    if (origin) headers['Access-Control-Allow-Origin'] = origin;
    res.writeHead(204, headers);
    return res.end();
  }

  if (req.method === 'POST' && path === '/api/host') {
    // 先鉴权再读请求体：未授权的请求不该被允许往里灌数据
    if (API_KEY_REQUIRED && !apiKeyOk(req)) {
      res.writeHead(401, { 'Content-Type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify({ error: '无效的 API 密钥' }));
    }
    let body = '';
    let tooBig = false;
    req.on('data', (c) => {
      if (tooBig) return;
      body += c;
      if (body.length > 4096) { tooBig = true; req.destroy(); }
    });
    req.on('end', () => {
      if (tooBig) return;
      let obj = {};
      try { obj = body ? JSON.parse(body) : {}; } catch { /* 空对象 */ }
      if (!obj || typeof obj !== 'object' || Array.isArray(obj)) obj = {};
      handleHost(req, res, obj);
    });
    return;
  }

  if (req.method === 'GET' && path === '/api/rooms') {
    if (API_KEY_REQUIRED && !apiKeyOk(req)) {
      return json(req, res, 401, { error: '无效的 API 密钥' });
    }
    return handleList(req, res);
  }

  if (path === '/health') {
    return json(req, res, 200, {
      ok: true,
      rooms: rooms.size,
      players: [...rooms.values()].reduce((n, r) => n + (r.vpn ? r.vpn.peers.size : 0), 0),
      uptime: Math.floor(process.uptime()),
      rss: Math.round(process.memoryUsage().rss / 1048576),
    });
  }

  json(req, res, 404, { error: 'not found' });
});

// ============ VPN 帧端口 ============
//
// 房主/玩家的 VPN 长连接走 **HTTP_PORT + 1**（可用 VPN_PORT 覆盖）。
// 与 HTTP 分开端口，HTTP 请求永远不会被误判成 VPN 握手。

const VPN_PORT = Number(process.env.VPN_PORT || (HTTP_PORT + 1));

/**
 * VPN 连接识别：第一行必须是「魔数 + 合法 JSON」，
 * 且 type 是 vpn-host / vpn-join / vpn-claim。
 */
function maybeHandleVpn(sock, headBuf) {

  const nl = headBuf.indexOf(0x0a);
  if (nl <= 0 || nl > 1024) return false;
  const line = headBuf.slice(0, nl).toString('utf8').trim();
  if (!looksLikeVpnHandshake(line) && !line.startsWith(MAGIC + '{"type":"vpn-claim"')) return false;
  const body = line.startsWith(MAGIC) ? line.slice(MAGIC.length) : line.replace(/^MCDATA/, '');
  handleVpnConnection(sock, body, rooms, log, headBuf.slice(nl + 1));
  return true;
}

const vpnServer = net.createServer((sock) => {
  sock.setNoDelay(true);
  sock.pause();

  const timer = setTimeout(() => { try { sock.destroy(); } catch { /* 忽略 */ } }, 10_000);
  timer.unref();

  let head = Buffer.alloc(0);
  const onData = (chunk) => {
    head = Buffer.concat([head, chunk]);
    if (head.length < 4) return;
    clearTimeout(timer);
    sock.removeListener('data', onData);

    if (!maybeHandleVpn(sock, head)) {
      try { sock.destroy(); } catch { /* 忽略 */ }
    }
  };

  sock.on('data', onData);
  sock.on('error', () => clearTimeout(timer));
  sock.on('close', () => clearTimeout(timer));
  sock.resume();
});

vpnServer.on('error', (e) => {
  log('VPN 端口监听失败: ' + e.message);
  process.exit(1);
});

vpnServer.listen(VPN_PORT, HOST, () => {
  log(`VPN 帧端口监听 ${HOST}:${VPN_PORT}`);
});

/** 校验 X-Api-Key。 */
function apiKeyOk(req) {
  if (!API_KEY_REQUIRED) return true;
  const key = String(req.headers['x-api-key'] || '');
  const a = Buffer.from(key, 'utf8');
  const b = Buffer.from(API_KEY, 'utf8');
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function requireApiKey(req, res, next) {
  if (apiKeyOk(req)) return next();
  return res.status(401).json({ error: '无效的 API 密钥' });
}

// HTTP 层兜底超时：慢读/慢写连接最多 60 秒
httpServer.headersTimeout = 15_000;
httpServer.requestTimeout = 30_000;
httpServer.timeout = 60_000;

httpServer.on('error', (e) => {
  log('HTTP 监听失败: ' + e.message);
  process.exit(1);
});

controlServer.listen(HTTP_PORT, HOST, () => {
  log(`VPN 中继 HTTP 监听 ${HOST}:${HTTP_PORT}  最多 ${MAX_ROOMS} 个房间`);
  if (!API_KEY_REQUIRED) {
    log('[警告] 未配置 API_KEY：/api/host 与 /api/rooms 对任何来源开放，' +
        '任意网页都可通过访客浏览器匿名建房/枚举房间。请设置 API_KEY 并让客户端带上 X-Api-Key。');
  }
  log(`跨域白名单: ${CORS_ORIGINS.size ? [...CORS_ORIGINS].join(',') : '（关闭，原生客户端不需要 CORS）'}`);
});

// 心跳超时 + 速率限制记录清理
setInterval(() => {
  const now = Date.now();
  for (const [id, r] of rooms) {
    if (r.control && now - r.lastSeen > HEARTBEAT_MS * HEARTBEAT_MISS) {
      closeRoom(id, '心跳超时');
    }
  }
  for (const [ip, rec] of hostRate) {
    if (now - rec.start > HOST_RATE_WINDOW_MS * 2) hostRate.delete(ip);
  }
}, HEARTBEAT_MS).unref();

// ============ 退出 ============

let shuttingDown = false;
function shutdown(sig) {
  if (shuttingDown) return;
  shuttingDown = true;
  log(`收到 ${sig}，关闭所有房间`);
  for (const id of [...rooms.keys()]) closeRoom(id, 'server-shutdown');
  vpnServer.close(() => controlServer.close(() => httpServer.close(() => process.exit(0))));
  setTimeout(() => process.exit(1), 5000).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

// 兜底：未捕获异常不要让整个中转挂掉
process.on('uncaughtException', (e) => {
  log('未捕获异常:', e && e.stack ? e.stack.split('\n')[0] : e);
});
process.on('unhandledRejection', (e) => {
  log('未处理的 Promise 拒绝:', e);
});
