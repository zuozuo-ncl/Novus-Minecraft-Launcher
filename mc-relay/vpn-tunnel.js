/**
 * VPN 转发模块（挂到现有的 mc-relay 服务器上）
 *
 * 设计
 * ----
 * 房主和玩家各自用 TCP 长连接到本服务器，服务器在其中转字节。
 * 每条连接开头是一行 JSON 握手（与房间协议同样的魔数前缀）：
 *
 *     房主：  MCRELAY\n{"type":"vpn-host","room":"a1b2c3","token":"...","peers":["uuid1",...]}\n
 *     玩家：  MCDATA\n{"type":"vpn-join","room":"a1b2c3","uuid":"..."}\n
 *
 * 房间用现有 createRoom 分配（复用端口池、速率限制、控制通道心跳），
 * 区别是 **不再监听端口**，所有流量都通过上面这两条 TCP 长连接。
 *
 * 转发规则
 * --------
 *   · 房主 → 服务器：二进制帧 {peer_uuid_len u8}{peer_uuid}{ip_packet}，
 *     服务器按 uuid 找到对应玩家连接并写出 ip_packet。
 *   · 玩家 → 服务器：帧 {ip_packet}（玩家只知道自己在和房主通信），
 *     服务器转给该房间的房主连接，并附上玩家 uuid。
 *
 * 为什么不复用现有 attachData：那是「一个玩家一条 TCP + 端口」模型，
 * 每个玩家要占一个公网端口，且一次只能转发一条 MC 连接。VPN 只需要
 * 房主与玩家各一条长连接，N 个玩家共用一个房间。
 *
 * 帧格式（长度前缀，均在 JSON 握手之后）
 * --------------------------------------
 *   房主 -> 服务器：
 *      u32 len (整帧剩余字节数，小端)
 *      u8  uuidLen
 *      bytes uuid
 *      bytes ipPacket
 *   服务器 -> 房主：
 *      u32 len
 *      u8  uuidLen
 *      bytes uuid
 *      bytes ipPacket
 *   玩家 -> 服务器：u32 len + bytes ipPacket
 *   服务器 -> 玩家：u32 len + bytes ipPacket
 */
'use strict';

const MAX_FRAME = 64 * 1024;
const MAX_UUID = 40;

/**
 * 把原始 TCP 流切成「握手行 + 帧流」。
 * 返回 { onHandshake, onFrame }，由调用方决定怎么处理。
 */
function makeFramer(sock, prefetched, onHandshake, onFrame, onError) {
  let buf = Buffer.alloc(0);

  const process_ = () => {
    while (buf.length >= 4) {
      const len = buf.readUInt32LE(0);
      if (len <= 0 || len > MAX_FRAME) {
        onError('帧长度非法: ' + len);
        return;
      }
      if (buf.length < 4 + len) return;
      const f = buf.slice(4, 4 + len);
      buf = buf.slice(4 + len);
      onFrame(f);
    }
  };

  sock.on('data', (chunk) => {
    buf = buf.length ? Buffer.concat([buf, chunk]) : chunk;
    process_();
  });

  // 把已经读到的字节喂进去（frontServer 判定阶段就读走了一部分）。
  // 必须 **prepend** 而不是覆盖：挂 listener 之后、执行到这里之前，
  // 可能已经有一条 data 事件进来并写进了 buf（例如房主刚连上就收到玩家的包）。
  // 覆盖会把那段数据直接吞掉，后续帧的边界就全乱 —— 表现为「帧长度非法」。
  if (prefetched && prefetched.length) {
    buf = buf.length ? Buffer.concat([prefetched, buf]) : Buffer.from(prefetched);
    process_();
  }
  return { feed: () => process_() };
}

/** 组帧：u32 长度 + 内容 */
function frame(payload) {
  const h = Buffer.alloc(4);
  h.writeUInt32LE(payload.length, 0);
  return Buffer.concat([h, payload]);
}

/** 房主 -> 服务器方向的帧：uuid + ip 包 */
function frameToHost(uuid, ipPacket) {
  const ub = Buffer.from(uuid, 'utf8');
  const payload = Buffer.concat([Buffer.from([ub.length]), ub, ipPacket]);
  return frame(payload);
}

module.exports = { makeFramer, frame, frameToHost, MAX_FRAME, MAX_UUID };