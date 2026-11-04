# mc-relay-go —— 虚拟局域网联机中继（Go 版）

与 `../mc-relay`（Node 版）协议完全兼容，Java 启动器不需要改任何代码。
选它的理由：单文件二进制、无运行时依赖，服务器上不用装 Node。

## 实现说明（原 main.go 顶部注释）

`Package main` 实现「虚拟局域网」MC 联机中继（Go 版）。协议与 `../mc-relay`（Node 版）完全一致，Java 启动器无需改动：

```
房主启动器 ──① POST /api/host ──────────────► 中继   {room, token}
           ──② 控制通道 hello (HTTP 端口) ────► 中继   hello-ok / vpn-token / vpn-peer
           ──③ VPN 长连接 vpn-host (VPN 端口) ─► 中继
玩家启动器 ──④ vpn-claim 口令换 token ────────► 中继   {token, uuid, ip}
           ──⑤ VPN 长连接 vpn-join ───────────► 中继
```

帧格式（小端）：

```
房主 → 中继： u32 len | u8 uuidLen | uuid | ipPacket
中继 → 房主： u32 len | u8 uuidLen | uuid | ipPacket
玩家 ↔ 中继： u32 len | ipPacket
```

`ipPacket` 是裸 IPv4 报文。房主方向的前缀：下行用玩家虚拟 IP，上行同样用玩家虚拟 IP（服务端按自己的映射补上，不信任客户端自报）。

协议常量集中在 `main.go` 的 `const` 块，与 Node 版保持一致；改动前请先改 Node 版并同步两端。

## 文件说明

| 文件 | 内容 |
|---|---|
| `main.go` | 启动参数、全局配置、协议常量、日志 |
| `wire.go` | 帧编解码（u32 长度前缀）、IPv4 报文校验、空闲看门狗 |
| `room.go` | 房间与虚拟地址分配（10.77.0.2–9）、生命周期与回收 |
| `vpn.go` | `vpn-host` / `vpn-join` / `vpn-claim` 握手、控制通道信令、签发邀请 |
| `http.go` | HTTP 路由、HTTP 端口上的控制通道嗅探与分流 |
| `helpers.go` | 限流、CORS、心跳超时清理 |

## 协议

```
房主启动器 ──① POST /api/host ─────────────► 中继   {room, token}
           ──② 控制通道 hello（HTTP 端口）───► 中继   hello-ok / vpn-token / vpn-peer
           ──③ VPN 长连接 vpn-host（VPN 端口）► 中继
玩家启动器 ──④ vpn-claim 口令换 token ────────► 中继   {token, uuid, ip}
           ──⑤ VPN 长连接 vpn-join ───────────► 中继
```

帧格式（小端）：

| 方向 | 帧 |
|---|---|
| 房主 → 中继 | `u32 len \| u8 uuidLen \| prefix \| ipPacket` |
| 中继 → 房主 | `u32 len \| u8 uuidLen \| 玩家虚拟 IP \| ipPacket` |
| 玩家 ↔ 中继 | `u32 len \| ipPacket` |

`prefix` 两种都接受：玩家 uuid 或玩家虚拟 IP。服务器发给房主时统一用玩家虚拟 IP。

## 构建

```bash
go build -o relay.exe .                                   # 本机
GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o dist/relay-linux-amd64 .
GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o dist/relay-linux-arm64 .
```

## 运行参数（也可用同名环境变量）

| 参数 | 默认 | 说明 |
|---|---|---|
| `-http-port` | 8080 | HTTP API + 房主控制通道 |
| `-vpn-port` | http-port+1 | VPN 帧端口 |
| `-host` | 0.0.0.0 | 监听地址 |
| `-api-key` | 空 | 写接口密钥；空 = 开放（会打警告） |
| `-max-rooms` | 100 | 最大房间数 |

## 部署

```bash
SERVER_IP=1.2.3.4 bash deploy-go.sh
```

脚本会：上传二进制 → 生成/沿用 API_KEY 写入 `.env` → 装 systemd 服务 → 自检 `/health`。
记得在云服务器安全组放行 **HTTP_PORT 与 VPN_PORT 两个 TCP 端口**。

## 测试

`../mc-relay/test-regression.js` 是同一套用例，可以分别打两版实现：

```bash
cd ../mc-relay
node test-regression.js                                                    # Node 版
RELAY_BIN="$(pwd)/../mc-relay-go/dist/relay-linux-amd64" node test-regression.js   # Go 版（Linux 上）
```

Windows 下用 `dist` 里的 linux 二进制不行，先 `go build -o relay.exe .` 再
`RELAY_BIN=$(cygpath -w ../mc-relay-go/relay.exe)`。

当前结果：两版都是 **25/25 通过**。

### 线上联调

`verify-live.go.txt` 是一份对**已部署服务器**做端到端验证的程序（改名成 `.go` 后
`go run` 即可，需把文件开头的 `apiKey` 换成服务器上的值）。覆盖 13 项：
建房、控制通道、签发邀请、口令领取与一次性、玩家加入、双向帧转发、重复加入被拒、`/health`。
