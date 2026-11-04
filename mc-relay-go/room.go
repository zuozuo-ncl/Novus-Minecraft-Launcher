package main

import (
	"crypto/rand"
	"encoding/hex"
	"regexp"
	"strings"
	"sync"
	"time"
)

var (
	roomIDRe = regexp.MustCompile(`^[A-Za-z0-9]{1,16}$`)
	uuidRe   = regexp.MustCompile(`^[A-Za-z0-9_-]{1,40}$`)
	nameFilter = regexp.MustCompile(`[\x00-\x1f\x7f<>]`)
)

// room 与 Node 版的 room 对象一一对应
type room struct {
	id    string
	token string
	name  string

	mu            sync.Mutex
	control       *tcpConn // 当前生效的控制通道
	vpn           *vpnRoom
	createdAt     time.Time
	lastSeen      time.Time // 毫秒级时间戳见 lastSeenMs
	lastSeenMs    int64
	handshakeDone bool // 是否已在超时前建立控制通道
}

// peerRec 一个已加入的玩家
type peerRec struct {
	uuid string
	ip   string
	conn connCloser
}

// joinRec 一枚已签发、尚未使用的 join token
type joinRec struct {
	uuid      string
	ip        string
	expiresAt time.Time
}

// codeRec 分享给玩家的口令
type codeRec struct {
	token     string
	uuid      string
	ip        string
	expiresAt time.Time
}

// vpnRoom 一个房间的 VPN 状态
type vpnRoom struct {
	roomID string
	token  string

	mu        sync.Mutex
	host      connCloser // 房主的 VPN 长连接
	peers     map[string]*peerRec
	ipByUUID  map[string]string
	uuidByIP  map[string]string
	joinTokens map[string]*joinRec
	joinCodes  map[string]*codeRec
	closed    bool
}

var (
	roomsMu sync.Mutex
	rooms   = map[string]*room{}
)

func newVpnRoom(roomID, token string) *vpnRoom {
	return &vpnRoom{
		roomID:     roomID,
		token:      token,
		peers:      map[string]*peerRec{},
		ipByUUID:   map[string]string{},
		uuidByIP:   map[string]string{},
		joinTokens: map[string]*joinRec{},
		joinCodes:  map[string]*codeRec{},
	}
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		// crypto/rand 不会失败；真失败也没法继续
		panic(err)
	}
	return hex.EncodeToString(b)
}

func createRoom(name string) *room {
	name = strings.TrimSpace(nameFilter.ReplaceAllString(name, ""))
	if name == "" {
		name = "未命名房间"
	}
	rs := []rune(name)
	if len(rs) > 32 {
		name = string(rs[:32])
	}
	r := &room{
		id:         randHex(3), // 6 位十六进制，与 Node 版 roomId 一致
		token:      randHex(16),
		name:       name,
		createdAt:  time.Now(),
		lastSeenMs: time.Now().UnixMilli(),
	}
	roomsMu.Lock()
	rooms[r.id] = r
	roomsMu.Unlock()
	logf("创建 VPN 房间 %s", r.id)

	// 建房后必须在 handshakeTimeout 内建立控制通道，否则回收
	time.AfterFunc(handshakeTimeout, func() {
		r.mu.Lock()
		done := r.handshakeDone
		r.mu.Unlock()
		if !done {
			closeRoom(r.id, "握手超时")
		}
	})
	return r
}

func getRoom(id string) *room {
	roomsMu.Lock()
	defer roomsMu.Unlock()
	return rooms[id]
}

func roomCount() int {
	roomsMu.Lock()
	defer roomsMu.Unlock()
	return len(rooms)
}

func totalPlayers() int {
	roomsMu.Lock()
	defer roomsMu.Unlock()
	n := 0
	for _, r := range rooms {
		r.mu.Lock()
		if r.vpn != nil {
			r.vpn.mu.Lock()
			n += len(r.vpn.peers)
			r.vpn.mu.Unlock()
		}
		r.mu.Unlock()
	}
	return n
}

func closeRoom(id, reason string) {
	roomsMu.Lock()
	r, ok := rooms[id]
	if ok {
		delete(rooms, id)
	}
	roomsMu.Unlock()
	if !ok {
		return
	}
	logf("回收房间 %s (%s)", id, reason)

	r.mu.Lock()
	ctl := r.control
	r.control = nil
	v := r.vpn
	r.vpn = nil
	r.mu.Unlock()

	if ctl != nil {
		ctl.closeQuietly()
	}
	if v != nil {
		v.close("房间回收")
	}
}

// currentControl 返回当前生效的控制通道（没有则 nil）
func (r *room) currentControl() *tcpConn {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.control
}

func (v *vpnRoom) touchHost(c connCloser) bool {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.host == c
}

// allocSeat 分配一个空闲的虚拟地址
func (v *vpnRoom) allocSeat() string {
	used := map[string]bool{}
	for _, ip := range v.ipByUUID {
		used[ip] = true
	}
	for _, t := range v.joinTokens {
		used[t.ip] = true
	}
	for _, c := range v.joinCodes {
		used[c.ip] = true
	}
	for n := peerIPMin; n <= peerIPMax; n++ {
		ip := subnet + itoa(n)
		if !used[ip] {
			return ip
		}
	}
	return ""
}

func (v *vpnRoom) pruneJoinTokens(now time.Time) {
	for k, t := range v.joinTokens {
		if !t.expiresAt.After(now) {
			delete(v.joinTokens, k)
		}
	}
	for k, c := range v.joinCodes {
		if !c.expiresAt.After(now) {
			delete(v.joinCodes, k)
		}
	}
}

func (v *vpnRoom) close(reason string) {
	v.mu.Lock()
	if v.closed {
		v.mu.Unlock()
		return
	}
	v.closed = true
	host := v.host
	v.host = nil
	peers := make([]*peerRec, 0, len(v.peers))
	for _, p := range v.peers {
		peers = append(peers, p)
	}
	v.peers = map[string]*peerRec{}
	v.ipByUUID = map[string]string{}
	v.uuidByIP = map[string]string{}
	v.joinTokens = map[string]*joinRec{}
	v.joinCodes = map[string]*codeRec{}
	v.mu.Unlock()

	if host != nil {
		host.closeQuietly()
	}
	for _, p := range peers {
		p.conn.closeQuietly()
	}
	logf("VPN 房间 %s 关闭 (%s)", v.roomID, reason)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [8]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}