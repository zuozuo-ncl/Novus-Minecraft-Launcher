package main

import (
	"net"
	"errors"
	"crypto/subtle"
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"time"
)

var errNoLine = errors.New("no line")

// ---------------- 限流 ----------------

type rateRec struct {
	count int
	start time.Time
}

var (
	rateMu sync.Mutex
	rates  = map[string]*rateRec{}
)

func rateLimited(ip string) bool {
	rateMu.Lock()
	defer rateMu.Unlock()
	now := time.Now()
	rec := rates[ip]
	if rec == nil || now.Sub(rec.start) > hostRateWindow {
		rates[ip] = &rateRec{count: 1, start: now}
		return false
	}
	rec.count++
	return rec.count > hostRateLimit
}

// ---------------- HTTP 工具 ----------------

func corsOrigin(req *http.Request) string {
	o := req.Header.Get("Origin")
	if o != "" && cfg.CORSOrigs[o] {
		return o
	}
	if envStr("CORS_ALLOW_ALL", "") == "1" {
		return "*"
	}
	return ""
}

func writeJSON(w http.ResponseWriter, req *http.Request, code int, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		http.Error(w, "{}", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Vary", "Origin")
	if o := corsOrigin(req); o != "" {
		w.Header().Set("Access-Control-Allow-Origin", o)
	}
	w.WriteHeader(code)
	_, _ = w.Write(b)
}

func apiKeyOK(req *http.Request) bool {
	if cfg.APIKey == "" {
		return true
	}
	key := req.Header.Get("X-Api-Key")
	return subtle.ConstantTimeCompare([]byte(key), []byte(cfg.APIKey)) == 1
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return strings.TrimPrefix(host, "::ffff:")
}

// ---------------- 心跳 ----------------

func heartbeatLoop() {
	t := time.NewTicker(heartbeatInterval)
	defer t.Stop()
	for range t.C {
		now := time.Now()
		var stale []string
		roomsMu.Lock()
		for id, r := range rooms {
			r.mu.Lock()
			hasCtrl := r.control != nil
			last := r.lastSeenMs
			r.mu.Unlock()
			if hasCtrl && now.UnixMilli()-last > int64(heartbeatInterval*heartbeatMiss/time.Millisecond) {
				stale = append(stale, id)
			}
		}
		roomsMu.Unlock()
		for _, id := range stale {
			closeRoom(id, "心跳超时")
		}

		rateMu.Lock()
		for ip, rec := range rates {
			if now.Sub(rec.start) > hostRateWindow*2 {
				delete(rates, ip)
			}
		}
		rateMu.Unlock()
	}
}
