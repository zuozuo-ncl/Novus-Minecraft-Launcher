package main

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net"
	"sync"
	"time"
)

// connCloser 抽象一条连接，便于统一关闭与判断状态
type connCloser interface {
	Write(p []byte) (int, error)
	Close() error
	isClosed() bool
	closeQuietly()
}

// tcpConn 包装 net.Conn，记录空闲看门狗与"已关闭"状态
type tcpConn struct {
	net.Conn
	writeMu sync.Mutex
	closed  bool
	closeMu sync.Mutex
	idleMu       sync.Mutex
	idleT        *time.Timer
	idleDisabled bool
	label        string
}

func newTCPConn(c net.Conn, label string) *tcpConn {
	return &tcpConn{Conn: c, label: label}
}

func (c *tcpConn) Write(p []byte) (int, error) {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	return c.Conn.Write(p)
}

func (c *tcpConn) isClosed() bool {
	c.closeMu.Lock()
	defer c.closeMu.Unlock()
	return c.closed
}

func (c *tcpConn) closeQuietly() {
	if c == nil {
		return
	}
	c.closeMu.Lock()
	c.closed = true
	c.closeMu.Unlock()
	c.stopIdle()
	_ = c.Conn.Close()
}

// replyAndClose 先回一行 JSON 再关闭连接。
// 不能写完立刻 Close：内核发送缓冲里还没发出去的数据会被 RST 丢掉
func (c *tcpConn) replyAndClose(errMsg string) {
	if c == nil {
		return
	}
	if errMsg != "" {
		b, _ := json.Marshal(map[string]string{"error": errMsg})
		_, _ = c.Write(append(b, '\n'))
	}
	c.closeMu.Lock()
	c.closed = true
	c.closeMu.Unlock()
	c.stopIdle()
	if cw, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
		_ = c.Conn.SetReadDeadline(time.Now().Add(replyDrainTimeout))
		var junk [256]byte
		for {
			if _, err := c.Conn.Read(junk[:]); err != nil {
				break
			}
		}
	}
	_ = c.Conn.Close()
}

// replyDrainTimeout 回完最后一包后等待对端关闭的时长上限
const replyDrainTimeout = 2 * time.Second

// touch 重置空闲看门狗：超过 vpnIdleTimeout 没有任何帧就断开。
// 房主连接不用这个（见 disableIdle）：它本来就可能长时间收不到上行帧。
func (c *tcpConn) touch() {
	c.idleMu.Lock()
	defer c.idleMu.Unlock()
	if c.idleDisabled {
		return
	}
	if c.idleT != nil {
		c.idleT.Stop()
	}
	c.idleT = time.AfterFunc(vpnIdleTimeout, func() {
		logf("VPN 连接空闲超时，断开: %s", c.label)
		c.closeQuietly()
	})
}

// disableIdle 关掉空闲看门狗。
//
// 房主的连接只有「玩家 → 房主」一个方向有数据，所有玩家都静止（挂机、看菜单）
// 时房主 60 秒收不到任何帧是正常现象，不能据此判定它已死 —— 那样会把整间房的人踢掉。
// 房主是否存活交给 TCP keepalive 判定。
func (c *tcpConn) disableIdle() {
	c.idleMu.Lock()
	defer c.idleMu.Unlock()
	c.idleDisabled = true
	if c.idleT != nil {
		c.idleT.Stop()
		c.idleT = nil
	}
}

func (c *tcpConn) stopIdle() {
	c.idleMu.Lock()
	defer c.idleMu.Unlock()
	if c.idleT != nil {
		c.idleT.Stop()
		c.idleT = nil
	}
}

// readFrame 读一个 u32 长度前缀的帧。返回值是去掉长度头后的内容。
func readFrame(r io.Reader, lenBuf []byte) ([]byte, error) {
	if _, err := io.ReadFull(r, lenBuf); err != nil {
		return nil, err
	}
	n := binary.LittleEndian.Uint32(lenBuf)
	if n == 0 || n > maxFrame {
		return nil, errBadFrame
	}
	frame := make([]byte, n)
	if _, err := io.ReadFull(r, frame); err != nil {
		return nil, err
	}
	return frame, nil
}

var errBadFrame = errors.New("帧长度非法")

// writeFrame 写一个 u32 长度前缀 + 内容
func writeFrame(w io.Writer, payload []byte) error {
	var h [4]byte
	binary.LittleEndian.PutUint32(h[:], uint32(len(payload)))
	buf := make([]byte, 4+len(payload))
	copy(buf, h[:])
	copy(buf[4:], payload)
	_, err := w.Write(buf)
	return err
}

// frameToHost 组「房主方向」的帧：u8 uuidLen | prefix | ipPacket
func frameToHost(prefix string, ipPacket []byte) ([]byte, error) {
	if len(prefix) == 0 || len(prefix) > maxUUID {
		return nil, errBadFrame
	}
	payload := make([]byte, 1+len(prefix)+len(ipPacket))
	payload[0] = byte(len(prefix))
	copy(payload[1:], prefix)
	copy(payload[1+len(prefix):], ipPacket)
	return payload, nil
}

// isSaneIPPacket 校验裸 IPv4 报文（与 Node 版 isSaneIpPacket 一致）
func isSaneIPPacket(p []byte) bool {
	if len(p) < 20 || len(p) > maxIPPacket {
		return false
	}
	if p[0]>>4 != 4 {
		return false
	}
	ihl := int(p[0]&0x0f) * 4
	if ihl < 20 || ihl > len(p) {
		return false
	}
	total := int(binary.BigEndian.Uint16(p[2:4]))
	return total > 0 && total <= len(p)
}

// relayFrames 把一条已握手的 VPN 连接当作帧流处理
//
// 房主与玩家的区别只在回调内容，帧的读写完全一致：
// 房主侧没人往它写帧，只有读；玩家侧会被服务器主动写帧。
func relayFrames(c *tcpConn, br *bufio.Reader, onFrame func([]byte) error) {
	defer c.closeQuietly()
	c.touch()
	var lenBuf [4]byte
	for {
		f, err := readFrame(br, lenBuf[:])
		if err != nil {
			if !c.isClosed() && !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrUnexpectedEOF) {
				logf("VPN 帧读取结束（%s）: %v", c.label, err)
			}
			return
		}
		c.touch()
		if err := onFrame(f); err != nil {
			logf("VPN 帧处理失败（%s）: %v", c.label, err)
			return
		}
	}
}

// writeWithBackpressure：对端不读时不要无限积压。
//
// 判断依据是对端还没收走的字节数（内核发送缓冲），而不是单帧长度 ——
// 之前的检查比较的是「单帧长度」，永远不可能触发，等于没做；现在按积压判断。
func writeWithBackpressure(c *tcpConn, payload []byte) bool {
	if c == nil || c.isClosed() {
		return false
	}
	if err := writeFrame(c, payload); err != nil {
		c.closeQuietly()
		return false
	}
	return true
}