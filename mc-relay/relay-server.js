#!/usr/bin/env node
/**
 * MC 联机中转服务器
 * ===================
 *
 * 解决「没有公网 IP 的玩家想开 Minecraft 服务器」的问题。
 *
 * 原理
 * ----
 * NAT 只拦入站、不拦出站。所以让房主**主动连出**到本服务器，
 * 本服务器把朋友们的入站连接转发给房主：
 *
 *     房主启动器 ──① 申请房间(HTTP)──► 本服务器
 *                ◄─ 返回端口 40001 ──
 *     房主启动器 ──② 建立控制通道(TCP:40001)──► 本服务器
 *     朋友       ──③ 连接 39.106.60.15:40001 ──► 本服务器 ──► 房主 → MC:25565
 *
 * 连接区分
 * --------
 * 同一个端口既有玩家也有房主自己的通道，靠**固定魔数**区分：
 *
 *     房主的每条连接都以 "MCRELAY\n" 开头，新浪客户端不可能发这个。
 *
 * 握手格式（魔数之后是一行 JSON，以 \n 结尾）：
 *
 *     控制通道：MCRELAY\n{"type":"hello","room":"a1b2c3","token":"..."}\n
 *     数据通道：MCRELAY\n{"type":"data","room":"a1b2c3","id":7}\n
 *
 * 控制通道之后是"信令"（每行一条 JSON），数据通道之后是纯字节流。
 *
 * 可靠性设计（都是踩过的坑，别删）
 * --------------------------------
 * - 玩家连接在数据通道建立前**暂停读取**（pause），也就是不让 Node 从内核
 *   缓冲区读走 —— 否则那段时间到达的数据会因为"没有 data 监听器"被丢弃。
 *   MC 客户端连上后会连发 handshake + login start，跨多个 TCP 段。
 * - 静默连接超时：连上却不发任何数据（端口扫描/半开扫描）的连接会被回收。
 * - pending 超时：房主迟迟不建数据通道时，玩家连接不会无限挂着。
 * - 控制通道重连时，只有"当前生效的那条"断开才回收房间。
 * - 正常关闭用 end() 走半关闭，保留对端待发送的数据。
 */
'use strict';

const net = require('net');
const http = require('http');
const crypto = require('crypto');
const { handleVpnConnection } = require('./vpn-room');

