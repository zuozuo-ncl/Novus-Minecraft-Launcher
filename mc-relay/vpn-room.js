'use strict';
/**
 * VPN 转发房间（挂在 relay-server.js 启动时 require 一次）
 * 安全性
 */

const net = require('net');
const crypto = require('crypto');

const { makeFramer, frame, frameToHost, MAX_UUID } = require('./vpn-tunnel');

/** 单房间最多玩家（与 MC 局域网一致，够用且防滥用） */
const MAX_VPN_PEERS = 10;
/** 帧缓冲区：单条连接允许积压的上限，超过就断开（防内存暴涨） */
const MAX_BUFFERED = 8 * 1024 * 1024;
/** 待用 join token 的寿命：房主签发后多久内有效 */
const JOIN_TOKEN_TTL_MS = 120_000;
/** 单个 IP 包的上限（IP 头 20 + 65535 数据报） */
const MAX_IP_PACKET = 65_535 + 64;

/** 虚拟网段（与启动器 VpnNetworkConfig 一致：10.77.0.1 是房主） */
const SUBNET = '10.77.0.';
const PEER_IP_MIN = 2;
const PEER_IP_MAX = 9;

/**
 * VPN 连接空闲超时：这段时间内一个帧都没收到就断开。
 * 为什么必须有：VPN 连接不走房间端口，frontServer 的静默超时在识别出 VPN
 */
const VPN_IDLE_TIMEOUT_MS = Number(process.env.VPN_IDLE_TIMEOUT_MS || 60_000);

/** uuid 只允许安全字符，防止握手里塞进控制字符/换行做日志注入 */
const UUID_RE = /^[A-Za-z0-9_-]{1,40}$/;
const ROOM_RE = /^[A-Za-z0-9]{1,16}$/;
/** 握手前缀，与 relay-server.js 的 MAGIC / 旧版 MCDATA 一致 */
const MAGIC_PREFIX = 'MCRELAY';

/** 控制通道上发给房主的消息（同一个控制通道也承载普通文本信令） */
function sendControlJson(room, obj) {
    const c = room.control;
    if (!c || c.destroyed || !c.writable) return false;
    try {
        c.write(JSON.stringify(obj) + '\n');
        return true;
    } catch {
        return false;
    }
}

class VpnRoom {
    constructor(roomId, token) {
        this.roomId = roomId;
        this.token = token;
        /** 房主的 TCP 长连接 */
        this.host = null;
        /** uuid -> socket */
        this.peers = new Map();
        /** uuid -> 虚拟 IP */
        this.ipByUuid = new Map();
        /** 虚拟 IP -> uuid（房主下发时反查） */
        this.uuidByIp = new Map();
        /** 已发出的 join token -> { uuid, ip, expiresAt } */
        this.joinTokens = new Map();
        /** 分享给玩家的口令 -> { token, uuid, ip, expiresAt } */
        this.joinCodes = new Map();
        /** 最后活动时间，用于空闲回收 */
        this.lastSeen = Date.now();
    }

    allocSeat() {
        const used = new Set();
        for (const ip of this.ipByUuid.values()) used.add(ip);
        for (const t of this.joinTokens.values()) used.add(t.ip);
        for (const t of this.joinCodes.values()) used.add(t.ip);
        for (let n = PEER_IP_MIN; n <= PEER_IP_MAX; n++) {
            const ip = SUBNET + n;
            if (!used.has(ip)) return ip;
        }
        return null;
    }

    /** 房主 → 某个玩家（同样要加长度前缀帧！） */
    toPeer(uuid, ipPacket) {
        const p = this.peers.get(uuid);
        if (!p || p.destroyed) return false;
        // 玩家侧的协议是「u32 长度 + ip 包」。这里漏了 frame() 的话，
        // 玩家会把 IP 头的前 4 字节（45 00 00 3C）当成帧长度，直接对不齐。
        return writeWithBackpressure(p, frame(ipPacket));
    }

    /** 玩家 → 房主（前缀为玩家的虚拟 IP，而不是 uuid） */
    toHost(uuid, ipPacket) {
        if (!this.host || this.host.destroyed) return false;
        return writeWithBackpressure(this.host, frameToHost(uuid, ipPacket));
    }

