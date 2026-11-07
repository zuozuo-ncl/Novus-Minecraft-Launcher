#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把引导器打包并部署到 Cloudflare。

用法：
    python tools/deploy-cf.py                    # 打包并部署到生产
    python tools/deploy-cf.py --no-deploy        # 只打包，不联网
    python tools/deploy-cf.py --dry-run          # 显示计划，不打包也不上传
    python tools/deploy-cf.py --init             # 生成配置模板 tools/deploy-cf.json

打包内容（两层）：
    1. 编译产物先改名：MCLauncherBootstrap.exe -> NCL.exe
                        MCLauncherBootstrap_x86.exe -> NCL_x86.exe
    2. 每个 exe 单独压成同名 zip：NCL.zip（内含 NCL.exe）
                                   NCL_x86.zip（内含 NCL_x86.exe）
    3. 两个 zip + 空白 index.html 放进大包 mc-launcher-bootstrap.zip：
           mc-launcher-bootstrap.zip
             ├── NCL.zip
             ├── NCL_x86.zip
             └── index.html
    4. 本地产物（dist/）：
           mc-launcher-bootstrap.zip          大包
           mc-launcher-bootstrap.zip.sha256   大包哈希
           NCL.zip / NCL_x86.zip              单独分发用
    5. 上传到 Cloudflare：大包 + 两个小 zip 都作为静态资源上传
       （线上可直接按架构单独下载 NCL.zip / NCL_x86.zip）

部署方式：Cloudflare Workers Static Assets（与 wrangler 相同的两步流程）
    1. 算出所有文件的 SHA-256（文件名 = 内容哈希截断后的 hex）
    2. POST /accounts/<id>/workers/scripts/<name>/assets-upload-session
       提交清单，返回 JWT 与需要上传的 buckets
    3. POST /accounts/<id>/workers/assets/upload?base64=true
       用 JWT 上传尚未存在的文件，返回发布用 JWT
    4. PUT  /accounts/<id>/workers/scripts/<name>
            ?include_subdomain_availability=true&excludeScript=true
       发布清单，assets 模式生效
    5. 回读线上 url，确认页面已生效

    （旧脚本用的 /workers/assets/v2/upload 目前在 API 上返回 404，已弃用。）

配置优先级：命令行 > 环境变量 > tools/deploy-cf.json
环境变量：
    CLOUDFLARE_API_TOKEN    必需，权限：Workers Scripts:Edit（Account 级）
    CLOUDFLARE_ACCOUNT_ID   必需
    CF_PROJECT              可选，Worker 名，默认 mc-launcher-bootstrap
    BOOTSTRAPPER_VERSION    可选，版本标识（目前只用于日志与计划输出）

依赖：Python 3.8+，requests
"""

import argparse
import base64
import hashlib
import io
import json
import os
import shutil
import sys
import tempfile
import time
import zipfile

if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

try:
    import requests
except ImportError:  # pragma: no cover
    print('缺少 requests，请先运行：python -m pip install requests')
    sys.exit(2)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG_PATH = os.path.join(ROOT, 'tools', 'deploy-cf.json')
BOOT_BIN = os.path.join(ROOT, 'bootstrapper', 'bin')
DIST_DIR = os.path.join(ROOT, 'dist')

EXE_X64 = 'NCL.exe'
EXE_X86 = 'NCL_x86.exe'
# 编译产物在 bootstrapper/bin 下的原名（打包时改名为上面的名字）
SRC_X64 = 'MCLauncherBootstrap.exe'
SRC_X86 = 'MCLauncherBootstrap_x86.exe'
INDEX_HTML = 'index.html'

DEFAULT_CONFIG = {
    'cloudflare': {
        'account_id': '',
        'api_token': '',
        'project': 'mc-launcher-bootstrap',
        # 绑定到该 worker 的自定义域名（用于部署后回读验证）。
        # 不用 *.workers.dev：国内很多网络会把它解析到错误地址，回读必然超时。
        'domain': '',
    },
    'pack': {
        # 版本标识；留空则取 exe 的文件时间
        'version': '',
        # zip 输出目录（相对仓库根目录）
        'out_dir': 'dist',
    },
}

API = 'https://api.cloudflare.com/client/v4'
# 静态资源不需要 Workers 运行时 API，取一个较早且稳定的兼容日期即可
COMPAT_DATE = '2025-01-01'
# 网络抖动时的重试次数（只针对连接超时/连接被重置）
MAX_RETRIES = 3

# Worker 入口脚本：静态资源的入口。
# 与控制台建 Sites 项目时生成的脚本相同 —— 把所有请求交给静态资源绑定。
# 没有它，API 上传的 assets 清单不会被识别为静态站点（线上 1105）。
WORKER_MODULE = 'worker.js'
WORKER_JS = (b'export default {\n'
             b'  async fetch(request, env) {\n'
             b'    return env.ASSETS.fetch(request);\n'
             b'  }\n'
             b'};\n')

BLANK_INDEX = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MC 启动器 引导程序</title>
</head>
<body>
<h3>MC 启动器 引导程序</h3>
<p>下载：</p>
<ul>
<li><a href="mc-launcher-bootstrap.zip" download>mc-launcher-bootstrap.zip（完整包，含两个架构）</a></li>
<li><a href="NCL.zip" download>NCL.zip（64 位 Windows）</a></li>
<li><a href="NCL_x86.zip" download>NCL_x86.zip（32 位 Windows）</a></li>
</ul>
<p>解压后双击里面的 exe 运行。不确定选哪个：右键「此电脑」→「属性」→ 查看「系统类型」。</p>
</body>
</html>
"""


# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------
def step(msg):
    print('\n==> ' + msg)


def ok(msg):
    print('    ✓ ' + msg)


def warn(msg):
    print('    ! ' + msg)


def UtilDeleteQuiet(path):
    """删除临时文件；删不掉也无所谓（可能被占用），不抛异常"""
    try:
        os.remove(path)
    except OSError:
        pass


def die(msg, code=1):
    print('\n[失败] ' + msg)
    sys.exit(code)


def human(n):
    if n < 1024:
        return '%d B' % n
    if n < 1024 * 1024:
        return '%.1f KB' % (n / 1024.0)
    return '%.1f MB' % (n / 1048576.0)


# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
def load_config():
    cfg = json.loads(json.dumps(DEFAULT_CONFIG))
    if os.path.isfile(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, encoding='utf-8') as f:
                user = json.load(f)
            for section in ('cloudflare', 'pack'):
                cfg[section].update(user.get(section) or {})
        except Exception as e:
            die('读取配置 %s 失败：%s' % (CONFIG_PATH, e))
    if os.environ.get('CLOUDFLARE_API_TOKEN'):
        cfg['cloudflare']['api_token'] = os.environ['CLOUDFLARE_API_TOKEN']
    if os.environ.get('CLOUDFLARE_ACCOUNT_ID'):
        cfg['cloudflare']['account_id'] = os.environ['CLOUDFLARE_ACCOUNT_ID']
    if os.environ.get('CF_PROJECT'):
        cfg['cloudflare']['project'] = os.environ['CF_PROJECT']
    if os.environ.get('CF_DOMAIN'):
        cfg['cloudflare']['domain'] = os.environ['CF_DOMAIN']
    if os.environ.get('BOOTSTRAPPER_VERSION'):
        cfg['pack']['version'] = os.environ['BOOTSTRAPPER_VERSION']
    return cfg


def write_config_template():
    if os.path.exists(CONFIG_PATH):
        die('配置已存在，未覆盖：%s' % CONFIG_PATH)
    with open(CONFIG_PATH, 'w', encoding='utf-8') as f:
        json.dump(DEFAULT_CONFIG, f, indent=2, ensure_ascii=False)
        f.write('\n')
    print('已生成配置模板：%s' % CONFIG_PATH)
    print('请填入 CLOUDFLARE_ACCOUNT_ID 与 API Token（权限 Workers Scripts:Edit）。')
    print('注意：这个文件含令牌，不要提交到仓库。也可以改用环境变量。')


# ---------------------------------------------------------------------------
# 打包
# ---------------------------------------------------------------------------
def locate_exes():
    """返回 [(源路径, zip 内名字)]；两个都必须在，缺一个就报错

    编译产物叫 MCLauncherBootstrap(.x86).exe，打进 zip 时改名为 NCL.exe / NCL_x86.exe
    """
    found = []
    for src_name, arc_name in ((SRC_X64, EXE_X64), (SRC_X86, EXE_X86)):
        p = os.path.join(BOOT_BIN, src_name)
        if not os.path.isfile(p):
            die('找不到 %s\n请先在 bootstrapper 目录编译：\n'
                '  bash tools/build-x86.sh（32 位）\n'
                '  按 README「方式一/二」编译 64 位' % p)
        found.append((p, arc_name))
    return found


