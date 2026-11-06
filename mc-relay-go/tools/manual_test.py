#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
中继手动测试：依次走一遍 HTTP 建房间 -> 房主控制通道 -> 房主 VPN 通道 -> 签发口令 -> 玩家 claim
每一步都打印实际收到的东西，哪一步断了一目了然。

用法：
    python tools/manual_test.py --http 192.168.9.9:8080 --vpn 192.168.9.9:8081 --api-key <KEY>
    python tools/manual_test.py --http 45.64.229.50:41181 --vpn 45.64.229.50:47098 --api-key <KEY>
    python tools/manual_test.py --vpn-only 192.168.9.9:8081          # 只测 VPN 端口有没有回包

依赖：Python 3.8+，requests
"""

import argparse
import json
import socket
import sys
import threading
import time

try:
    import requests
except ImportError:
    print('缺少 requests：python -m pip install requests')
    sys.exit(2)

if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

UA = 'MCLauncher-manual-test/1.0'


def parse_ep(s):
    host, _, port = s.rpartition(':')
    return host, int(port)


def ok(msg):
    print('    [OK]   ' + msg)


def bad(msg):
    print('    [FAIL] ' + msg)


def vpn_connect(host, port):
    return socket.create_connection((host, port), timeout=10)


MAGIC = b'MCRELAY'


def send_line(sock, obj, magic=True):
    """发一行握手 / 控制消息。

    首行握手必须带魔数且与 JSON 之间不带空格：MCRELAY{"type":...}\\n
    （带空格时前缀判断不过，会被当成非法 HTTP 请求回 400）
    握手之后在同一条控制通道上继续发的消息则**不带**魔数，直接是 JSON 行。
    """
    line = json.dumps(obj, separators=(',', ':')).encode()
    sock.sendall((MAGIC + line if magic else line) + b'\n')


def recv_line(sock, timeout=8, discard=0):
    """读一行（含 '\\n'），超时或断开返回 None；discard 表示先跳过几行"""
    sock.settimeout(timeout)
    buf = b''
    end = time.time() + timeout
    try:
        while b'\n' not in buf:
            c = sock.recv(1)
            if not c:
                return None
            buf += c
    except socket.timeout:
        return None
    if discard:
        return recv_line(sock, max(0.5, end - time.time()), discard - 1)
    return buf.decode('utf-8', 'replace').strip()


def read_json_line(sock, timeout=8, discard=0):
    """跳过前 discard 行以及非 JSON 行，返回第一条 JSON 行（dict）"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        line = recv_line(sock, max(1, timeout), discard)
        discard = 0
        if line is None:
            return None
        try:
            m = json.loads(line)
        except Exception:
            continue
        if isinstance(m, dict):
            return m
    return None


# ---------------------------------------------------------------------------
# 1) 前置检查：VPN 端口是否按中继协议应答
# ---------------------------------------------------------------------------
def probe_vpn(host, port, timeout=15):
    """用 vpn-claim 探测 VPN 端口。

    一个正常的中继对 claim 一定回一行 JSON（要么 token，要么 error）；
    如果什么也不回，说明这一层不是中继（被代理吞了 / 端口接错了）。
    """
    print('\n==> 测试 VPN 端口 %s:%d' % (host, port))
    try:
        s = vpn_connect(host, port)
    except Exception as e:
        bad('TCP 连接失败：%s' % e)
        return False
    ok('TCP 连接建立')
    send_line(s, {'type': 'vpn-claim', 'room': 'probe0000', 'code': 'deadbeef'})
    # 服务端日志出现「VPN 连接失败：房间不存在 probe0000」就说明这一行已经送达中继，
    # 这时回包晚到一点是正常的（隧道 RTT），多等一会儿再判定
    line = recv_line(s, max(timeout, 15))
    s.close()
    if line is None:
        bad('发送 MCRELAY 握手后没有收到任何回包（对端直接关闭或静默）')
        print('          正常的中继这时应该回：{"error":"房间不存在"}（房间 probe0000 是故意编的）')
        print('          服务端日志里有「VPN 连接失败：房间不存在 probe0000」= 握手已送达，只是回包没回来')
        print('          可以做对照：再对同一个端点发 vpn-claim 并提供真实房间号，或直接跑完整流程')
        return False
    ok('收到回包：%s' % line)
    return True