    /** 玩家 → 房主：按虚拟 IP 分发（房主侧用目的地址反查） */
    toHostByIp(uuid, ipPacket) {
        if (!this.host || this.host.destroyed) return false;
        return writeWithBackpressure(this.host, frameToHost(this.ipByUuid.get(uuid) || uuid, ipPacket));
    }

    /** 清理过期的 join token */
    pruneJoinTokens(now) {
        for (const [t, rec] of this.joinTokens) {
            if (rec.expiresAt <= now) this.joinTokens.delete(t);
        }
    }

    close(reason, log) {
        this.joinTokens.clear();
        this.joinCodes.clear();
        if (this.host) {
            try { this.host.destroy(); } catch { /* 忽略 */ }
            this.host = null;
        }
        for (const [, s] of this.peers) {
            try { s.destroy(); } catch { /* 忽略 */ }
        }
        this.peers.clear();
        this.ipByUuid.clear();
        this.uuidByIp.clear();
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

/** IPv4 头是否合法：首段 0x4X（version 4），IHL>=5，总长与缓冲区一致 */
function isSaneIpPacket(ip) {
    if (!ip || ip.length < 20 || ip.length > MAX_IP_PACKET) return false;
    if ((ip[0] >> 4) !== 4) return false;
    const ihl = (ip[0] & 0x0f) * 4;
    if (ihl < 20 || ihl > ip.length) return false;
    const total = ip.readUInt16BE(2);
    return total > 0 && total <= ip.length;
}

/** 处理一条 VPN 连接。 */
function handleVpnConnection(sock, line, rooms, log, prefetched, knownRoom) {
    let msg;
    try {
        msg = JSON.parse(line);
    } catch {
        sock.destroy();
        return;
    }
    if (!msg || typeof msg !== 'object') {
        sock.destroy();
        return;
    }
    // msg.room 会被拼进日志：先校验再使用（否则可注入换行伪造日志行）
    if (typeof msg.room !== 'string' || !ROOM_RE.test(msg.room)) {
        log('VPN 连接失败：房间号格式不正确');
        sock.destroy();
        return;
    }
    const room = knownRoom || rooms.get(msg.room);
    if (!room) {
        log(`VPN 连接失败：房间不存在 ${msg.room}`);
        sock.destroy();
        return;
    }


    sock.setNoDelay(true);
    sock.on('error', () => { /* 由 close 处理 */ });

    // ------------------------------------------------------------------
    //  房主
    // ------------------------------------------------------------------
    if (msg.type === 'vpn-host') {
        // 房主鉴权失败时不要顺手创建 VpnRoom（以前会留下没有 host 的僵尸对象）
        if (typeof msg.token !== 'string' || room.token !== msg.token) {
            log(`VPN 房主鉴权失败 ${room.roomId}`);
            sock.destroy();
            return;
        }
        if (!room.vpn) room.vpn = new VpnRoom(room.roomId, room.token);
        const vpn = room.vpn;

        // 替换旧房主：先摘掉旧连接的 close 回调，否则旧连接关闭时会把新房间一起关掉
        if (vpn.host && !vpn.host.destroyed) {
            try { vpn.host.removeAllListeners('close'); } catch { /* 忽略 */ }
            try { vpn.host.destroy(); } catch { /* 忽略 */ }
        }
        vpn.host = sock;
        log(`VPN 房间 ${room.roomId} 房主已连接`);

        sock.on('close', () => {
            // 只有「当前生效的那条」断开才回收
            if (room.vpn !== vpn || vpn.host !== sock) return;
            log(`VPN 房间 ${room.roomId} 房主断开，关闭所有玩家连接`);
            vpn.close('房主断开', log);
            room.vpn = null;
        });

        // 把已在线的成员补发给新房主（房主重连时用）
        for (const [uuid, ip] of vpn.ipByUuid) {
            sendControlJson(room, { type: 'vpn-peer', uuid, ip });
        }

        const touchHost = idleWatchdog(sock, `房主 ${room.roomId}`, log);

        makeFramer(sock, prefetched, () => { /* 房主不会再发握手 */ }, (f) => {
            touchHost();
            // 房主 → 玩家：uuidLen + uuid + ip
            if (f.length < 1) return;
            const ul = f[0];
            if (ul === 0 || ul > MAX_UUID || f.length < 1 + ul) return;
            const uuid = f.slice(1, 1 + ul).toString('utf8');
            // 两种前缀都认：uuid（房主自己发的）或玩家的虚拟 IP（测试/其它客户端）
            const target = vpn.ipByUuid.has(uuid)
                ? uuid
                : (vpn.uuidByIp.get(uuid) || null);
            if (!target) return;   // 未登记：丢弃
            const ip = f.slice(1 + ul);
            if (!isSaneIpPacket(ip)) return;
            vpn.toPeer(target, ip);
        }, () => { try { sock.destroy(); } catch { /* 忽略 */ } });

        return;
    }

    // ------------------------------------------------------------------
    if (msg.type === 'vpn-claim') {
        const vpn = room.vpn;
        const code = typeof msg.code === 'string' ? msg.code : '';
        if (!vpn || !vpn.host || vpn.host.destroyed) {
            sock.end(JSON.stringify({ error: '房主尚未建立 VPN 通道' }) + '\n');
            return;
        }
        vpn.pruneJoinTokens(Date.now());
        const rec = vpn.joinCodes.get(code);
        if (!rec || rec.expiresAt <= Date.now()) {
            log(`VPN 房间 ${room.roomId} 口令无效，拒绝加入`);
            sock.end(JSON.stringify({ error: '口令无效或已过期，请让房主重新发送邀请' }) + '\n');
            return;
        }
        if (vpn.peers.size + vpn.joinTokens.size >= MAX_VPN_PEERS) {
            sock.end(JSON.stringify({ error: 'VPN 房间已满' }) + '\n');
            return;
        }
        vpn.joinCodes.delete(code);   // 一票一次
        vpn.joinTokens.set(rec.token, { uuid: rec.uuid, ip: rec.ip,
                                        expiresAt: Date.now() + JOIN_TOKEN_TTL_MS });
        log(`VPN 房间 ${room.roomId} 玩家凭口令换取 token（${rec.ip}）`);
        sock.end(JSON.stringify({ token: rec.token, uuid: rec.uuid, ip: rec.ip }) + '\n');
        return;
    }

    if (msg.type === 'vpn-join') {
        const vpn = room.vpn;
        const uuid = typeof msg.uuid === 'string' ? msg.uuid : '';
        const joinToken = typeof msg.token === 'string' ? msg.token : '';

        if (!vpn || !vpn.host || vpn.host.destroyed) {
            log(`VPN 房间 ${room.roomId} 房主未连接，拒绝加入`);
            sock.destroy();
            return;
        }
        if (!joinToken || !uuid) {
            log(`VPN 房间 ${room.roomId} 玩家缺少 join token，拒绝`);
            sock.destroy();
            return;
        }
        vpn.pruneJoinTokens(Date.now());
        const rec = vpn.joinTokens.get(joinToken);
        if (!rec || rec.uuid !== uuid) {
            log(`VPN 房间 ${room.roomId} join token 无效，拒绝`);
            sock.destroy();
            return;
        }
        if (vpn.peers.size >= MAX_VPN_PEERS) {
            log(`VPN 房间 ${room.roomId} 已满，拒绝`);
            sock.destroy();
            return;
        }
        if (vpn.peers.has(uuid)) {
            // 同 uuid 已在线：拒绝覆盖（否则一条连接就能把别人踢掉）
            log(`VPN 房间 ${room.roomId} uuid 重复在线，拒绝`);
            sock.destroy();
            return;
        }
        vpn.joinTokens.delete(joinToken);

        const ip = rec.ip;
        sock.__vpn = { room, vpn, uuid, ip };   // 便于排障/对账
        vpn.peers.set(uuid, sock);
        vpn.ipByUuid.set(uuid, ip);
        vpn.uuidByIp.set(ip, uuid);
        log(`VPN 房间 ${room.roomId} 玩家 ${uuid} 已加入 (${ip}，共 ${vpn.peers.size})`);

        // 告知双方：玩家自己的地址 + 房主的新成员
        sendControlJson(room, { type: 'vpn-peer', uuid, ip });

        sock.on('close', () => {
            // 只删「自己那条」，不能按 uuid 盲删（否则会误删后来者的连接）
            if (vpn.peers.get(uuid) !== sock) return;
            vpn.peers.delete(uuid);
            vpn.ipByUuid.delete(uuid);
            vpn.uuidByIp.delete(ip);
            if (room.vpn === vpn) {
                sendControlJson(room, { type: 'vpn-peer-left', uuid });
                log(`VPN 房间 ${room.roomId} 玩家 ${uuid} 离开（剩 ${vpn.peers.size}）`);
            }
        });

        const touchPeer = idleWatchdog(sock, `玩家 ${uuid}`, log);

        makeFramer(sock, prefetched, () => { /* 玩家不会再发握手 */ }, (ipPacket) => {
            touchPeer();
            if (!isSaneIpPacket(ipPacket)) return;
            // 前缀用玩家的虚拟 IP：房主侧完全按「包的目的/来源地址」分发
            vpn.toHostByIp(uuid, ipPacket);
        }, () => { try { sock.destroy(); } catch { /* 忽略 */ } });

        return;
    }

    sock.destroy();
}

/** 在 frontServer 上挂 VPN 处理：识别首行是否为 VPN 握手。 */
function looksLikeVpnHandshake(line) {
    if (!line) return false;
    // 魔数（MCRELAY / MCDATA）后面紧跟 JSON；没有魔数前缀的行不算 VPN 握手
    const trimmed = line.trimStart();
    const idx = trimmed.indexOf('{');
    if (idx < 0) return false;
    const prefix = trimmed.slice(0, idx);
    // 魔数后面紧跟 JSON（MCRELAY{"type":...}）。以前只要行里出现 "vpn-join"
    // 字样就当 VPN 处理，任何含该字样的 HTTP 请求都会被断开。
    if (prefix !== MAGIC_PREFIX) return false;
    try {
        const obj = JSON.parse(trimmed.slice(idx));
        return obj && (obj.type === 'vpn-host' || obj.type === 'vpn-join'
            || obj.type === 'vpn-claim');
    } catch {
        return false;
    }
}

/** 兼容旧名字 */
const isVpnHandshake = looksLikeVpnHandshake;

/** 房主为即将接入的玩家签发 join token（由控制通道信令调用） */
function issueJoinToken(room, id, log) {
    const vpn = room.vpn;
    if (!vpn || !vpn.host || vpn.host.destroyed) {
        sendControlJson(room, { type: 'vpn-token-error', id, error: '房主尚未建立 VPN 通道' });
        return;
    }
    if (vpn.peers.size + vpn.joinTokens.size >= MAX_VPN_PEERS) {
        sendControlJson(room, { type: 'vpn-token-error', id, error: 'VPN 房间已满' });
        return;
    }
    vpn.pruneJoinTokens(Date.now());
    const ip = vpn.allocSeat();
    if (!ip) {
        sendControlJson(room, { type: 'vpn-token-error', id, error: '没有空闲的虚拟地址' });
        return;
    }
    const token = crypto.randomBytes(16).toString('hex');
    // 分享给玩家的口令：8 位，玩家输入/粘贴用
    const code = crypto.randomBytes(4).toString('hex');
    const uuid = 'p' + crypto.randomBytes(8).toString('hex');   // 16 位十六进制
    vpn.joinCodes.set(code, { token, uuid, ip, expiresAt: Date.now() + JOIN_TOKEN_TTL_MS });
    // 清掉过期口令
    for (const [c, r] of vpn.joinCodes) {
        if (r.expiresAt <= Date.now()) vpn.joinCodes.delete(c);
    }
    log(`VPN 房间 ${room.roomId} 为玩家 #${id} 分配地址 ${ip}`);
    sendControlJson(room, { type: 'vpn-token', id, token, uuid, ip, code });
}

module.exports = {
    handleVpnConnection,
    isVpnHandshake,
    looksLikeVpnHandshake,
    issueJoinToken,
    VpnRoom,
    MAX_VPN_PEERS,
};