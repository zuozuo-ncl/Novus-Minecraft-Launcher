#!/usr/bin/env node

'use strict';

const net = require('net');
const http = require('http');
const crypto = require('crypto');

const HTTP_PORT = Number(process.env.HTTP_PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const [PORT_MIN, PORT_MAX] = (process.env.RANGE || '40000-40999').split('-').map(Number);
const API_KEY = process.env.API_KEY || ''; 

const MAGIC = 'MCRELAY';

const MAX_PLAYERS_PER_ROOM = Number(process.env.MAX_PLAYERS || 20);

const MAX_ROOMS = Number(process.env.MAX_ROOMS || 100);

const HANDSHAKE_TIMEOUT_MS = 30_000;

const SILENT_TIMEOUT_MS = 10_000;

const PENDING_TIMEOUT_MS = 30_000;

const CONFIRM_HINT_MS = 8_000;

const HEARTBEAT_MS = 15_000;
const HEARTBEAT_MISS = 3;

const HOST_RATE_LIMIT = 10;
const HOST_RATE_WINDOW_MS = 60_000;

const log = (...a) => console.log(new Date().toISOString(), ...a);

const rooms = new Map();
const portOwner = new Map();
const hostRate = new Map();     

function json(res, code, obj) {
  const s = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(s),
    'Access-Control-Allow-Origin': '*',
  });
  res.end(s);
}

const MAX_CONTROL_BUFFER = 256 * 1024;

function sendLoginDisconnect(sock, reason) {
  if (!sock || sock.destroyed || !sock.writable) return;
  try {
    const text = Buffer.from(JSON.stringify({ text: '§c' + reason }) + '\n', 'utf8');

    const body = Buffer.concat([Buffer.from([0x00]), varIntBuf(text.length), text]);
    sock.write(Buffer.concat([varIntBuf(body.length), body]));
  } catch {  }
}

function varIntBuf(v) {
  const out = [];
  while (v >= 0x80) { out.push((v & 0x7f) | 0x80); v >>>= 7; }
  out.push(v);
  return Buffer.from(out);
}