# ---------------------------------------------------------------------------
# 2) 完整联机流程
# ---------------------------------------------------------------------------
def full_test(http_ep, vpn_ep, api_key, wait_host=8):
    hh, hp = parse_ep(http_ep)
    vh, vp = parse_ep(vpn_ep)
    base = 'http://%s:%d' % (hh, hp)
    hdr = {'X-Api-Key': api_key, 'User-Agent': UA}

    print('\n==> 1/5 HTTP 建房间 %s/api/host' % base)
    r = requests.post(base + '/api/host', json={}, headers=hdr, timeout=20)
    print('    HTTP %d  %s' % (r.status_code, r.text[:200]))
    if r.status_code != 200:
        bad('建房间失败（401=密钥不对，429=限流，503=房间满）')
        return False
    info = r.json()
    room, token = info['room'], info['token']
    ok('房间 %s' % room)

    print('\n==> 2/5 房主控制通道（HTTP 端口上的 MCRELAY hello）')
    ctl = vpn_connect(hh, hp)
    send_line(ctl, {'type': 'hello', 'room': room, 'token': token})
    m = read_json_line(ctl, 12)
    print('    收到：%r' % m)
    if not m or m.get('type') != 'hello-ok':
        bad('控制通道没有回 hello-ok —— 房主会停留在「连接中继中」')
        return False
    ok('控制通道正常')

    print('\n==> 3/5 房主 VPN 通道 %s:%d（vpn-host）' % (vh, vp))
    hostsock = vpn_connect(vh, vp)
    send_line(hostsock, {'type': 'vpn-host', 'room': room, 'token': token})
    # 房主通道正常是「静默保持」：不会回任何东西，也不该被断开
    hostsock.settimeout(3)
    try:
        d = hostsock.recv(100)
        if d == b'':
            bad('房主 VPN 通道被立即关闭 —— 8081 这层没有把连接交给中继')
            return False
    except socket.timeout:
        ok('房主 VPN 通道保持打开（正常：静默长连接）')

    print('\n==> 4/5 通过控制通道签发口令（vpn-token）')
    send_line(ctl, {'type': 'vpn-token', 'id': 1}, magic=False)
    code = None
    t0 = time.time()
    while time.time() - t0 < 10:
        m = read_json_line(ctl, 5)
        if m is None:
            break
        print('    控制通道收到：%s' % json.dumps(m, ensure_ascii=False))
        if m.get('type') == 'vpn-token':
            code = m.get('code')
            break
        if m.get('type', '').endswith('error'):
            break
    if not code:
        bad('没有拿到口令 —— 房主进入 VPN 的握手没成功')
        return False
    ok('口令 %s' % code)

    print('\n==> 5/5 玩家 claim 口令')
    p = vpn_connect(vh, vp)
    send_line(p, {'type': 'vpn-claim', 'room': room, 'code': code})
    m = read_json_line(p, 10)
    print('    收到：%r' % m)
    if not m:
        bad('claim 没有回包 —— 玩家加入会失败')
        return False
    if m.get('token'):
        ok('claim 成功：虚拟 IP %s' % m.get('ip'))
    else:
        bad('claim 被拒：%s' % m.get('error'))
        return False

    print('\n全部通过：%s:%d（HTTP）+ %s:%d（VPN）可以用于联机' % (hh, hp, vh, vp))
    for s in (p, hostsock, ctl):
        try:
            s.close()
        except Exception:
            pass
    return True


def main():
    ap = argparse.ArgumentParser(description='mc-relay-go 手动测试')
    ap.add_argument('--http', help='HTTP 端点 host:port，如 192.168.9.9:8080')
    ap.add_argument('--vpn', help='VPN 端点 host:port，如 192.168.9.9:8081')
    ap.add_argument('--api-key', default='', help='中继 API_KEY')
    ap.add_argument('--vpn-only', help='只测 VPN 端口是否按中继协议应答 host:port')
    args = ap.parse_args()

    if args.vpn_only:
        h, p = parse_ep(args.vpn_only)
        sys.exit(0 if probe_vpn(h, p) else 1)
    if not args.http or not args.vpn:
        ap.print_help()
        sys.exit(2)
    sys.exit(0 if full_test(args.http, args.vpn, args.api_key) else 1)


if __name__ == '__main__':
    main()