def build_zip(cfg, out_path, version):
    """按固定顺序把文件写进 zip，返回 ([(zip 内名字, 字节数, sha256)], zip 的 sha256, [(名字, 字节)])

    打包分两层：
      1. 每个 exe 先改名（复制到临时目录），再**单独**压成同名 zip：NCL.zip / NCL_x86.zip
      2. 这两个 zip 放进大包 mc-launcher-bootstrap.zip（根目录还有空白 index.html）

    这样用户拿到的分发包里是「按架构分好的两个 zip」，单独分发某一个架构时也只需
    下载对应的小 zip，而不用下载整包再挑文件。
    """
    # zip 内部固定时间戳，保证同样输入得到同样的产物（便于比对哈希）
    zip_date = (2020, 1, 1, 0, 0, 0)

    def make_zip(items):
        """items: [(zip 内名字, 字节)] -> zip 字节"""
        bio = io.BytesIO()
        with zipfile.ZipFile(bio, 'w') as zf:
            for arcname, data in items:
                info = zipfile.ZipInfo(arcname, date_time=zip_date)
                info.compress_type = zipfile.ZIP_DEFLATED
                # 标记为解压出来是普通文件
                info.external_attr = 0o644 << 16
                info.create_system = 0
                zf.writestr(info, data)
        return bio.getvalue()

    # 1) 每个 exe 改名后单独压成同名 zip
    exe_zips = []
    tmpdir = tempfile.mkdtemp(prefix='ncl-deploy-')
    try:
        for src, exe_name in locate_exes():
            renamed = os.path.join(tmpdir, exe_name)      # 先改名
            shutil.copyfile(src, renamed)
            with open(renamed, 'rb') as f:
                exe_bytes = f.read()
            zip_name = os.path.splitext(exe_name)[0] + '.zip'   # NCL.exe -> NCL.zip
            exe_zips.append((zip_name, make_zip([(exe_name, exe_bytes)])))
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    # 2) 两个小 zip + 空白页 压成大包
    big = make_zip(exe_zips + [(INDEX_HTML, BLANK_INDEX.encode('utf-8'))])

    os.makedirs(os.path.dirname(out_path) or '.', exist_ok=True)
    tmp = out_path + '.tmp'
    try:
        with open(tmp, 'wb') as f:
            f.write(big)
        os.replace(tmp, out_path)
    except PermissionError:
        UtilDeleteQuiet(tmp)
        die('无法写入 %s（文件被占用）\n'
            '请关闭正在打开该 zip 的程序（资源管理器预览、解压软件、杀毒扫描等）后重试'
            % out_path)
    except OSError as e:
        UtilDeleteQuiet(tmp)
        die('无法写入 %s：%s' % (out_path, e))

    # 3) 小 zip 也写到大包旁边，方便单独分发
    out_dir = os.path.dirname(out_path) or '.'
    for zip_name, data in exe_zips:
        p = os.path.join(out_dir, zip_name)
        try:
            with open(p, 'wb') as f:
                f.write(data)
        except OSError as e:
            warn('无法写出 %s（可能被占用）：%s' % (p, e))

    zip_sha = hashlib.sha256(big).hexdigest()
    with open(out_path + '.sha256', 'w', encoding='utf-8', newline='\n') as f:
        f.write('%s  %s\n' % (zip_sha, os.path.basename(out_path)))

    # 本函数只负责本地产物；上传用的文件清单（大包 + 两个小 zip）由 main 组装
    return [(os.path.basename(out_path), len(big), zip_sha)] +            [(name, len(data), hashlib.sha256(data).hexdigest()) for name, data in exe_zips],            zip_sha, [(os.path.basename(out_path), big)] + exe_zips


