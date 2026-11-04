package main

import (
	"errors"
	"io"
	"bufio"
	"bytes"
	"encoding/json"
	"strings"
	"time"
)

// vpnHandshake 解析第一行握手 JSON
type vpnHandshake struct {
	Type  string `json:"type"`
	Room  string `json:"room"`
	Token string `json:"token"`
	UUID  string `json:"uuid"`
	Code  string `json:"code"`
}

// decodeRelaxed 解析 JSON，不允许尾随垃圾
func decodeRelaxed(body string, out any) error {
	dec := json.NewDecoder(strings.NewReader(body))
	if err := dec.Decode(out); err != nil {
		return err
	}
	if dec.More() {
		return errors.New("多余内容")
	}
	return nil
}

// handleVPNSession 处理房主/玩家/口令领取三种 VPN 握手
func handleVPNSession(c *tcpConn, br *bufio.Reader, hs vpnHandshake) {
	// 房间号会被拼进日志：先校验
	if !roomIDRe.MatchString(hs.Room) {
		logf("VPN 连接失败：房间号格式不正确")
		c.closeQuietly()
		return
	}
	r := getRoom(hs.Room)
	if r == nil {
		logf("VPN 连接失败：房间不存在 %s", hs.Room)
		c.closeQuietly()
		return
	}

	switch hs.Type {
	case "vpn-claim":
		handleClaim(c, r, hs.Code)
	case "vpn-host":
		handleHost(c, br, r, hs.Token)
	case "vpn-join":
		handleJoin(c, br, r, hs)
	}
}

// handleClaim 用房主分享的口令换取 token/uuid/ip（短连接，回一行 JSON 即关）
func handleClaim(c *tcpConn, r *room, code string) {
	r.mu.Lock()
	v := r.vpn
	r.mu.Unlock()

	if v == nil || !v.hasHost() {
		_, _ = c.Write(append([]byte(`{"error":"房主尚未建立 VPN 通道"}`), '\n'))
		c.closeQuietly()
		return
	}

	v.mu.Lock()
	v.pruneJoinTokens(time.Now())
	rec, ok := v.joinCodes[code]
	if !ok {
		v.mu.Unlock()
		logf("VPN 房间 %s 口令无效，拒绝加入", r.id)
		_, _ = c.Write(append([]byte(`{"error":"口令无效或已过期，请让房主重新发送邀请"}`), '\n'))
		c.closeQuietly()
		return
	}
	if len(v.peers)+len(v.joinTokens) >= maxVPNPeers {
		v.mu.Unlock()
		_, _ = c.Write(append([]byte(`{"error":"VPN 房间已满"}`), '\n'))
		c.closeQuietly()
		return
	}
	delete(v.joinCodes, code) // 一票一次
	v.joinTokens[rec.token] = &joinRec{uuid: rec.uuid, ip: rec.ip, expiresAt: time.Now().Add(joinTokenTTL)}
	v.mu.Unlock()

	logf("VPN 房间 %s 玩家凭口令换取 token（%s）", r.id, rec.ip)
	resp := map[string]string{"token": rec.token, "uuid": rec.uuid, "ip": rec.ip}
	b, _ := json.Marshal(resp)
	_, _ = c.Write(append(b, '\n'))
	c.closeQuietly()
}

func (v *vpnRoom) hasHost() bool {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.host != nil && !v.host.isClosed()
}

