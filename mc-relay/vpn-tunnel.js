/**
 * VPN 转发模块（挂到现有的 mc-relay 服务器上）
 * 房主再把这个 token/uuid 转给对应玩家。vpn-join 必须带 token，且一票一用。
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
  // 交给 VPN 时 socket 一般还停在判定阶段留下的暂停状态（relay-server 的
  // frontServer 先 pause 再嗅探魔数）。这里不 resume 的话，房主/玩家的数据
  // 永远读不到 —— 表现为「房主已连接」之后立即断开。
  if (typeof sock.resume === 'function') {
    try { sock.resume(); } catch { /* 已关闭 */ }
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