function safeWrite(sock, data) {
  if (sock && !sock.destroyed && sock.writable) {
    try {

      
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

function closeGracefully(sock) {
  if (!sock || sock.destroyed) return;
  try {
    sock.end();
  } catch {  }
  const t = setTimeout(() => {
    try { sock.destroy(); } catch {  }
  }, 2000);
  t.unref();
  sock.once('close', () => clearTimeout(t));
}

function allocPort() {

  const usedPorts = new Set([...portOwner.keys()]);
  for (let p = PORT_MIN; p <= PORT_MAX; p++) {
    if (!usedPorts.has(p)) return p;
  }
  return null;
}

function createRoom(name, port) {
  const roomId = crypto.randomBytes(3).toString('hex');
  const token = crypto.randomBytes(16).toString('hex');
  const room = {
    roomId,
    token,
    port,
    name: String(name || '未命名房间').slice(0, 32),
    maxPlayers: 8,
    control: null,
    pending: new Map(),
    dataSockets: new Map(),
    nextId: 1,
    createdAt: Date.now(),
    lastSeen: Date.now(),
    missedPings: 0,
  };
  room.server = listenRoom(room);
  rooms.set(roomId, room);
  portOwner.set(port, roomId);
  log(`分配房间 ${roomId} 端口 ${port}`);
  return room;
}

function closeRoom(roomId, reason) {
  const room = rooms.get(roomId);
  if (!room) return;
  log(`回收房间 ${roomId} (${reason})`);
  rooms.delete(roomId);
  portOwner.delete(room.port);

  if (room.server) {
    try { room.server.close(); } catch {  }
  }

  try { room.control && room.control.destroy(); } catch {  }

  for (const [, p] of room.pending) {
    clearTimeout(p.timer);
    closeGracefully(p.sock);
  }
  room.pending.clear();

  for (const [, s] of room.dataSockets) {
    closeGracefully(s);
  }
  room.dataSockets.clear();
}

function listenRoom(room) {
  const server = net.createServer((sock) => {
    sock.setNoDelay(true);

    

    const silentTimer = setTimeout(() => {
      log(`房间 ${room.roomId} 静默连接超时 from ${sock.remoteAddress}`);
      sock.destroy();
    }, SILENT_TIMEOUT_MS);

    sock.pause();

    const onFirst = (chunk) => {
      clearTimeout(silentTimer);
      sock.removeListener('data', onFirst);

      const cmpLen = Math.min(chunk.length, MAGIC.length);
      const isMagA = chunk.slice(0, cmpLen).toString('latin1') === MAGIC.slice(0, cmpLen);
      if (cmpLen < MAGIC.length && isMagA) {

        

        const partialTimer = setTimeout(() => {
          log(`房间 ${room.roomId} 半包握手超时 ${sock.remoteAddress}`);
          sock.destroy();
        }, SILENT_TIMEOUT_MS);
        partialTimer.unref();
        sock.once('data', (more) => {
          clearTimeout(partialTimer);
          onFirst(Buffer.concat([chunk, more]));
        });
        sock.once('close', () => clearTimeout(partialTimer));
        return;
      }
      if (isMagA) {

        
        onTunnelConnection(sock, chunk);
        return;
      }

      if (room.dataSockets.size + room.pending.size >= MAX_PLAYERS_PER_ROOM) {
        log(`房间 ${room.roomId} 已满，拒绝新玩家`);
        sock.destroy();
        return;
      }

      const id = room.nextId++;
      const entry = { sock, timer: null };

      entry.timer = setTimeout(() => {
        if (room.pending.get(id) === entry) {
          log(`房间 ${room.roomId} 玩家 #${id} 等待房主确认超时（${PENDING_TIMEOUT_MS / 1000} 秒）`);
          room.pending.delete(id);

          sendLoginDisconnect(sock, '房主没有在 30 秒内确认，请重新加入');
          setTimeout(() => closeGracefully(sock), 300);
        }
      }, PENDING_TIMEOUT_MS);
      entry.timer.unref();

      
      sock.unshift(chunk);
      sock.pause();
      room.pending.set(id, entry);
      log(`房间 ${room.roomId} 新玩家接入 id=${id} from ${sock.remoteAddress}`);

      

      if (room.control) {
        entry.timer.refresh();
      }

      sock.on('error', () => {});
      sock.on('close', () => {
        clearTimeout(entry.timer);
        if (room.pending.get(id) === entry) {
          room.pending.delete(id);
        }
      });

      const joinMsg = { type: 'join', id, from: sock.remoteAddress || '' };
      if (!safeWrite(room.control, JSON.stringify(joinMsg) + '\n')) {
        log(`房间 ${room.roomId} 无控制通道，无法通知房主`);
      }
    };

    sock.on('data', onFirst);
    sock.on('error', () => clearTimeout(silentTimer));
    sock.resume();      
  });

  server.on('error', (e) => {
    log(`房间 ${room.roomId} 监听失败: ${e.message}`);
    closeRoom(room.roomId, 'listen-error');
  });
  server.listen(room.port, HOST, () => {
    log(`房间 ${room.roomId} 监听 ${HOST}:${room.port}`);
  });
  return server;
}

function onTunnelConnection(sock, firstChunk) {
  let buf = '';

  
  const handshakeTimer = setTimeout(() => {
    log(`隧道握手超时 ${sock.remoteAddress}`);
    sock.destroy();
  }, HANDSHAKE_TIMEOUT_MS);
  handshakeTimer.unref();

  const onData = (chunk) => {
    buf += chunk.toString('utf8');
    const idx = buf.indexOf('\n');
    if (idx < 0) {
      if (buf.length > 8192) sock.destroy();
      return;
    }
    clearTimeout(handshakeTimer);
    sock.removeListener('data', onData);

    const line = buf.slice(0, idx);
    const rest = buf.slice(idx + 1);

    const body = line.startsWith(MAGIC) ? line.slice(MAGIC.length) : line;

    let msg;
    try {
      msg = JSON.parse(body);
    } catch {
      sock.destroy();
      return;
    }

    if (rest.length) sock.unshift(Buffer.from(rest, 'utf8'));

    if (msg.type === 'hello') {
      attachControl(sock, msg);
    } else if (msg.type === 'data') {
      attachData(sock, msg);
    } else {
      sock.destroy();
    }
  };

  sock.on('data', onData);
  sock.on('error', () => clearTimeout(handshakeTimer));
  if (firstChunk && firstChunk.length) {
    onData(firstChunk);
  }
  sock.resume();
}

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
    try { old.destroy(); } catch {  }
  }

  sock.__isControl = true;
  sock.__room = room;

  sock.on('close', () => {
    if (sock.__room && sock.__room.control === sock) {
      closeRoom(sock.__room.roomId, '控制通道断开');
    }
  });

  let hb = '';
  sock.on('data', (chunk) => {
    room.lastSeen = Date.now();
    room.missedPings = 0;
    hb += chunk.toString('utf8');

    let i;
    while ((i = hb.indexOf('\n')) >= 0) {
      const line = hb.slice(0, i);
      hb = hb.slice(i + 1);
      if (line.includes('"ping"')) {
        safeWrite(sock, JSON.stringify({ type: 'pong' }) + '\n');
      }
    }
    if (hb.length > 4096) hb = '';
  });

  log(`房间 ${room.roomId} 控制通道就绪`);
  safeWrite(sock, JSON.stringify({ type: 'hello-ok', port: room.port }) + '\n');
}

function attachData(sock, msg) {
  const room = rooms.get(msg.room);

  if (!room || room.token !== msg.token) {
    sock.destroy();
    return;
  }

  const entry = room.pending.get(msg.id);
  if (!entry) {
    sock.destroy();
    return;
  }
  room.pending.delete(msg.id);
  clearTimeout(entry.timer);
  const client = entry.sock;

  sock.setNoDelay(true);
  room.dataSockets.set(msg.id, sock);

  client.pipe(sock);
  sock.pipe(client);
  client.resume();

  let closed = false;
  const cleanup = () => {
    if (closed) return;
    closed = true;
    room.dataSockets.delete(msg.id);
    closeGracefully(client);
    closeGracefully(sock);
  };
  client.on('close', cleanup);
  sock.on('close', cleanup);
  client.on('error', cleanup);
  sock.on('error', cleanup);

  log(`房间 ${room.roomId} 数据通道建立 id=${msg.id}`);
}

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
    return json(res, 429, { error: '请求过于频繁，请稍后再试' });
  }
  if (rooms.size >= MAX_ROOMS) {
    return json(res, 503, { error: '房间数量已达上限，请稍后再试' });
  }
  const port = allocPort();
  if (port === null) {
    return json(res, 503, { error: '端口池已满，请稍后再试' });
  }

  const room = createRoom(String(body.name || '未命名房间'), port);
  setTimeout(() => {
    const r = rooms.get(room.roomId);
    if (r && !r.control) closeRoom(room.roomId, '握手超时');
  }, HANDSHAKE_TIMEOUT_MS).unref();

  json(res, 200, { room: room.roomId, token: room.token, port });
}