// handleHost 房主 VPN 长连接
func handleHost(c *tcpConn, br *bufio.Reader, r *room, token string) {
	if token == "" || r.token != token {
		logf("VPN 房主鉴权失败 %s", r.id)
		c.closeQuietly()
		return
	}

	r.mu.Lock()
	if r.vpn == nil {
		r.vpn = newVpnRoom(r.id, r.token)
	}
	v := r.vpn
	r.mu.Unlock()

	v.mu.Lock()
	old := v.host
	v.host = c
	v.closed = false
	members := make([][2]string, 0, len(v.ipByUUID))
	for uuid, ip := range v.ipByUUID {
		members = append(members, [2]string{uuid, ip})
	}
	v.mu.Unlock()

	// 替换旧房主：旧连接的 close 不会再影响新房间（isCurrentHost 判断）
	if old != nil && old != c {
		old.closeQuietly()
	}
	logf("VPN 房间 %s 房主已连接", r.id)

	// 把已在线的成员补发给新房主
	for _, m := range members {
		sendControlJSON(r, map[string]any{"type": "vpn-peer", "uuid": m[0], "ip": m[1]})
	}

	c.label = "房主 " + r.id
	defer func() {
		if !v.touchHost(c) { // 只有当前生效的那条断开才回收
			return
		}
		logf("VPN 房间 %s 房主断开，关闭所有玩家连接", r.id)
		r.mu.Lock()
		if r.vpn == v {
			r.vpn = nil
		}
		r.mu.Unlock()
		v.close("房主断开")
	}()

	relayFrames(c, br, func(f []byte) error {
		if len(f) < 1 {
			return nil
		}
		ul := int(f[0])
		if ul == 0 || ul > maxUUID || len(f) < 1+ul {
			return nil
		}
		prefix := string(f[1 : 1+ul])
		ip := f[1+ul:]
		if !isSaneIPPacket(ip) {
			return nil
		}
		// 两种前缀都认：uuid（房主自己发的）或玩家的虚拟 IP
		v.mu.Lock()
		target := ""
		if _, ok := v.ipByUUID[prefix]; ok {
			target = prefix
		} else if u, ok := v.uuidByIP[prefix]; ok {
			target = u
		}
		var peer *peerRec
		if target != "" {
			peer = v.peers[target]
		}
		v.mu.Unlock()
		if peer == nil {
			return nil
		}
		writeWithBackpressure(asTCP(peer.conn), ip)
		return nil
	})
}

// handleJoin 玩家 VPN 长连接
func handleJoin(c *tcpConn, br *bufio.Reader, r *room, hs vpnHandshake) {
	if hs.UUID == "" || !uuidRe.MatchString(hs.UUID) {
		c.closeQuietly()
		return
	}

	r.mu.Lock()
	v := r.vpn
	r.mu.Unlock()
	if v == nil || !v.hasHost() {
		logf("VPN 房间 %s 房主未连接，拒绝加入", r.id)
		c.closeQuietly()
		return
	}
	if hs.Token == "" {
		logf("VPN 房间 %s 玩家缺少 join token，拒绝", r.id)
		c.closeQuietly()
		return
	}

	v.mu.Lock()
	v.pruneJoinTokens(time.Now())
	rec, ok := v.joinTokens[hs.Token]
	if !ok || rec.uuid != hs.UUID {
		v.mu.Unlock()
		logf("VPN 房间 %s join token 无效，拒绝", r.id)
		c.closeQuietly()
		return
	}
	if len(v.peers) >= maxVPNPeers {
		v.mu.Unlock()
		logf("VPN 房间 %s 已满，拒绝", r.id)
		c.closeQuietly()
		return
	}
	if _, dup := v.peers[hs.UUID]; dup {
		v.mu.Unlock()
		logf("VPN 房间 %s uuid 重复在线，拒绝", r.id)
		c.closeQuietly()
		return
	}
	delete(v.joinTokens, hs.Token)
	ip := rec.ip
	v.peers[hs.UUID] = &peerRec{uuid: hs.UUID, ip: ip, conn: c}
	v.ipByUUID[hs.UUID] = ip
	v.uuidByIP[ip] = hs.UUID
	count := len(v.peers)
	v.mu.Unlock()

	c.label = "玩家 " + hs.UUID
	logf("VPN 房间 %s 玩家 %s 已加入 (%s，共 %d)", r.id, hs.UUID, ip, count)
	sendControlJSON(r, map[string]any{"type": "vpn-peer", "uuid": hs.UUID, "ip": ip})

	defer func() {
		v.mu.Lock()
		cur, ok := v.peers[hs.UUID]
		if !ok || cur.conn != c {
			v.mu.Unlock()
			return // 只删「自己那条」
		}
		delete(v.peers, hs.UUID)
		delete(v.ipByUUID, hs.UUID)
		delete(v.uuidByIP, ip)
		left := len(v.peers)
		v.mu.Unlock()
		sendControlJSON(r, map[string]any{"type": "vpn-peer-left", "uuid": hs.UUID})
		logf("VPN 房间 %s 玩家 %s 离开（剩 %d）", r.id, hs.UUID, left)
	}()

	relayFrames(c, br, func(ipPacket []byte) error {
		if !isSaneIPPacket(ipPacket) {
			return nil
		}
		v.mu.Lock()
		host := v.host
		fromIP := v.ipByUUID[hs.UUID]
		v.mu.Unlock()
		if host == nil || fromIP == "" {
			return nil
		}
		payload, err := frameToHost(fromIP, ipPacket)
		if err != nil {
			return nil
		}
		writeWithBackpressure(asTCP(host), payload)
		return nil
	})
}

