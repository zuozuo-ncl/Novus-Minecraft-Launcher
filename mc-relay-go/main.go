package main

import (
	"fmt"
	"encoding/json"
	"flag"
	"log"
	"os"
	"strconv"
	"time"
)

const (
	magic = "MCRELAY"

	maxRooms = 100

	handshakeTimeout = 30 * time.Second // 申请房间后必须在此时间内建立控制通道
	heartbeatInterval = 5 * time.Second
	heartbeatMiss     = 3

	hostRateLimit  = 10
	hostRateWindow = 60 * time.Second

	// VPN 相关
	maxVPNPeers      = 10
	maxFrame         = 65535 + 64   // 与客户端 MAX_FRAME 一致（IPv4 报文最大 65535 + uuid 前缀）
	maxUUID          = 40
	joinTokenTTL     = 120 * time.Second
	maxIPPacket      = 65535 + 64
	vpnIdleTimeout   = 60 * time.Second
	maxHeaderLine    = 1024       // 握手行上限
	sniffFirstBytes  = 4          // 至少读到 4 字节才判定
	sniffTimeout     = 10 * time.Second
	controlMaxBuffer = 64 * 1024 // 控制通道单行累积上限
	apiMaxBody       = 4096

	subnet     = "10.77.0."
	peerIPMin  = 2
	peerIPMax  = 9
)

type config struct {
	HTTPPort  int
	VPNPort   int
	Host      string
	APIKey    string
	MaxRooms  int
	CORSOrigs map[string]bool
}

var cfg config

var startTime = time.Now()

func main() {
	flag.IntVar(&cfg.HTTPPort, "http-port", envInt("HTTP_PORT", 8080), "HTTP API 端口")
	flag.IntVar(&cfg.VPNPort, "vpn-port", envInt("VPN_PORT", 0), "VPN 帧端口（默认 http-port+1）")
	flag.StringVar(&cfg.Host, "host", envStr("HOST", "0.0.0.0"), "监听地址")
	flag.StringVar(&cfg.APIKey, "api-key", envStr("API_KEY", ""), "写接口密钥（留空则开放）")
	flag.IntVar(&cfg.MaxRooms, "max-rooms", envInt("MAX_ROOMS", maxRooms), "最大房间数")
	flag.Parse()

	if cfg.VPNPort == 0 {
		cfg.VPNPort = cfg.HTTPPort + 1
	}
	cfg.CORSOrigs = map[string]bool{}
	for _, o := range splitComma(envStr("CORS_ORIGINS", "")) {
		cfg.CORSOrigs[o] = true
	}

	log.SetFlags(log.LstdFlags)
	logf("VPN 中继（Go）启动：HTTP %s:%d，VPN 帧 %s:%d，最多 %d 个房间",
		cfg.Host, cfg.HTTPPort, cfg.Host, cfg.VPNPort, cfg.MaxRooms)
	if cfg.APIKey == "" {
		logf("[警告] 未配置 API_KEY：/api/host 与 /api/rooms 对任何来源开放")
	}

	go heartbeatLoop()

	if err := serveVPN(); err != nil {
		fmt.Fprintln(os.Stderr, "VPN 端口监听失败:", err)
		os.Exit(1)
	}
	// serveHTTP 阻塞（内部起 accept 循环）
	if err := serveHTTP(); err != nil {
		fmt.Fprintln(os.Stderr, "HTTP 监听失败:", err)
		os.Exit(1)
	}
}

func logf(format string, a ...any) {
	log.Printf(format, a...)
}

func envStr(k, d string) string {
	if v, ok := os.LookupEnv(k); ok && v != "" {
		return v
	}
	return d
}

func envInt(k string, d int) int {
	if v, ok := os.LookupEnv(k); ok && v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return d
}

func splitComma(s string) []string {
	var out []string
	cur := ""
	for _, c := range s {
		if c == ',' {
			if cur != "" {
				out = append(out, cur)
			}
			cur = ""
			continue
		}
		cur += string(c)
	}
	if cur != "" {
		out = append(out, cur)
	}
	return out
}

// writeJSONLine 往控制通道写一行 JSON（带换行）
func writeJSONLine(w interface{ Write([]byte) (int, error) }, v any) bool {
	b, err := json.Marshal(v)
	if err != nil {
		return false
	}
	_, err = w.Write(append(b, '\n'))
	return err == nil
}