// ============ 配置 ============
const HTTP_PORT = Number(process.env.HTTP_PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const [PORT_MIN, PORT_MAX] = (process.env.RANGE || '40000-40999').split('-').map(Number);
const API_KEY = process.env.API_KEY || ''; // 可选：API 密钥，用于保护写接口

/** 房主握手魔数 */
const MAGIC = 'MCRELAY';

/** 房间里最多同时在线玩家（防止单房间撑爆中转） */
const MAX_PLAYERS_PER_ROOM = Number(process.env.MAX_PLAYERS || 20);
/** 同时允许存在的房间数 */
const MAX_ROOMS = Number(process.env.MAX_ROOMS || 100);

/** 建房间后多久内必须建立控制通道 */
const HANDSHAKE_TIMEOUT_MS = 30_000;
/** 连接建立后多久内必须发出第一块数据（防端口扫描堆积） */
const SILENT_TIMEOUT_MS = 10_000;
/** 玩家连上后多久内必须建立数据通道 */
const PENDING_TIMEOUT_MS = 30_000;
/** 房主打招呼（ACK）允许的时间：收到玩家后如果房主一直没回应，也要给玩家一个交代 */
const CONFIRM_HINT_MS = 8_000;
/** 心跳间隔与容忍倍数 */
// 5 秒：房主每 5 秒 ping 一次，超过 15 秒没收到才判定断开。
// 这个间隔同时也是「与 NAT 保持活跃」的节奏，太大会被部分路由器提前回收表项。
const HEARTBEAT_MS = 5_000;
const HEARTBEAT_MISS = 3;
/** 建房间的速率限制：每个 IP 每分钟最多几次 */
const HOST_RATE_LIMIT = 10;
const HOST_RATE_WINDOW_MS = 60_000;
/** 单个 IP 每分钟允许的玩家接入次数（超过后直接断开，不通知房主） */
const JOIN_RATE_LIMIT = Number(process.env.JOIN_RATE_LIMIT || 6);
const JOIN_RATE_WINDOW_MS = 60_000;
/** 手动封禁的玩家 IP，逗号分隔（环境变量 BLOCK_IPS） */
const BLOCK_IPS = new Set(
  (process.env.BLOCK_IPS || '')
    .split(',').map((s) => s.trim()).filter(Boolean)
);

const log = (...a) => console.log(new Date().toISOString(), ...a);

// ============ 房间状态 ============

/**
 * room = {
 *   roomId, token, port,
 *   name, maxPlayers,
 *   control:      Socket|null  当前生效的控制通道
 *   pending:      Map<id, {sock, timer}>  等待数据通道的玩家
 *   dataSockets:  Map<id, Socket>         已建立的数据通道
 *   server:       net.Server   监听玩家端口
 *   nextId:       number
 *   createdAt, lastSeen
 * }
 */
const rooms = new Map();
const portOwner = new Map();
const hostRate = new Map();     // ip -> { count, windowStart }
/**
 * 正在释放、暂不能重新分配的端口。
 *
 * server.close() 是异步的：停止 accept 后还要等现存连接全部关闭才触发
 * 'close' 事件、真正释放端口。allocPort 必须跳过这些端口，否则会把
 * 还没释放的端口分给新房间，listen 报 EADDRINUSE 并连带把新房间关掉。
 */
const releasingPorts = new Set();
const joinRate = new Map();     // 玩家接入：ip -> { count, resetAt }

// ============ 工具 ============

function json(res, code, obj) {
  const s = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(s),
    'Access-Control-Allow-Origin': '*',
  });
  res.end(s);
}

/** 控制通道排队上限：房主不读时不无限占内存 */
const MAX_CONTROL_BUFFER = 256 * 1024;

/**
 * 给玩家发一个 Minecraft「登录断开」包（state=login 下 0x00）。
 *
 * 此时玩家已经发过握手与 login start，处于 login 状态。发完之后客户端会
 * 把这段文本显示出来，比直接断线更容易看懂。
 *
 * 注意：玩家 socket 在等待期间是 pause 的，这里只写不读，不影响 unshift 的数据。
 */
function sendLoginDisconnect(sock, reason) {
  if (!sock || sock.destroyed || !sock.writable) return;
  try {
    const text = Buffer.from(JSON.stringify({ text: '§c' + reason }) + '\n', 'utf8');
    // 包 = varint(长度) + 0x00(Login Disconnect) + varint(文本长度) + 文本
    const body = Buffer.concat([Buffer.from([0x00]), varIntBuf(text.length), text]);
    sock.write(Buffer.concat([varIntBuf(body.length), body]));
  } catch { /* 玩家可能已经断开 */ }
}

function varIntBuf(v) {
  const out = [];
  while (v >= 0x80) { out.push((v & 0x7f) | 0x80); v >>>= 7; }
  out.push(v);
  return Buffer.from(out);
}

/** 只往还活着的 socket 写 */
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

/**
 * 优雅关闭一个 socket
 *
 * 先 end() 让对端收到 FIN，把排队的写缓冲发完；
 * 2 秒内没关掉再强拆（防止对端不响应导致连接泄漏）。
 */
function closeGracefully(sock) {
  if (!sock || sock.destroyed) return;
  try {
    sock.end();
  } catch { /* 忽略 */ }
  const t = setTimeout(() => {
    try { sock.destroy(); } catch { /* 忽略 */ }
  }, 2000);
  t.unref();
  sock.once('close', () => clearTimeout(t));
}

// ============ 房间生命周期 ============

function allocPort() {
  // 使用集合差集更高效：先找出已用端口，再从范围中筛选。
  // 正在释放（句柄尚未关闭）的端口也不能分配，否则 listen 会 EADDRINUSE。
  const usedPorts = new Set([...portOwner.keys(), ...releasingPorts]);
  for (let p = PORT_MIN; p <= PORT_MAX; p++) {
    if (!usedPorts.has(p)) return p;
  }
  return null;
}