// asTCP 把 connCloser 还原为 *tcpConn（peerRec.host 存的都是 tcpConn）
func asTCP(c connCloser) *tcpConn {
	t, _ := c.(*tcpConn)
	return t
}

// issueJoinToken 房主要求为某位玩家签发邀请。与 Node 版行为一致。
func issueJoinToken(r *room, id int64) {
	r.mu.Lock()
	v := r.vpn
	r.mu.Unlock()

	if v == nil || !v.hasHost() {
		sendControlJSON(r, map[string]any{"type": "vpn-token-error", "id": id, "error": "房主尚未建立 VPN 通道"})
		return
	}

	v.mu.Lock()
	defer v.mu.Unlock()
	if len(v.peers)+len(v.joinTokens) >= maxVPNPeers {
		sendControlJSON(r, map[string]any{"type": "vpn-token-error", "id": id, "error": "VPN 房间已满"})
		return
	}
	v.pruneJoinTokens(time.Now())
	ip := v.allocSeat()
	if ip == "" {
		sendControlJSON(r, map[string]any{"type": "vpn-token-error", "id": id, "error": "没有空闲的虚拟地址"})
		return
	}

	token := randHex(16)
	code := randHex(4) // 8 位，玩家粘贴用
	uuid := "p" + randHex(8)
	exp := time.Now().Add(joinTokenTTL)
	v.joinCodes[code] = &codeRec{token: token, uuid: uuid, ip: ip, expiresAt: exp}

	logf("VPN 房间 %s 为玩家 #%d 分配地址 %s", r.id, id, ip)
	sendControlJSON(r, map[string]any{
		"type": "vpn-token", "id": id, "token": token, "uuid": uuid, "ip": ip, "code": code,
	})
}

// sendControlJSON 往控制通道写一行 JSON
func sendControlJSON(r *room, v any) bool {
	ctl := r.currentControl()
	if ctl == nil || ctl.isClosed() {
		return false
	}
	return writeJSONLine(ctl, v)
}

// attachControl 接管控制通道：心跳 ping/pong + vpn-token 信令
func attachControl(c *tcpConn, br *bufio.Reader, hs vpnHandshake) {
	r := getRoom(hs.Room)
	if r == nil || r.token != hs.Token {
		c.closeQuietly()
		return
	}

	r.mu.Lock()
	old := r.control
	r.control = c
	r.handshakeDone = true
	r.lastSeenMs = time.Now().UnixMilli()
	r.mu.Unlock()

	if old != nil && old != c {
		old.closeQuietly()
	}

	logf("房间 %s 控制通道就绪", r.id)
	_, _ = c.Write([]byte("{\"type\":\"hello-ok\"}\n"))

	// 读循环：逐行处理，控制通道关闭则回收房间（只有「当前生效」的那条）
	defer func() {
		r.mu.Lock()
		cur := r.control
		r.mu.Unlock()
		if cur == c {
			closeRoom(r.id, "控制通道断开")
		}
		c.closeQuietly()
	}()

	for {
		_ = c.SetReadDeadline(time.Now().Add(heartbeatInterval * (heartbeatMiss + 1)))
		line, err := br.ReadBytes('\n')
		if err != nil {
			return
		}
		if len(line) > controlMaxBuffer {
			return
		}
		r.mu.Lock()
		r.lastSeenMs = time.Now().UnixMilli()
		r.mu.Unlock()

		var m struct {
			Type string `json:"type"`
			ID   *int64 `json:"id"`
		}
		if err := json.Unmarshal(bytes.TrimSpace(line), &m); err != nil {
			continue
		}
		switch m.Type {
		case "ping":
			_, _ = c.Write([]byte("{\"type\":\"pong\"}\n"))
		case "vpn-token":
			if m.ID != nil {
				issueJoinToken(r, *m.ID)
			}
		}
	}
}

// 确保 io 被使用（ReadBytes 的返回类型）
var _ = io.EOF