function handleList(res) {
  const list = [];
  for (const r of rooms.values()) {
    if (!r.control) continue;
    list.push({
      room: r.roomId,
      name: r.name,
      port: r.port,
      players: r.dataSockets.size,
      maxPlayers: r.maxPlayers,
      uptime: Math.floor((Date.now() - r.createdAt) / 1000),
    });
  }
  json(res, 200, { rooms: list });
}

const httpServer = http.createServer((req, res) => {
  const path = (req.url || '/').split('?')[0];

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    });
    return res.end();
  }

  if (req.method === 'POST' && path === '/api/host') {
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
      try { obj = body ? JSON.parse(body) : {}; } catch {  }
      if (!API_KEY) {
        handleHost(req, res, obj);
      } else {
        requireApiKey(req, res, () => handleHost(req, res, obj));
      }
    });
    return;
  }

  if (req.method === 'GET' && path === '/api/rooms') {

    if (API_KEY) {
      return requireApiKey(req, res, handleList);
    }
    return handleList(res);
  }

  if (path === '/health') {
    return json(res, 200, {
      ok: true,
      rooms: rooms.size,
      players: [...rooms.values()].reduce((n, r) => n + r.dataSockets.size, 0),
      ports: `${PORT_MIN}-${PORT_MAX}`,
      uptime: Math.floor(process.uptime()),
      rss: Math.round(process.memoryUsage().rss / 1048576),
    });
  }

  json(res, 404, { error: 'not found' });
});

const BIN_MAGIC_REQ = 'MCRQ';
const BIN_MAGIC_OK = 'MCRO';
const BIN_MAGIC_ERR = 'MCRE';

const BIN_PORT = Number(process.env.BIN_PORT || (HTTP_PORT + 1));