# ---------------------------------------------------------------------------
# Cloudflare Workers Static Assets
# ---------------------------------------------------------------------------
class Cloudflare:
    def __init__(self, cfg, dry_run=False):
        cf = cfg['cloudflare']
        self.account = cf['account_id']
        self.token = cf['api_token']
        self.project = cf['project']
        self.dry_run = dry_run

    def _url(self, path):
        return '%s/accounts/%s/%s' % (API, self.account, path)

    def _headers(self):
        return {'Authorization': 'Bearer %s' % self.token}

    def _request(self, method, path, what, **kw):
        """所有 API 请求的统一出口：带重试

        Cloudflare 的接口偶尔会连接超时（WinError 10060 / ConnectTimeout）。
        这类错误与请求内容无关，重试通常就能成功；但重试同一个「上传版本」
        请求会多建一个版本，所以只在连接阶段失败时重试。
        """
        url = self._url(path)
        last = None
        for attempt in range(1, MAX_RETRIES + 1):
            try:
                return requests.request(method, url, timeout=kw.pop('timeout', 120), **kw)
            except (requests.ConnectTimeout, requests.ConnectionError) as e:
                last = e
                if attempt < MAX_RETRIES:
                    wait = attempt * 5
                    warn('%s：连接失败（%s），%d 秒后重试（%d/%d）'
                         % (what, type(e).__name__, wait, attempt, MAX_RETRIES))
                    time.sleep(wait)
            except requests.RequestException as e:
                last = e
                break
        die('%s：网络错误 — %s\n'
            '若反复出现，请检查网络能否访问 api.cloudflare.com（本机 DNS 是否正常）'
            % (what, last))

    def _check(self, r, what):
        """把 Cloudflare 的 {success, errors} 结构翻译成可读错误"""
        try:
            data = r.json()
        except ValueError:
            data = {}
        if r.status_code >= 300 or not data.get('success', False):
            errs = data.get('errors') or []
            msg = '; '.join('%s %s' % (e.get('code', ''), e.get('message', ''))
                            for e in errs) or r.text[:300]
            hints = []
            if r.status_code in (401, 403):
                hints.append('API Token 无效或无权限（需要 Workers Scripts:Edit）')
            if r.status_code == 404:
                hints.append('Account ID 或项目名不正确')
            die('%s 失败（HTTP %d）：%s%s'
                % (what, r.status_code, msg,
                   ('\n' + '\n'.join(hints)) if hints else ''))
        return data.get('result')

    def upload_assets(self, entries):
        """两步式 Static Assets 上传（与 wrangler 相同），返回发布用 JWT。

            POST /workers/scripts/<name>/assets-upload-session   -> {jwt, buckets}
            POST /workers/assets/upload?base64=true  (带 jwt)     -> {jwt}

        注意这里用的是 workers/scripts/<name>/assets-upload-session，
        不是 /workers/assets/v2/upload —— 后者目前在 API 上返回 404。

        entries: [(名字, 字节)]。文件名 = 内容哈希（截断 32 hex），
        内容相同的文件自动去重，这是 Static Assets 的约定。
        """
        by_hash = {}
        names = []
        for arcname, data in entries:
            h = hashlib.sha256(data).hexdigest()[:32]
            by_hash[h] = data
            names.append((arcname, h))

        # zip 里没有目录，路径都是 /xxx
        manifest = {'/' + arcname: {'hash': h, 'size': len(by_hash[h])}
                    for arcname, h in names}

        r = self._request(
            'POST', 'workers/scripts/%s/assets-upload-session' % self.project,
            '创建上传会话',
            headers={**self._headers(), 'Content-Type': 'application/json'},
            data=json.dumps({'manifest': manifest}), timeout=60)
        res = self._check(r, '创建上传会话') or {}
        jwt = res.get('jwt')
        buckets = res.get('buckets') or []
        if not jwt:
            die('创建上传会话后没有拿到 JWT')

        # 会话按内容去重，只把还没上传过的文件列在 buckets 里
        if buckets:
            boundary = '----mclauncher%d' % int(time.time() * 1000)
            parts = []
            n = 0
            for bucket in buckets:
                for h in bucket:
                    if h not in by_hash:
                        continue
                    parts.append(('--%s\r\nContent-Disposition: form-data; name="%s"; '
                                  'filename="%s"\r\nContent-Type: application/octet-stream\r\n\r\n'
                                  % (boundary, h, h)).encode('utf-8'))
                    parts.append(base64.b64encode(by_hash[h]))
                    parts.append(b'\r\n')
                    n += 1
            parts.append(('--%s--\r\n' % boundary).encode('utf-8'))
            up = self._request(
                'POST', 'workers/assets/upload', '上传文件内容',
                params={'base64': 'true'},
                headers={'Authorization': 'Bearer %s' % jwt,
                         'Content-Type': 'multipart/form-data; boundary=%s' % boundary},
                data=b''.join(parts), timeout=300)
            upres = self._check(up, '上传文件内容') or {}
            jwt = upres.get('jwt') or jwt
            ok('已上传 %d 个文件（%d 个已存在，跳过）'
               % (n, len(by_hash) - n))
        else:
            ok('所有文件都已存在，无需上传')
        return jwt, manifest

    def deploy(self, jwt, manifest):
        """发布：Worker 脚本（负责把请求交给静态资源）+ 静态资源清单

        为什么一定要带脚本：
          只用 API 往一个空 worker 里塞 assets 清单，接口会返回 200，
          但它识别不出这是 Sites 项目（has_assets=False），
          线上直接 503 / error code 1105。用控制台建的项目自带一个
          env.ASSETS.fetch(req) 的脚本，这里照做。

        manifest 的键是带前导 / 的路径：{"/index.html": {"hash": ..., "size": ...}}
        """
        metadata = {
            'main_module': WORKER_MODULE,
            'compatibility_date': COMPAT_DATE,
            'assets': {'config': {}},
        }
        boundary = '----mclauncher%d' % int(time.time() * 1000)

        def part(name, value, filename=None, ctype=None):
            if filename:
                head = ('--%s\r\nContent-Disposition: form-data; name="%s"; '
                        'filename="%s"\r\nContent-Type: %s\r\n\r\n'
                        % (boundary, name, filename, ctype))
                return head.encode('utf-8') + value + b'\r\n'
            return (('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n'
                     % (boundary, name)).encode('utf-8') + str(value).encode('utf-8')
                    + b'\r\n')

        body = b''.join([
            part('metadata', json.dumps(metadata, ensure_ascii=False)),
            part(WORKER_MODULE, WORKER_JS, WORKER_MODULE,
                 'application/javascript+module'),
            part('files', json.dumps({'files': manifest}, ensure_ascii=False)),
            ('--%s--\r\n' % boundary).encode('utf-8'),
        ])
        # 先存一份请求体，失败时可以原样重试（排查用：--keep-body）
        if os.environ.get('MCL_CF_KEEP_BODY'):
            p = os.path.join(tempfile.gettempdir(), 'mcl_cf_body.bin')
            with open(p, 'wb') as f:
                f.write(body)
            warn('请求体已保存：%s（metadata 只含清单，不含文件内容）' % p)
            warn('metadata: %s' % json.dumps(metadata, ensure_ascii=False))
        r = self._request(
            'PUT', 'workers/scripts/%s' % self.project, '上传静态资源',
            headers={'Authorization': self._headers()['Authorization'],
                     'Content-Type': 'multipart/form-data; boundary=%s' % boundary},
            data=body, timeout=180)
        res = self._check(r, '上传静态资源') or {}
        if not res.get('has_assets'):
            warn('接口没有把这次上传识别为静态站点（has_assets=False），'
                 '线上可能出现 1105，请检查 metadata 格式')

        # 这里的 id 是脚本名，不是版本号：再查一次版本列表拿真正的版本 ID
        version_id = self.latest_version_id()
        if not version_id:
            die('上传静态资源后拿不到版本 ID，无法发布')

        # 创建版本不会自动生效，必须再建一个部署指向它（PUT 会 405，只能 POST）
        r = self._request(
            'POST', 'workers/scripts/%s/deployments' % self.project, '创建部署',
            headers={**self._headers(), 'Content-Type': 'application/json'},
            data=json.dumps({'versions': [{'version_id': version_id, 'percentage': 100}]}),
            timeout=120)
        self._check(r, '创建部署')
        return version_id

    def latest_version_id(self):
        """取该 worker 最新的版本 ID（列表按创建时间倒序）"""
        r = self._request(
            'GET', 'workers/scripts/%s/versions' % self.project, '查询版本列表',
            headers=self._headers(), timeout=60)
        res = self._check(r, '查询版本列表') or {}
        items = res.get('items') if isinstance(res, dict) else res
        if not items:
            return None
        # 按 number 取最大，避免接口排序变化
        best = max(items, key=lambda v: v.get('number') or 0)
        return best.get('id')

    def subdomain(self):
        r = self._request('GET', 'workers/subdomain', '查询子域名',
                          headers=self._headers(), timeout=30)
        try:
            return (r.json().get('result') or {}).get('subdomain')
        except ValueError:
            return None


