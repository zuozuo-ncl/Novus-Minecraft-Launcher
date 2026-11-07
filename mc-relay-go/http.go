package main

import (
	"fmt"
	"bufio"
	"bytes"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"runtime"
	"strings"
	"time"
)

// ---------------------------------------------------------------
//  HTTP 端口：控制通道与普通 HTTP 共用
// ---------------------------------------------------------------

// peekResult 首行预读结果
type peekResult struct {
	line []byte   // 读到 '\n' 为止（含 '\n'）
	rest *bufio.Reader
}

// peekLine 从连接里读到第一个 '\n'（最多 maxHeaderLine 字节）
func peekLine(c *tcpConn) (*peekResult, error) {
	br := bufio.NewReaderSize(c, 4096)
	_ = c.SetReadDeadline(time.Now().Add(sniffTimeout))
	line, err := br.ReadBytes('\n')
	_ = c.SetReadDeadline(time.Time{})
	if err != nil {
		return nil, err
	}
	if len(line) > maxHeaderLine {
		return nil, errNoLine
	}
	return &peekResult{line: line, rest: br}, nil
}

// sniffAndDispatchWithLine 已经读到首行时的分发
func sniffAndDispatchWithLine(c *tcpConn, p *peekResult, isVPNSide bool) {
	text := strings.TrimSpace(string(p.line))
	if !strings.HasPrefix(text, magic) {
		c.closeQuietly()
		return
	}
	body := text[len(magic):]

	var hs vpnHandshake
	if err := decodeRelaxed(body, &hs); err != nil {
		c.closeQuietly()
		return
	}

	switch hs.Type {
	case "hello":
		if isVPNSide {
			c.closeQuietly()
			return
		}
		attachControl(c, p.rest, hs)
	case "vpn-host", "vpn-join", "vpn-claim", "vpn-probe":
		// HTTP 端口与 VPN 帧端口都接受这几类握手：经内网穿透部署时只需要
		// 对外暴露一个端口（客户端会优先连 HTTP 端口）。vpn-probe 只回一行
		// pong 即关（见 handleVPNSession），不会接管任何连接。
		// 以前这条注释写的是 attachControl 按端口顺序拒绝 hello —— 实际并不存在
		// 这样的逻辑，hello 在 isVPNSide 时直接断开，见上面的 case "hello"。
		handleVPNSession(c, p.rest, hs)
	default:
		c.closeQuietly()
	}
}

// 把预读字节放回连接，交给 net/http 解析
func replayConn(c *tcpConn, p *peekResult) net.Conn {
	r := io.MultiReader(bytes.NewReader(p.line), p.rest)
	return &prefixedConn{Conn: c.Conn, r: io.NopCloser(r)}
}

// prefixedConn 让 net/http 读到「已预读的首行 + 剩余数据」
type prefixedConn struct {
	net.Conn
	r io.Reader
}

func (p *prefixedConn) Read(b []byte) (int, error) { return p.r.Read(b) }

// oneConnListener 只返回一条连接一次的 Listener
type oneConnListener struct {
	conn net.Conn
	done bool
}

func (l *oneConnListener) Accept() (net.Conn, error) {
	if l.done {
		return nil, io.EOF
	}
	l.done = true
	return l.conn, nil
}
func (l *oneConnListener) Close() error   { return nil }
func (l *oneConnListener) Addr() net.Addr { return l.conn.LocalAddr() }

// serveHTTP 启动 HTTP 端口的 raw 嗅探器
func serveHTTP() error {
	ln, err := net.Listen("tcp", net.JoinHostPort(cfg.Host, itoa(cfg.HTTPPort)))
	if err != nil {
		return err
	}
	mux := http.NewServeMux()
	registerHTTPRoutes(mux)

	go func() {
		for {
			raw, err := ln.Accept()
			if err != nil {
				return
			}
			go func(raw net.Conn) {
				tc := newTCPConn(raw, "http")
				if tcp, ok := raw.(*net.TCPConn); ok {
					_ = tcp.SetNoDelay(true)
				}
				p, err := peekLine(tc)
				if err != nil {
					tc.closeQuietly()
					return
				}
				line := strings.TrimSpace(string(p.line))
				if strings.HasPrefix(line, magic) {
					sniffAndDispatchWithLine(tc, p, false)
					return
				}
				hc := replayConn(tc, p)
				if tcp, ok := hc.(*prefixedConn).Conn.(*net.TCPConn); ok {
					_ = tcp.SetNoDelay(true)
				}
				srv := &http.Server{Handler: mux, ReadHeaderTimeout: 15 * time.Second,
					ReadTimeout: 30 * time.Second, WriteTimeout: 60 * time.Second}
				_ = srv.Serve(&oneConnListener{conn: hc})
			}(raw)
		}
	}()
	logf("VPN 中继 HTTP 监听 %s:%d  最多 %d 个房间", cfg.Host, cfg.HTTPPort, cfg.MaxRooms)
	select {}
}

// ---------------------------------------------------------------
//  VPN 帧端口：只接受 VPN 握手
// ---------------------------------------------------------------