/**
 * 创建房间并开始监听玩家端口
 *
 * HTTP 与二进制两种申请方式共用。
 */
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
    pendingJoins: [],
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

  // 监听端口异步释放：先标记 releasing，等 server 'close' 事件后才放回端口池
  releaseRoomServer(room);
  // 控制通道：直接断（不需要优雅，通道没有用户数据）
  try { room.control && room.control.destroy(); } catch { /* 忽略 */ }

  for (const [, p] of room.pending) {
    clearTimeout(p.timer);
    closeGracefully(p.sock);
  }
  room.pending.clear();

  for (const [, s] of room.dataSockets) {
    closeGracefully(s);
  }
  room.dataSockets.clear();

  // VPN 连接也一并关闭
  if (room.vpn) {
    room.vpn.close('房间回收', null);
    room.vpn = null;
  }
}

/**
 * 关闭房间监听端口，等句柄真正释放后再把端口放回池。
 *
 * 之前 closeRoom 直接 server.close() 就删掉端口映射，allocPort 可能在
 * 句柄关闭前把同一端口分给新房间 → EADDRINUSE → listen-error 连新房间
 * 一起被关掉。现在：
 *   1. 端口加入 releasingPorts，allocPort 跳过；
 *   2. closeAllConnections() 主动断开现存连接（否则半开连接会让 close 一直等）；
 *   3. 'close' 事件到达后解除标记；30 秒兜底防止事件丢失导致端口永久占用。
 */
function releaseRoomServer(room) {
  const server = room.server;
  if (!server) return;
  releasingPorts.add(room.port);
  const markReleased = () => releasingPorts.delete(room.port);
  try {
    if (typeof server.closeAllConnections === 'function') {
      server.closeAllConnections();
    }
    server.close(markReleased);
  } catch {
    markReleased();
    return;
  }
  setTimeout(markReleased, 30_000).unref();
}

/** IPv4-mapped IPv6 地址归一化（::ffff:1.2.3.4 → 1.2.3.4） */
function normalizeIp(ip) {
  if (!ip) return '';
  return ip.startsWith('::ffff:') ? ip.slice(7) : ip;
}

/**
 * 玩家接入的 IP 级限流 / 黑名单。
 *
 * 公网扫描器会反复连房间端口并发送握手，每个连接都弹房主确认框，
 * 正常玩家根本没法用。超过阈值的连接直接断开、不通知房主；
 * BLOCK_IPS 中的地址一律拒绝。
 *
 * @returns true 放行；false 直接断开
 */
function playerJoinAllowed(ip) {
  const addr = normalizeIp(ip);
  if (!addr) return true;
  if (BLOCK_IPS.has(addr)) return false;
  const now = Date.now();
  const rec = joinRate.get(addr);
  if (!rec || now >= rec.resetAt) {
    joinRate.set(addr, { count: 1, resetAt: now + JOIN_RATE_WINDOW_MS });
    return true;
  }
  rec.count += 1;
  return rec.count <= JOIN_RATE_LIMIT;
}

// ============ 玩家端口 ============

/**
 * 为一个房间开监听端口
 *
 * 进来的连接有两种：
 *   1. 玩家（MC 客户端）—— 通知房主建立数据通道
 *   2. 房主自己建的控制/数据通道 —— 以魔数开头
 *
 * 判别方式：**暂停读取**，看第一块数据是否以魔数开头。
 * 暂停期间数据留在内核缓冲区，不会丢；判定后再决定交给谁。
 */