def verify_online(url):
    """部署后回读线上页面；失败只警告，不影响部署结果"""
    last = ''
    for attempt in range(3):
        try:
            r = requests.get(url, timeout=30)
            if r.status_code == 200 and b'<html' in r.content.lower():
                ok('线上可访问：%s（HTTP 200）' % url)
                return True
            last = 'HTTP %d' % r.status_code
        except requests.RequestException as e:
            last = type(e).__name__
        time.sleep(3)
    warn('线上暂时不可访问（%s）：%s' % (last, url))
    warn('部署本身已成功。若一直打不开，常见原因：')
    warn('  · 刚部署完，CDN 还要几秒到一分钟生效；')
    warn('  · 本机 DNS 把 *.workers.dev 解析到了错误地址'
         '（可用 nslookup 核对，正常应为 104.x / 172.x）')
    return False


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description='打包引导器并部署到 Cloudflare')
    ap.add_argument('--init', action='store_true', help='生成配置模板 tools/deploy-cf.json')
    ap.add_argument('--no-deploy', action='store_true', help='只打包，不上传')
    ap.add_argument('--dry-run', action='store_true', help='只显示计划')
    ap.add_argument('--out', help='zip 输出路径（默认 dist/mc-launcher-bootstrap.zip）')
    args = ap.parse_args()

    if args.init:
        write_config_template()
        return 0

    cfg = load_config()
    version = cfg['pack'].get('version') or ''
    if not version:
        # 以 64 位 exe 的文件时间作为版本标识
        src = os.path.join(BOOT_BIN, SRC_X64)
        if os.path.isfile(src):
            version = time.strftime('%Y%m%d-%H%M', time.localtime(os.path.getmtime(src)))
        else:
            version = 'unknown'

    out_dir = cfg['pack'].get('out_dir') or 'dist'
    if not os.path.isabs(out_dir):
        out_dir = os.path.join(ROOT, out_dir)
    out_path = args.out or os.path.join(out_dir, 'mc-launcher-bootstrap.zip')

    need_deploy = not args.no_deploy and not args.dry_run
    cf = cfg['cloudflare']
    if need_deploy:
        missing = [k for k in ('account_id', 'api_token', 'project') if not cf.get(k)]
        if missing:
            die('配置不完整：%s\n请运行 python tools/deploy-cf.py --init 生成配置，'
                '或设置环境变量 CLOUDFLARE_API_TOKEN / CLOUDFLARE_ACCOUNT_ID'
                % ', '.join(missing))

    step('1/4 检查源文件')
    locate_exes()
    for src, name in locate_exes():
        ok('%-28s %s' % (name, human(os.path.getsize(src))))
    ok('%-28s 内置下载页' % INDEX_HTML)

    step('2/4 计划')
    print('    zip      : %s' % out_path)
    print('    版本标识 : %s' % version)
    if need_deploy:
        print('    Cloudflare: account=%s  worker=%s' % (cf['account_id'], cf['project']))
    else:
        print('    Cloudflare: 跳过（--no-deploy / --dry-run）')
    if args.dry_run:
        warn('dry-run：不打包、不上传')
        return 0

    step('3/4 打包 zip')
    os.makedirs(os.path.dirname(out_path) or '.', exist_ok=True)
    entries, zip_sha, payload = build_zip(cfg, out_path, version)
    for name, size, _ in entries:
        ok('%-32s %s' % (name, human(size)))
    ok('大包：%s（%s）' % (out_path, human(os.path.getsize(out_path))))
    ok('SHA256：%s' % zip_sha.upper())
    print(chr(10) + '    mc-launcher-bootstrap.zip 内含：')
    for src, exe_name in locate_exes():
        ok('  %-24s（%s 改名后单独压缩）' % (os.path.splitext(exe_name)[0] + '.zip', exe_name))
    ok('  %-24s（下载页，列出两个 zip）' % INDEX_HTML)

    if not need_deploy:
        step('4/4 跳过部署')
        print('\n完成。zip 与 .sha256 已生成。')
        return 0

    step('4/4 部署到 Cloudflare')
    client = Cloudflare(cfg, dry_run=False)
    jwt, manifest = client.upload_assets(payload)
    version_id = client.deploy(jwt, manifest)
    ok('已发布到 worker：%s（版本 %s）' % (cf['project'], version_id))

    # 优先用自定义域名回读；没有配置时才退回 workers.dev
    domain = (cf.get('domain') or '').strip().rstrip('/')
    if domain:
        url = domain if domain.startswith('http') else 'https://' + domain
        verify_online(url.rstrip('/') + '/')
    else:
        sub = client.subdomain()
        if sub:
            verify_online('https://%s.%s.workers.dev/' % (cf['project'], sub))
        else:
            warn('未能获取 workers.dev 子域名，请到控制台确认访问地址')

    print('\n部署完成：')
    print('  zip      : %s' % out_path)
    print('  sha256   : %s' % (out_path + '.sha256'))
    print('  worker   : %s' % cf['project'])
    print('  文件     : %d 个' % len(manifest))
    return 0


if __name__ == '__main__':
    sys.exit(main())