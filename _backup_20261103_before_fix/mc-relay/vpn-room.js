'use strict';
/**
 * VPN 转发房间（挂在 relay-server.js 启动时 require 一次）
 *
 * 职责
 * ----
 *   · 房主开一条 TCP 长连接到 HTTP 端口，握手 {type:"vpn-host"}，成为该房间的转发出口
 *   · 每个玩家开一条 TCP 长连接到同一端口，握手 {type:"vpn-join"}，登记到该房间
 *   · 双方发的都是帧（见 vpn-tunnel.js），服务器只做「按 uuid 交换 IP 包」
 *
 * 复用现有基础设施
 * ----------------
 *   · 房间生命周期、速率限制、控制通道心跳 → 仍走 relay-server.js 的 rooms
 *   · 但 VPN 房间 **不开端口**：所有流量走上面的长连接
 *
 * 安全性
 * ------
 *   · 玩家必须带上房主在控制通道下发的 token 才能登记
 *   · 房主连接断开 = 房间结束，所有玩家连接一起关闭
 */

const net = require('net');

const { makeFramer, frame, frameToHost } = require('./vpn-tunnel');

/** 单房间最多玩家（与 MC 局域网一致，够用且防滥用） */
const MAX_VPN_PEERS = 10;
/** 帧缓冲区：单条连接允许积压的上限，超过就断开（防内存暴涨） */
const MAX_BUFFERED = 8 * 1024 * 1024;
/**
 * VPN 连接空闲超时：这段时间内一个帧都没收到就断开。
 *
 * 为什么必须有：VPN 连接不走房间端口，frontServer 的静默超时在识别出 VPN
 * 之后就不再生效。没有这道兜底时，攻击者只要知道房间号，连上发一帧
 * vpn-join 然后什么都不发，就能永久占住一条连接 + 一个 peer 名额
 * （房间上限 10 个），把真正的玩家挤出去。
 *
 * 60 秒足够覆盖 MC 客户端的各种停顿（卡加载、暂停），又不会让死连接长期滞留。
 */
const VPN_IDLE_TIMEOUT_MS = Number(process.env.VPN_IDLE_TIMEOUT_MS || 60_000);

class VpnRoom {
  constructor(roomId, token) {
    this.roomId = roomId;
    this.token = token;
    /** 房主的 TCP 长连接 */
    this.host = null;
    /** uuid -> socket */
    this.peers = new Map();
    /** 最后活动时间，用于空闲回收 */
    this.lastSeen = Date.now();
  }

  /** 房主 → 某个玩家（同样要加长度前缀帧！） */
  toPeer(uuid, ipPacket) {
    const p = this.peers.get(uuid);
    if (!p || p.destroyed) return false;
    // 玩家侧的协议是「u32 长度 + ip 包」。这里漏了 frame() 的话，
    // 玩家会把 IP 头的前 4 字节（45 00 00 3C）当成帧长度，直接对不齐。
    return writeWithBackpressure(p, frame(ipPacket));
  }

  /** 玩家 → 房主（附 uuid） */
  toHost(uuid, ipPacket) {
    if (!this.host || this.host.destroyed) return false;
    return writeWithBackpressure(this.host, frameToHost(uuid, ipPacket));
  }

  close(reason, log) {
    if (this.host) {
      try { this.host.destroy(); } catch { /* 忽略 */ }
      this.host = null;
    }
    for (const [, s] of this.peers) {
      try { s.destroy(); } catch { /* 忽略 */ }
    }
    this.peers.clear();
    if (log) log(`VPN 房间 ${this.roomId} 关闭 (${reason})`);
  }
}

function writeWithBackpressure(sock, buf) {
  if (sock.destroyed || !sock.writable) return false;
  if (sock.writableLength > MAX_BUFFERED) {
    // 对端不读，积压过多：断开，让上层重连
    try { sock.destroy(); } catch { /* 忽略 */ }
    return false;
  }
  return sock.write(buf);
}

/**
 * 给 VPN 连接挂空闲看门狗：每收到一个帧就重置，超时未收到则断开。
 *
 * @returns 重置函数（收到帧时调用）
 */
function idleWatchdog(sock, label, log) {
  let timer = null;
  const reset = () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      log(`VPN 连接空闲超时，断开: ${label}`);
      try { sock.destroy(); } catch { /* 忽略 */ }
    }, VPN_IDLE_TIMEOUT_MS);
    timer.unref();
  };
  sock.on('close', () => { if (timer) clearTimeout(timer); });
  reset();
  return reset;
}