func serveVPN() error {
	ln, err := net.Listen("tcp", net.JoinHostPort(cfg.Host, itoa(cfg.VPNPort)))
	if err != nil {
		return err
	}
	logf("VPN 帧端口监听 %s:%d", cfg.Host, cfg.VPNPort)

	go func() {
		for {
			raw, err := ln.Accept()
			if err != nil {
				return
			}
			go func(raw net.Conn) {
				tc := newTCPConn(raw, "vpn")
				if tcp, ok := raw.(*net.TCPConn); ok {
					_ = tcp.SetNoDelay(true)
				}
				p, err := peekLine(tc)
				if err != nil {
					tc.closeQuietly()
					return
				}
				line := strings.TrimSpace(string(p.line))
				if !strings.HasPrefix(line, magic) {
					// 这个端口只谈 VPN；普通 HTTP 直接断开
					tc.closeQuietly()
					return
				}
				sniffAndDispatchWithLine(tc, p, true)
			}(raw)
		}
	}()
	return nil
}

// ---------------------------------------------------------------
//  HTTP 路由（供 serveHTTP 注册）
// ---------------------------------------------------------------

func registerHTTPRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/host", handleAPIHost)
	mux.HandleFunc("/api/rooms", handleAPIRooms)
	mux.HandleFunc("/health", handleHealth)
	mux.HandleFunc("/", func(w http.ResponseWriter, req *http.Request) {
		writeJSON(w, req, http.StatusNotFound, map[string]string{"error": "not found"})
	})
}

func handleAPIHost(w http.ResponseWriter, req *http.Request) {
	if req.Method == http.MethodOptions {
		writePreflight(w, req)
		return
	}
	if req.Method != http.MethodPost {
		writeJSON(w, req, http.StatusNotFound, map[string]string{"error": "not found"})
		return
	}
	if !apiKeyOK(req) {
		writeJSON(w, req, http.StatusUnauthorized, map[string]string{"error": "无效的 API 密钥"})
		return
	}
	body, err := io.ReadAll(io.LimitReader(req.Body, apiMaxBody+1))
	if err != nil || len(body) > apiMaxBody {
		writeJSON(w, req, http.StatusBadRequest, map[string]string{"error": "请求体过大或不完整"})
		return
	}
	var in struct {
		Name string `json:"name"`
	}
	var probe map[string]any
	if json.Unmarshal(body, &probe) != nil || probe == nil {
		probe = map[string]any{}
	} else {
		if v, ok := probe["name"].(string); ok {
			in.Name = v
		}
	}

	ip := clientIP(req)
	if rateLimited(ip) {
		writeJSON(w, req, http.StatusTooManyRequests, map[string]string{"error": "请求过于频繁，请稍后再试"})
		return
	}
	if roomCount() >= cfg.MaxRooms {
		writeJSON(w, req, http.StatusServiceUnavailable, map[string]string{"error": "房间数量已达上限，请稍后再试"})
		return
	}
	r := createRoom(in.Name)
	writeJSON(w, req, http.StatusOK, map[string]string{"room": r.id, "token": r.token})
}

func handleAPIRooms(w http.ResponseWriter, req *http.Request) {
	if req.Method == http.MethodOptions {
		writePreflight(w, req)
		return
	}
	if !apiKeyOK(req) {
		writeJSON(w, req, http.StatusUnauthorized, map[string]string{"error": "无效的 API 密钥"})
		return
	}
	type item struct {
		Room       string `json:"room"`
		Name       string `json:"name"`
		Players    int    `json:"players"`
		MaxPlayers int    `json:"maxPlayers"`
		Uptime     int64  `json:"uptime"`
	}
	list := make([]item, 0, 8)
	roomsMu.Lock()
	for _, r := range rooms {
		r.mu.Lock()
		hasCtrl := r.control != nil
		v := r.vpn
		created := r.createdAt
		r.mu.Unlock()
		if !hasCtrl {
			continue
		}
		players := 0
		if v != nil {
			v.mu.Lock()
			players = len(v.peers)
			v.mu.Unlock()
		}
		list = append(list, item{
			Room: r.id, Name: r.name, Players: players, MaxPlayers: maxVPNPeers,
			Uptime: int64(time.Since(created) / time.Second),
		})
	}
	roomsMu.Unlock()
	writeJSON(w, req, http.StatusOK, map[string]any{"rooms": list})
}

func handleHealth(w http.ResponseWriter, req *http.Request) {
	writeJSON(w, req, http.StatusOK, map[string]any{
		"ok":      true,
		"rooms":   roomCount(),
		"players": totalPlayers(),
		"uptime":  int64(time.Since(startTime) / time.Second),
		"rss":     currentRSSMB(),
	})
}

func writePreflight(w http.ResponseWriter, req *http.Request) {
	w.Header().Set("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-Api-Key")
	w.Header().Set("Vary", "Origin")
	if o := corsOrigin(req); o != "" {
		w.Header().Set("Access-Control-Allow-Origin", o)
	}
	w.WriteHeader(http.StatusNoContent)
}

func currentRSSMB() int {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return int(m.Sys / 1048576)
}

func init() { _ = fmt.Sprint }