function listenRoom(room) {
  const server = net.createServer((sock) => {
    sock.setNoDelay(true);

    // 连上却不发数据（端口扫描）→ 直接回收
    // 注意：silentTimer 在派发后（无论玩家还是隧道）都会 clear；
    // 隧道方向若只收到半截魔数，改由 onTunnelConnection 的握手超时兜底。
    const silentTimer = setTimeout(() => {
      log(`房间 ${room.roomId} 静默连接超时 from ${sock.remoteAddress}`);
      sock.destroy();
    }, SILENT_TIMEOUT_MS);

    // 先暂停：数据留在内核缓冲区，下面判定完再 resume
    sock.pause();

    const onFirst = (chunk) => {
      clearTimeout(silentTimer);
      sock.removeListener('data', onFirst);

      // 魔数匹配（处理半包：可能是 "MCR" 这种前缀）
      const cmpLen = Math.min(chunk.length, MAGIC.length);
      const isMagA = chunk.slice(0, cmpLen).toString('latin1') === MAGIC.slice(0, cmpLen);
      if (cmpLen < MAGIC.length && isMagA) {
        // 只收到魔数的一部分：等剩余部分。
        // 用 once 继续，但必须重新挂上一种兜底超时 —— 否则对端发个 "MCR"
        // 就消失，这个连接会永久占着句柄。
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
        // 房主的隧道连接：把已经读到的数据直接交给握手逻辑，
        // 不要再 unshift（那会触发一次多余的 data 事件，和后续监听打架）
        onTunnelConnection(sock, chunk);
        return;
      }

      // ---- 玩家连接 ----
      // IP 限流 / 黑名单先拦：扫描器反复握手时不再给房主弹确认框
      if (!playerJoinAllowed(sock.remoteAddress)) {
        log(`房间 ${room.roomId} 拒绝高频/黑名单连接 from ${sock.remoteAddress}`);
        sock.destroy();
        return;
      }
      if (room.dataSockets.size + room.pending.size >= MAX_PLAYERS_PER_ROOM) {
        log(`房间 ${room.roomId} 已满，拒绝新玩家`);
        sock.destroy();
        return;
      }

      const id = room.nextId++;
      const entry = { sock, timer: null };
      // 房主迟迟不建数据通道：超时回收，玩家不会无限挂着
      entry.timer = setTimeout(() => {
        if (room.pending.get(id) === entry) {
          log(`房间 ${room.roomId} 玩家 #${id} 等待房主确认超时（${PENDING_TIMEOUT_MS / 1000} 秒）`);
          room.pending.delete(id);
          // 先尝试把原因送到玩家手里（MC 登录断开包），再关连接
          sendLoginDisconnect(sock, '房主没有在 30 秒内确认，请重新加入');
          setTimeout(() => closeGracefully(sock), 300);
        }
      }, PENDING_TIMEOUT_MS);
      entry.timer.unref();

      // 这一块已经被 onFirst 读出来了。放回去再 pause，数据才真正留在缓冲区里，
      // 等 attachData 挂好 pipe 后 resume 就能被正常读出。
      sock.unshift(chunk);
      sock.pause();
      room.pending.set(id, entry);
      log(`房间 ${room.roomId} 新玩家接入 id=${id} from ${sock.remoteAddress}`);

      // 玩家已经连上、房间也在线：先给一句「正在等待房主确认」。
      // 房主迟迟不点确认时，玩家端能看到原因，而不是干等到超时后
      // 只显示「连接已丢失」。
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

      // 通知房主（带上玩家地址，房主据此做接入确认 / 白名单）
      playerJoin(room, id, sock.remoteAddress || '');
    };

    sock.on('data', onFirst);
    sock.on('error', () => clearTimeout(silentTimer));
    sock.resume();      // 必须 resume，否则收不到 data 事件
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

// ============ 房主隧道 ============

/**
 * 房主连过来的连接：解析魔数后的一行 JSON，决定是控制通道还是数据通道
 */
function onTunnelConnection(sock, firstChunk) {
  let buf = '';

  // 握手阶段总超时：连上、发了半截魔数或半截 JSON 后再也不动的连接
  // 以前会永久挂着（房间端口上任何不完整握手都算），耗尽文件句柄。
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

    // 去掉行首的魔数（同一行内，魔数后面紧跟 JSON）
    const body = line.startsWith(MAGIC) ? line.slice(MAGIC.length) : line;

    let msg;
    try {
      msg = JSON.parse(body);
    } catch {
      sock.destroy();
      return;
    }

    // 魔数之后若还有数据（房主连发），放回去
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

/**
 * 把「新玩家接入」告诉房主。
 *
 * 房间端口在控制通道建立前就已经在监听：真实 MC 客户端连上后会**立刻**发握手，
 * 经常比房主的控制通道还早到。早期实现此时只打一行「无控制通道，无法通知房主」
 * 就放弃，那条玩家连接便一直挂到 30 秒超时 —— 表现就是「点允许之前对端就失败」。
 *
 * 现在改为排队：控制通道一建立就补发全部待通知的玩家。
 *
 * @returns true 已发出 / 已排队
 */
function playerJoin(room, id, from) {
  const msg = JSON.stringify({ type: 'join', id, from }) + '\n';
  if (room.control && safeWrite(room.control, msg)) {
    return true;
  }
  if (!room.pendingJoins) room.pendingJoins = [];
  room.pendingJoins.push({ id, from });
  log(`房间 ${room.roomId} 玩家 #${id} 到达时控制通道未就绪，已排队（共 ${room.pendingJoins.length} 个）`);
  return true;
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

  // 旧通道：先摘掉监听器再销毁，否则它的 close 事件会把整个房间带走
  if (old && old !== sock) {
    old.__isControl = false;
    old.removeAllListeners('close');
    try { old.destroy(); } catch { /* 忽略 */ }
  }

  sock.__isControl = true;
  sock.__room = room;

  // 只有「当前生效的那条」断开才回收房间
  sock.on('close', () => {
    if (sock.__room && sock.__room.control === sock) {
      closeRoom(sock.__room.roomId, '控制通道断开');
    }
  });

  // 心跳：房主定期发 ping，超时回收（避免死连接占着端口）
  let hb = '';
  sock.on('data', (chunk) => {
    room.lastSeen = Date.now();
    room.missedPings = 0;
    hb += chunk.toString('utf8');
    // 按行处理，避免跨段导致 includes 判断出错
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

  // 补发「控制通道建立前就到了」的玩家
  if (room.pendingJoins && room.pendingJoins.length) {
    const backlog = room.pendingJoins.splice(0);
    log(`房间 ${room.roomId} 补发 ${backlog.length} 个待通知的玩家`);
    for (const j of backlog) {
      // 玩家可能已经超时断开：pending 里没有就跳过
      if (!room.pending.has(j.id)) continue;
      safeWrite(sock, JSON.stringify({ type: 'join', id: j.id, from: j.from }) + '\n');
    }
  }
}

function attachData(sock, msg) {
  const room = rooms.get(msg.room);

  // 必须校验 token：否则知道房间号的任何人就能抢走玩家的连接
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

  // 内核收发缓冲区：Node 没有公开的 setRecvBufferSize/setSendBufferSize
  // （net.Socket 上不存在这两个方法，bufferSize 也只是只读属性）。
  // 要调大必须在系统层做，例如在服务器 /etc/sysctl.conf 里：
  //   net.core.rmem_max = 4194304
  //   net.core.wmem_max = 4194304
  //   net.ipv4.tcp_rmem = 4096 87380 4194304
  //   net.ipv4.tcp_wmem = 4096 65536 4194304
  // 应用层能做的只有下面这些：立即发送（关 Nagle）+ 保活。
  sock.setNoDelay(true);
  try { sock.setKeepAlive(true, 10_000); } catch { /* 平台不支持 */ }

  // 手动转发，**不要用 .pipe()**。
  //
  // Node 的 pipe() 在源端收到 end 时会自动 end() 目标端：MC 登录握手时
  // 客户端发完 Login Start 就停住等回包，只要这一侧出现一次 EOF（半关闭、
  // 网络波动），pipe 就把房主的数据通道也 end 掉，接着 cleanup 双端 close，
  // 玩家端表现为「登录中一闪就断开 / 会话无效」—— 这是联机秒断的根因。
  //
  // 改成监听 data + end：只 write 和 end()（半关闭），真正的回收交给
  // close / error 事件。
  //
  // 必须处理背压：write() 返回 false 表示对端缓冲区已满，此时继续读只会让
  // 本进程内存无限增长（大流量下卡顿、内存暴涨，甚至 OOM）。正确做法是
  // 暂停读取源端，等目标端触发 'drain' 再恢复。
  //
  // 注意两个边界：
  //   · 已经 pause 时不要重复 pause（否则 resume 次数对不上）；
  //   · 目标端已经结束/销毁时，'drain' 永远不会来，恢复不了，直接放弃这条通道。
  let clientPaused = false;
  let sockPaused = false;

  // 注意：**不能**用 `!stream.isPaused()` 当作「可以 resume」的判断。
  // 被 pause 过的流 isPaused() 返回 true，加个取反就永远不会调用 resume ——
  // 连接一旦触发背压就会永久卡住（实测：每次传输卡在 78% 左右，30 秒超时）。
  // 只需要保证「pause 和 resume 成对」，用我们自己的标志位就够了。
  const resumeClient = () => {
    if (!clientPaused) return;
    clientPaused = false;
    if (!client.destroyed) {
      try { client.resume(); } catch { /* 已关闭 */ }
    }
  };
  const resumeSock = () => {
    if (!sockPaused) return;
    sockPaused = false;
    if (!sock.destroyed) {
      try { sock.resume(); } catch { /* 已关闭 */ }
    }
  };

  client.on('data', (chunk) => {
    if (sock.destroyed || !sock.writable) return;
    const canWrite = sock.write(chunk);
    if (!canWrite && !clientPaused && !client.destroyed) {
      clientPaused = true;
      client.pause();
      sock.once('drain', resumeClient);
    }
  });
  sock.on('data', (chunk) => {
    if (client.destroyed || !client.writable) return;
    const canWrite = client.write(chunk);
    if (!canWrite && !sockPaused && !sock.destroyed) {
      sockPaused = true;
      sock.pause();
      client.once('drain', resumeSock);
    }
  });
  // 半关闭：一端说完了，只关闭对端的写方向，读方向继续
  client.on('end', () => {
    if (!sock.destroyed) sock.end();
  });
  sock.on('end', () => {
    if (!client.destroyed) client.end();
  });
  // 对端关闭后 drain 不会再触发：把可能停住的另一侧恢复，让 close 事件正常走到
  client.on('close', () => {
    if (sockPaused && !sock.destroyed) resumeSock();
  });
  sock.on('close', () => {
    if (clientPaused && !client.destroyed) resumeClient();
  });
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
  // 出错时才主动关两端；正常 half-close 走上面的 end 处理
  client.on('error', cleanup);
  sock.on('error', cleanup);

  log(`房间 ${room.roomId} 数据通道建立 id=${msg.id}`);
}

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
    return json(res, 429, { error: '请求过于频繁，请稍后再试' });
  }
  if (rooms.size >= MAX_ROOMS) {
    return json(res, 503, { error: '房间数量已达上限，请稍后再试' });
  }
  const port = allocPort();
  if (port === null) {
    return json(res, 503, { error: '端口池已满，请稍后再试' });
  }

  // 限时内必须建立控制通道
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
      try { obj = body ? JSON.parse(body) : {}; } catch { /* 空对象 */ }
      if (!API_KEY) {
        handleHost(req, res, obj);
      } else {
        requireApiKey(req, res, () => handleHost(req, res, obj));
      }
    });
    return;
  }

  if (req.method === 'GET' && path === '/api/rooms') {
    // 只在配置了 API_KEY 时进行验证，未配置则放行
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

// ============ 二进制申请协议（纯 TCP，供 WAF 后的隧道使用） ============
//
// 有些部署环境前面挂了 WAF，会把带 HTTP 特征的流量整段拦掉。
// 所以除了 HTTP API，这里再提供一个**纯二进制**的申请通道：
//
//   房主 → 中转：  "MCRQ" [房间名长度 uint16 BE] [房间名 UTF-8]
//   中转 → 房主：  "MCRO" [room 8B ascii] [token 32B ascii] [port uint32 BE]
//                 失败时： "MCRE" [错误信息长度 uint16 BE] [错误信息]
//
// 全程没有 HTTP 关键字，WAF 不会拦。

const BIN_MAGIC_REQ = 'MCRQ';
const BIN_MAGIC_OK = 'MCRO';
const BIN_MAGIC_ERR = 'MCRE';

/** 二进制申请端口：默认 HTTP_PORT + 1 */
const BIN_PORT = Number(process.env.BIN_PORT || (HTTP_PORT + 1));

/**
 * 处理一次二进制申请（数据可能分多次到达）
 *
 * @param sock       连接
 * @param prefetched 已经读到的字节（至少含 4 字节魔数）
 */
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

    setTimeout(() => { try { sock.destroy(); } catch { /* 忽略 */ } }, 500).unref();
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
  setTimeout(() => { try { sock.destroy(); } catch { /* 忽略 */ } }, 300).unref();
}

// ============ 单端口双协议 ============
//
// 同一个端口同时接受：
//   - 二进制申请（以 "MCRQ" 开头）—— WAF 环境唯一可用的方式
//   - HTTP API（其余流量）        —— 自建环境方便调试
//
// 做法：自己监听端口，先看首 4 字节，再决定交给谁。

/** HTTP 连接空闲超时（防止慢速攻击长期占句柄） */
const HTTP_IDLE_TIMEOUT_MS = 30_000;

/**
 * VPN 连接识别：房主/玩家连到 HTTP 端口后，第一行是
 *   MCRELAY{"type":"vpn-host",...}  或  MCDATA{"type":"vpn-join",...}
 * 与房间协议共用 MAGIC 前缀，所以挂在同一个分发点上。
 */
function maybeHandleVpn(sock, headBuf) {
  // 握手是第一行 JSON，**必须按字节找换行**：用字符串转换会破坏后面的二进制帧
  const nl = headBuf.indexOf(0x0a);
  if (nl < 0) return false;
  const line = headBuf.slice(0, nl).toString('utf8');
  if (!line.includes('"vpn-host"') && !line.includes('"vpn-join"')) return false;

  const body = line.startsWith(MAGIC) ? line.slice(MAGIC.length) : line.replace(/^MCDATA/, '');
  // 同一块里已经读到的帧数据（可能为空）：原样交给 VPN，不能丢
  const rest = headBuf.slice(nl + 1);
  handleVpnConnection(sock, body, rooms, log, rest);
  return true;
}

const frontServer = net.createServer((sock) => {
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

    if (head.slice(0, 4).toString('latin1') === BIN_MAGIC_REQ) {
      handleBinHandshake(sock, head);
    } else if (maybeHandleVpn(sock, head)) {
      // 已交给 VPN 处理
    } else {
      // 交给 HTTP 解析器：慢速攻击（发齐 4 字节后一个字节一个字节地拖）
      // 会让连接长期占着句柄，这里给 http 侧设置空闲超时
      sock.setTimeout(HTTP_IDLE_TIMEOUT_MS, () => {
        try { sock.destroy(); } catch { /* 忽略 */ }
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
  log(`玩家接入限流 ${JOIN_RATE_LIMIT} 次/${JOIN_RATE_WINDOW_MS / 1000} 秒` +
    (BLOCK_IPS.size ? `  黑名单: ${[...BLOCK_IPS].join(',')}` : ''));
});

function requireApiKey(req, res, next) {
  const key = req.headers['x-api-key'] || '';
  if (!API_KEY) {
    // 未配置 API_KEY，放行（兼容旧部署）
    return next();
  }
  if (key !== API_KEY) {
    return res.status(401).json({ error: '无效的 API 密钥' });
  }
  next();
}

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
  // 玩家接入限流窗口过期后清掉，避免 Map 无限增长
  for (const [ip, rec] of joinRate) {
    if (now >= rec.resetAt) joinRate.delete(ip);
  }
}, HEARTBEAT_MS).unref();

// ============ 退出 ============

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

// 兜底：未捕获异常不要让整个中转挂掉
process.on('uncaughtException', (e) => {
  log('未捕获异常:', e && e.stack ? e.stack.split('\n')[0] : e);
});
process.on('unhandledRejection', (e) => {
  log('未处理的 Promise 拒绝:', e);
});