/**
 * 处理一条 VPN 连接。
 *
 * @param sock    已建立的 TCP 连接
 * @param line    握手行（已去掉魔数前缀）
 * @param rooms   relay-server 的 rooms（Map）
 * @param log     relay-server 的 log 函数
 */
function handleVpnConnection(sock, line, rooms, log, prefetched) {
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    sock.destroy();
    return;
  }
  const room = rooms.get(msg.room);
  if (!room) {
    log(`VPN 连接失败：房间不存在 ${msg.room}`);
    sock.destroy();
    return;
  }
  // 房间上挂一个 VPN 对象（懒创建）。
  // 注意：只有合法的房主连接才会保留它；下面任何一个失败分支都要清掉，
  // 否则会留下一个没有 host 的僵尸 VpnRoom，玩家仍然能 join 到它上面。
  if (!room.vpn) room.vpn = new VpnRoom(room.roomId, room.token);

  sock.setNoDelay(true);
  sock.on('error', () => { /* 由 close 处理 */ });

  if (msg.type === 'vpn-host') {
    if (room.token !== msg.token) {
      log(`VPN 房主鉴权失败 ${room.roomId}`);
      if (!room.vpn.host) {
        room.vpn = null;      // 清掉刚懒创建的僵尸对象
      }
      sock.destroy();
      return;
    }
    if (room.vpn.host && !room.vpn.host.destroyed) {
      try { room.vpn.host.destroy(); } catch { /* 忽略 */ }
    }
    room.vpn.host = sock;
    log(`VPN 房间 ${room.roomId} 房主已连接`);

    sock.on('close', () => {
      log(`VPN 房间 ${room.roomId} 房主断开，关闭所有玩家连接`);
      room.vpn.close('房主断开', log);
      room.vpn = null;
    });

    const touchHost = idleWatchdog(sock, `房主 ${room.roomId}`, log);

    let frames = 0;
    makeFramer(sock, prefetched, () => { /* 房主不会再发握手 */ }, (f) => {
      touchHost();
      // 房主 → 玩家：uuidLen + uuid + ip
      if (f.length < 1) return;
      const ul = f[0];
      if (ul === 0 || ul > 40 || f.length < 1 + ul) return;
      const uuid = f.slice(1, 1 + ul).toString('utf8');
      const ip = f.slice(1 + ul);
      room.vpn && room.vpn.toPeer(uuid, ip);
      if ((++frames & 0xff) === 0) room.lastSeen = Date.now();
    }, () => { try { sock.destroy(); } catch { /* 忽略 */ } });

    return;
  }

  if (msg.type === 'vpn-join') {
    const uuid = String(msg.uuid || '').slice(0, 40);
    if (!uuid) {
      sock.destroy();
      return;
    }
    // 房主必须先连上，否则玩家挂了连接也没有对端，白白占住名额
    if (!room.vpn.host || room.vpn.host.destroyed) {
      log(`VPN 房间 ${room.roomId} 房主未连接，拒绝 ${uuid}`);
      sock.destroy();
      return;
    }
    if (room.vpn.peers.size >= MAX_VPN_PEERS) {
      log(`VPN 房间 ${room.roomId} 已满，拒绝 ${uuid}`);
      sock.destroy();
      return;
    }
    room.vpn.peers.set(uuid, sock);
    log(`VPN 房间 ${room.roomId} 玩家 ${uuid} 已加入（共 ${room.vpn.peers.size}）`);

    sock.on('close', () => {
      if (room.vpn) {
        room.vpn.peers.delete(uuid);
        log(`VPN 房间 ${room.roomId} 玩家 ${uuid} 离开（剩 ${room.vpn.peers.size}）`);
      }
    });

    const touchPeer = idleWatchdog(sock, `玩家 ${uuid}`, log);

    let frames = 0;
    makeFramer(sock, prefetched, () => { /* 玩家不会再发握手 */ }, (ip) => {
      touchPeer();
      room.vpn && room.vpn.toHost(uuid, ip);
      if ((++frames & 0xff) === 0) room.lastSeen = Date.now();
    }, () => { try { sock.destroy(); } catch { /* 忽略 */ } });

    return;
  }

  sock.destroy();
}

/**
 * 在 frontServer 上挂 VPN 处理：识别首行是否为 VPN 握手。
 *
 * 现有 relay-server 已经有「先看魔数决定交给谁」的分发，这里提供一个
 * 判断函数，供 relay-server 的 onTunnelConnection / frontServer 调用。
 */
function isVpnHandshake(line) {
  return line.includes('"vpn-host"') || line.includes('"vpn-join"');
}

module.exports = { handleVpnConnection, isVpnHandshake, VpnRoom };