function handleBinHandshake(sock, prefetched) {
  let buf = prefetched;

  const tryParse = () => {
    if (buf.length < 6) return false;
    const nameLen = buf.readUInt16BE(4);
    if (buf.length < 6 + nameLen) return false;

    const name = buf.slice(6, 6 + nameLen).toString('utf8') || '未命名房间';
    const ip = sock.remoteAddress || 'unknown';

    if (rateLimited(ip)) { replyErr(sock, '请求过于频繁，请稍后再试'); return true; }
    if (rooms.size >= MAX_ROOMS) { replyErr(sock, '房间数量已达上限'); return true; }
    const port = allocPort();
    if (port === null) { replyErr(sock, '端口池已满'); return true; }

    const room = createRoom(name, port);
    log(`[BIN] 分配房间 ${room.roomId} 端口 ${port} 来自 ${ip}`);

    const out = Buffer.alloc(4 + 8 + 32 + 4);
    out.write(BIN_MAGIC_OK, 0, 'latin1');
    out.write(room.roomId.padEnd(8, ' '), 4, 'latin1');
    out.write(room.token.padEnd(32, ' '), 12, 'latin1');
    out.writeUInt32BE(port, 44);
    safeWrite(sock, out);

    setTimeout(() => { try { sock.destroy(); } catch {  } }, 500).unref();
    return true;
  };

  if (tryParse()) return;

  const onMore = (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    if (tryParse()) {
      sock.removeListener('data', onMore);
    } else if (buf.length > 1024) {
      sock.destroy();
    }
  };
  sock.on('data', onMore);
  sock.resume();
}

function replyErr(sock, msg) {
  const b = Buffer.from(msg, 'utf8');
  const out = Buffer.alloc(6 + b.length);
  out.write(BIN_MAGIC_ERR, 0, 'latin1');
  out.writeUInt16BE(b.length, 4);
  b.copy(out, 6);
  safeWrite(sock, out);
  setTimeout(() => { try { sock.destroy(); } catch {  } }, 300).unref();
}

const HTTP_IDLE_TIMEOUT_MS = 30_000;

const frontServer = net.createServer((sock) => {
  sock.setNoDelay(true);
  sock.pause();

  const timer = setTimeout(() => { try { sock.destroy(); } catch {  } }, 10_000);
  timer.unref();

  let head = Buffer.alloc(0);

  const onData = (chunk) => {
    head = Buffer.concat([head, chunk]);
    if (head.length < 4) return;
    clearTimeout(timer);
    sock.removeListener('data', onData);

    if (head.slice(0, 4).toString('latin1') === BIN_MAGIC_REQ) {
      handleBinHandshake(sock, head);
    } else {

      
      sock.setTimeout(HTTP_IDLE_TIMEOUT_MS, () => {
        try { sock.destroy(); } catch {  }
      });
      sock.unshift(head);
      httpServer.emit('connection', sock);
    }
  };

  sock.on('data', onData);
  sock.on('error', () => clearTimeout(timer));
  sock.on('close', () => clearTimeout(timer));
  sock.resume();
});

frontServer.on('error', (e) => {
  log('监听失败: ' + e.message);
  process.exit(1);
});

frontServer.listen(HTTP_PORT, HOST, () => {
  log(`中转监听 ${HOST}:${HTTP_PORT}  （二进制申请 + HTTP API）`);
  log(`隧道端口池 ${PORT_MIN}-${PORT_MAX}  最多 ${MAX_ROOMS} 个房间`);
});

function requireApiKey(req, res, next) {
  const key = req.headers['x-api-key'] || '';
  if (!API_KEY) {

    return next();
  }
  if (key !== API_KEY) {
    return res.status(401).json({ error: '无效的 API 密钥' });
  }
  next();
}

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

let shuttingDown = false;
function shutdown(sig) {
  if (shuttingDown) return;
  shuttingDown = true;
  log(`收到 ${sig}，关闭所有房间`);
  for (const id of [...rooms.keys()]) closeRoom(id, 'server-shutdown');
  httpServer.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 5000).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

process.on('uncaughtException', (e) => {
  log('未捕获异常:', e && e.stack ? e.stack.split('\n')[0] : e);
});
process.on('unhandledRejection', (e) => {
  log('未处理的 Promise 拒绝:', e);
});
