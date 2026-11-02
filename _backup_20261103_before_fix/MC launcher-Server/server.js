/**
 * NCL 启动器服务端
 *
 * 提供两类能力：
 *
 *   1. 版本管理（更新检查 / 版本列表）—— 启动器 UpdateClient 与 Web 管理页共用
 *        POST   /api/check-update        { currentVersion } -> { updateAvailable, latestVersion, allVersions }
 *        GET    /api/versions            -> { latest, versions: [] }
 *        POST   /api/versions            { version }       添加
 *        PUT    /api/versions/latest     { version }       指定最新
 *        DELETE /api/versions/:version                     删除
 *
 *   2. 静态数据（启动器直接从本站下载）
 *        GET    /mcmod_intros.json       模组中文简介表（~8 MB）
 *        GET    /mcmod_intros.json.meta  简介表元信息（条数 / 生成时间 / 大小 / 版本号）
 *
 * 设计要点
 * --------
 * - **版本文件读改写要串行**：多个请求同时改 version.json 会互相覆盖。
 *   这里用一条 Promise 链把写操作排成队（单进程 fork 模式下足够）。
 * - **写入要原子**：先写临时文件再 rename，避免进程中途被杀留下半个 JSON，
 *   下次启动直接读不出来 —— 那会让所有客户端「检查更新」失败。
 * - **简介表走流式发送 + ETag**：文件 8 MB，一次性 readFileSync 会吃掉十几 MB
 *   堆内存并阻塞事件循环；启动器每 7 天拉一次，ETag 命中时直接 304。
 * - **端口 / 文件路径可用环境变量覆盖**：pm2 与 systemd 两种部署方式都能配。
 */
'use strict';

const express = require('express');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');
const { pipeline } = require('stream');

const app = express();

/** 端口：优先环境变量（pm2 / systemd 都从这里传） */
const PORT = Number(process.env.PORT) || 5003;

/** 只监听本机时设 HOST=127.0.0.1（前面挂 nginx 反代时推荐） */
const HOST = process.env.HOST || '0.0.0.0';

/** 数据文件 */
const VERSION_FILE = process.env.VERSION_FILE || path.join(__dirname, 'version.json');
const INTRO_FILE = process.env.INTRO_FILE || path.join(__dirname, 'mcmod_intros.json');

/**
 * 各版本 jar 的 SHA-256 清单（文件替代）
 *
 * 结构：{ "1.0.2": "f241...", "1.0.1": "9b42..." }
 * 由发布脚本生成（见 publish-jar.js），不是手工维护的接口。
 * 这里只读文件、不做热更新：每 30 秒按 mtime 重新加载一次。
 */
const SHA_FILE = process.env.SHA_FILE || path.join(__dirname, 'artifacts.json');

/** sha256 -> 版本 的反查表，用于给调用方补上缺失的哈希 */
let shaCache = { mtimeMs: 0, byVersion: {}, valid: false };

const SHA256_RE = /^[0-9a-fA-F]{64}$/;

/** 合并写入 artifacts.json（原子写：先 .tmp 再 rename） */
function mergeShaFile(entries) {
    const current = loadShaTable().byVersion;
    const merged = { ...current };
    for (const [v, h] of Object.entries(entries || {})) {
        if (VERSION_RE.test(v) && typeof h === 'string' && SHA256_RE.test(h)) {
            merged[v] = h.toLowerCase();
        }
    }
    const tmp = `${SHA_FILE}.${process.pid}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(merged, null, 2) + '\n', 'utf8');
    fs.renameSync(tmp, SHA_FILE);
    // 让下一次读取重新加载（mtime 可能精度不足）
    shaCache = { mtimeMs: -1, byVersion: merged, valid: true };
    return merged;
}

function loadShaTable() {
  let st;
  try {
    st = fs.statSync(SHA_FILE);
  } catch {
    shaCache = { mtimeMs: 0, byVersion: {}, valid: false };
    return shaCache;
  }
  // Windows mtime 精度只有 1-2ms，避免不必要的重新加载
  // 仅当实际内容变化时才重新加载
  if (st.mtimeMs === shaCache.mtimeMs && shaCache.valid) {
    return shaCache;
  }
  try {
    const raw = JSON.parse(fs.readFileSync(SHA_FILE, 'utf8'));
    const src = raw && typeof raw === 'object' && raw.hashes ? raw.hashes : raw;
    const byVersion = {};
    for (const [k, v] of Object.entries(src || {})) {
      if (VERSION_RE.test(k) && typeof v === 'string' && /^[0-9a-f]{64}$/i.test(v)) {
        byVersion[k] = v.toLowerCase();
      }
    }
    shaCache = { mtimeMs: st.mtimeMs, byVersion, valid: true };
  } catch (err) {
    console.error('[sha] 读取 SHA 清单失败（本次请求不带哈希）:', err.message);
    shaCache = { mtimeMs: st.mtimeMs, byVersion: {}, valid: false };
  }
  return shaCache;
}

function shaFor(version) {
  if (typeof version !== 'string' || !VERSION_RE.test(version)) return null;
  return loadShaTable().byVersion[version] || null;
}

/**
 * 补上版本对象里的 sha256 字段
 *
 * 只在能定位到这个 jar 时给出哈希：
 * - artifacts.json 里有该版本 -> 直接给；
 * - 否则若 artifacts.json 里恰好某个版本的文件名与请求的 jar 同名
 *   （发布时用 .jar 名字登记），也能对上。
 * 查不到就返回 null，客户端会走自己的信任锚校验。
 */
function withSha(version) {
  return { version, sha256: shaFor(version) };
}

/** 默认版本号：版本文件损坏或不存在时使用 */
const FALLBACK_VERSION = '1.0.0';

/** 版本号格式：只允许数字和点（启动器下载 jar 时也用同样的白名单） */
const VERSION_RE = /^\d+(\.\d+){0,3}$/;

// ==================== 通用工具 ====================

/** 版本号比较：1.10 > 1.9 > 1.9.0.1 */
function compareVersions(v1, v2) {
  const a = String(v1).split('.').map((x) => parseInt(x, 10) || 0);
  const b = String(v2).split('.').map((x) => parseInt(x, 10) || 0);
  const len = Math.max(a.length, b.length);
  for (let i = 0; i < len; i++) {
    const x = a[i] || 0;
    const y = b[i] || 0;
    if (x > y) return 1;
    if (x < y) return -1;
  }
  return 0;
}

/**
 * 规范化版本对象
 *
 * 历史数据里 versions 可能是乱序、含重复或含 latest 不存在的值，
 * 统一校正成「升序、去重、latest 一定在列表里」。
 */
function normalizeVersionData(raw) {
  let list = Array.isArray(raw && raw.versions) ? raw.versions : [];
  list = list.filter((v) => typeof v === 'string' && VERSION_RE.test(v));
  list = Array.from(new Set(list)).sort(compareVersions);

  let latest = raw && typeof raw.latest === 'string' ? raw.latest : '';
  if (!VERSION_RE.test(latest) || !list.includes(latest)) {
    latest = list.length ? list[list.length - 1] : FALLBACK_VERSION;
  }
  if (!list.length) list = [latest];
  if (!list.includes(latest)) list.push(latest);

  return { latest, versions: list };
}

/** 同步读取版本文件（简单、量小） */
function readVersionFile() {
  try {
    return normalizeVersionData(JSON.parse(fs.readFileSync(VERSION_FILE, 'utf8')));
  } catch (err) {
    if (err.code !== 'ENOENT') {
      console.error('[版本] 读取版本文件失败，使用默认值:', err.message);
    }
    return normalizeVersionData(null);
  }
}

/**
 * 原子写版本文件
 *
 * 先写 .tmp 再 rename：rename 在同一文件系统上是原子的，
 * 读者要么看到旧文件、要么看到完整的新文件，不会读到半个 JSON。
 */
function writeVersionFile(data) {
  const tmp = `${VERSION_FILE}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2) + '\n', 'utf8');
  fs.renameSync(tmp, VERSION_FILE);
}

/**
 * 串行化「读-改-写」
 *
 * 不排队的话，两个并发的 POST 会同时读到同一份旧数据，
 * 后写的那个把前一个的修改整个覆盖掉（丢版本）。
 */
let writeQueue = Promise.resolve();
function mutateVersions(fn) {
  const task = writeQueue.then(async () => {
    const current = readVersionFile();
    // fn 返回 { error, status } 表示拒绝，返回新数据表示成功
    const result = await fn(current);
    if (result && result.error) {
      return result;
    }
    writeVersionFile(result);
    return { data: result };
  });
  // 无论成功失败，队列都要继续往下走，并记录错误
  writeQueue = task.catch((err) => {
    console.error('[writeQueue] Task failed:', err.message);
  });
  return task;
}

/** 统一的接口响应形状：与旧版保持一致，Web 管理页直接读 data */
function ok(res, data) {
  res.json({ success: true, data });
}

/**
 * 管理令牌：写接口（增删版本 / 指定 latest）必须携带。
 *
 * 以前这三个接口完全没有鉴权且默认监听 0.0.0.0 —— 任何人都能改掉
 * 推给全部客户端的「最新版本」。现在：
 * - 未配置 ADMIN_TOKEN 时写接口一律 503（fail-closed，不允许无鉴权放行）
 * - 已配置时校验 Authorization: Bearer <token> 或 X-Admin-Token
 * 公开的 Web 管理页可以把令牌存在本地，请求时带上。
 */
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || '';

function safeEqual(a, b) {
  const ba = Buffer.from(String(a || ''), 'utf8');
  const bb = Buffer.from(String(b || ''), 'utf8');
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

function requireAdmin(req, res, next) {
  if (!ADMIN_TOKEN) {
    return res.status(503).json({ error: '服务端未配置 ADMIN_TOKEN，写接口已禁用' });
  }
  const auth = req.headers['authorization'] || '';
  const bearer = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  const header = req.headers['x-admin-token'] || '';
  if ((bearer && safeEqual(bearer, ADMIN_TOKEN)) || (header && safeEqual(header, ADMIN_TOKEN))) {
    return next();
  }
  return res.status(401).json({ error: '未授权：需要有效的管理令牌' });
}

// ==================== 通用中间件 ====================

app.disable('x-powered-by');
app.use(express.json({ limit: '50mb' }));

// API 响应一律禁止缓存：版本信息一变就该立刻生效，
// 不希望中间的反代 / CDN 把「已是最新」缓存住。
// 注意本行必须放在静态中间件之前，close() 内部才会调用 next()。
app.use('/api', (req, res, next) => {
  res.setHeader('Cache-Control', 'no-store');
  // ETag 是 express 在 send 阶段才生成的，这里只能拦在发送时删掉
  const rawEnd = res.end.bind(res);
  res.end = function (chunk, encoding) {
    res.removeHeader('ETag');
    return rawEnd(chunk, encoding);
  };
  next();
});

// 静态页面（Web 管理页）
app.use(express.static(path.join(__dirname, 'public'), { index: false }));

// 简易访问日志：只记 API 与非成功状态，避免图片/静态资源刷屏
app.use((req, res, next) => {
  const start = Date.now();
  res.on('finish', () => {
    if (req.path.startsWith('/api/') || res.statusCode >= 400) {
      console.log(
        `[${new Date().toISOString()}] ${req.ip} ${req.method} ${req.originalUrl} ` +
        `${res.statusCode} ${Date.now() - start}ms`
      );
    }
  });
  next();
});

// ==================== 版本管理 API ====================

/**
 * 检查更新
 *
 * 启动器契约（UpdateClient.checkUpdate）：
 *   有更新   -> { updateAvailable: true,  latestVersion: "x.y.z", allVersions: [...] }
 *   无更新   -> { updateAvailable: false, latestVersion: null, currentVersion: "x.y.z" }
 */
app.post('/api/check-update', (req, res, next) => {
  try {
    const currentVersion = req.body && req.body.currentVersion;
    if (!currentVersion || typeof currentVersion !== 'string') {
      return res.status(400).json({ error: '缺少 currentVersion 参数' });
    }

    const data = readVersionFile();
    const cmp = compareVersions(currentVersion, data.latest);

    if (cmp < 0) {
      return res.json({
        updateAvailable: true,
        latestVersion: data.latest,
        currentVersion,
        allVersions: data.versions,
        sha256: shaFor(data.latest),
        files: data.versions.map((v) => withSha(v)),
      });
    }
    return res.json({
      updateAvailable: false,
      latestVersion: null,
      currentVersion: currentVersion,
      allVersions: data.versions,
      sha256: null,
      files: data.versions.map((v) => withSha(v)),
    });
  } catch (err) {
    next(err);
  }
});

/** 版本列表
 *
 * 保持向后兼容：latest / versions 的字段不变；
 * 额外给出 sha256（最新版本）以及 files 数组（每个版本 + 哈希），
 * 老客户端忽略新字段，新客户端用它校验下载内容。
 */
app.get('/api/versions', (req, res, next) => {
  try {
    const data = readVersionFile();
    res.json({
      ...data,
      sha256: shaFor(data.latest),
      files: data.versions.map((v) => withSha(v)),
    });
  } catch (err) {
    next(err);
  }
});

/** 添加版本 */
app.post('/api/versions', requireAdmin, async (req, res, next) => {
  try {
    const version = req.body && req.body.version;
    if (!version || typeof version !== 'string') {
      return res.status(400).json({ error: '缺少 version 参数' });
    }
    if (!VERSION_RE.test(version)) {
      return res.status(400).json({ error: `版本号格式不正确（示例：1.0.2）：${version}` });
    }
    // 可选：顺带登记 jar 的 SHA-256（发布脚本上传后调用）
    const sha256 = req.body && typeof req.body.sha256 === 'string' ? req.body.sha256 : null;
    if (sha256 !== null && !SHA256_RE.test(sha256)) {
      return res.status(400).json({ error: 'sha256 必须是 64 位十六进制' });
    }

    const r = await mutateVersions((data) => {
      if (data.versions.includes(version)) {
        return { error: '版本已存在', status: 400 };
      }
      const next = normalizeVersionData({
        versions: [...data.versions, version],
        // 新加的版本比当前 latest 高时自动成为最新
        latest: compareVersions(version, data.latest) > 0 ? version : data.latest,
      });
      return next;
    });

    // 版本已存在但带了新哈希：同样写入（允许补登记哈希）
    if (r.error) {
      if (sha256) {
        mergeShaFile({ [version]: sha256 });
      }
      return res.status(r.status || 400).json({ error: r.error });
    }
    if (sha256) {
      try {
        mergeShaFile({ [version]: sha256 });
      } catch (e) {
        console.error('[sha] 写入 artifacts.json 失败:', e.message);
        return res.status(500).json({ error: '版本已添加，但哈希写入失败: ' + e.message });
      }
    }
    return ok(res, { ...r.data, sha256: shaFor(version) });
  } catch (err) {
    next(err);
  }
});

/** 删除版本 */
app.delete('/api/versions/:version', requireAdmin, async (req, res, next) => {
  try {
    const version = req.params.version;
    const r = await mutateVersions((data) => {
      if (!data.versions.includes(version)) {
        return { error: '版本不存在', status: 404 };
      }
      if (version === data.latest && data.versions.length > 1) {
        return { error: '不能删除最新版本，请先添加更高版本或把其它版本设为最新', status: 400 };
      }
      const rest = data.versions.filter((v) => v !== version);
      return normalizeVersionData({ versions: rest, latest: data.latest });
    });

    if (r.error) return res.status(r.status || 400).json({ error: r.error });
    return ok(res, r.data);
  } catch (err) {
    next(err);
  }
});

/**
 * 指定最新版本
 *
 * 注意：这个路由必须写在 DELETE/POST 之后、且路径带 latest，
 * Express 按注册顺序匹配，不会和 /api/versions/:version 冲突。
 */
app.put('/api/versions/latest', requireAdmin, async (req, res, next) => {
  try {
    const version = req.body && req.body.version;
    if (!version || typeof version !== 'string') {
      return res.status(400).json({ error: '缺少 version 参数' });
    }

    const r = await mutateVersions((data) => {
      if (!data.versions.includes(version)) {
        return { error: '版本不存在，请先添加该版本', status: 404 };
      }
      return { latest: version, versions: data.versions };
    });

    if (r.error) return res.status(r.status || 400).json({ error: r.error });
    return ok(res, r.data);
  } catch (err) {
    next(err);
  }
});

// ==================== 静态数据 ====================

/**
 * 简介表元信息
 *
 * 启动器可以先用它做一次很小的探测（拿到条数/版本号/大小），
 * 再决定要不要拉那 8 MB。不带缓存头，保证拿到最新。
 */
app.get('/mcmod_intros.json.meta', (req, res, next) => {
  fs.stat(INTRO_FILE, (err, st) => {
    if (err) {
      return res.status(404).json({ error: '简介表不存在' });
    }
    // 用 大小+修改时间 作为版本号：文件一换就变
    const version = crypto.createHash('sha1')
      .update(`${st.size}:${st.mtimeMs}`)
      .digest('hex')
      .slice(0, 16);
    res.setHeader('Cache-Control', 'no-cache');
    res.json({
      file: path.basename(INTRO_FILE),
      size: st.size,
      mtime: new Date(st.mtimeMs).toISOString(),
      version,
    });
  });
});

/**
 * 模组中文简介表
 *
 * - ETag：内容一变才变，启动器带 If-None-Match 时回 304（省 8 MB 流量）
 * - 流式发送：不把整个文件读进内存
 * - 支持 gzip：8 MB 的 JSON 压缩后通常只有 1~2 MB
 */
/*
 * gzip 结果缓存：8 MB 的 JSON 用 level 9 压缩要几百毫秒同步地吃一颗 CPU 核，
 * 以前每个请求都现压一遍 —— 几个客户端同时拉就会把事件循环堵死。
 * 压缩结果只取决于文件内容，按 mtime+size 缓存即可；旧缓存自动被替换。
 */
const GZIP_LEVEL = 6;
let gzipCache = { key: '', buf: null, inflight: null };

function getGzipBuffer(st, cb) {
  const key = `${st.size}:${st.mtimeMs}`;
  if (gzipCache.key === key && gzipCache.buf) return cb(null, gzipCache.buf);
  if (gzipCache.key === key && gzipCache.inflight) {
    gzipCache.inflight.push(cb);
    return undefined;
  }
  const waiters = [cb];
  gzipCache = { key, buf: null, inflight: waiters };
  const chunks = [];
  const gz = zlib.createGzip({ level: GZIP_LEVEL });
  gz.on('data', (c) => chunks.push(c));
  gz.on('error', (e) => {
    gzipCache = { key: '', buf: null, inflight: null };
    waiters.splice(0).forEach((fn) => fn(e));
  });
  gz.on('end', () => {
    const buf = Buffer.concat(chunks);
    gzipCache = { key, buf, inflight: null };
    waiters.splice(0).forEach((fn) => fn(null, buf));
  });
  fs.createReadStream(INTRO_FILE).on('error', (e) => gz.destroy(e)).pipe(gz);
  return undefined;
}

app.get('/mcmod_intros.json', (req, res, next) => {
  fs.stat(INTRO_FILE, (err, st) => {
    if (err) {
      return res.status(404).json({ error: '简介表不存在' });
    }

    const etag = `"${st.size.toString(16)}-${Math.floor(st.mtimeMs).toString(16)}"`;
    res.setHeader('ETag', etag);
    // 允许缓存 1 小时，期间用 ETag 校验
    res.setHeader('Cache-Control', 'public, max-age=3600');
    res.setHeader('Content-Type', 'application/json; charset=utf-8');

    if (req.headers['if-none-match'] === etag) {
      return res.status(304).end();
    }

    // 8 MB 的 JSON 压缩后通常只有 1~2 MB。用 Node 内置 zlib，
    // 不额外依赖 compression 包 —— 服务器上 npm install 不一定拿得到。
    const acceptsGzip = (req.headers['accept-encoding'] || '')
        .split(',')
        .some((part) => part.trim().toLowerCase().startsWith('gzip'));
    if (acceptsGzip) {
      res.setHeader('Content-Encoding', 'gzip');
      res.setHeader('Vary', 'Accept-Encoding');
      return getGzipBuffer(st, (err, buf) => {
        if (err) return next(err);
        if (res.destroyed) return undefined;
        res.setHeader('Content-Length', buf.length);
        res.writeHead(200);
        res.end(buf);
        return undefined;
      });
    }

    res.setHeader('Content-Length', st.size);
    const source = fs.createReadStream(INTRO_FILE);
    source.on('error', next);
    res.writeHead(200);
    pipeline(source, res, (e) => {
      if (e && e.code !== 'ERR_STREAM_PREMATURE_CLOSE' && e.code !== 'EPIPE') next(e);
    });
    return undefined;
  });
});

/** 根路径：Web 管理页 */
app.get('/', (req, res) => {
  const dir = path.join(__dirname, 'public');
  let html;
  try {
    // 优先 index.html；否则取排序后的第一个 html，保证每次结果一致
    html = fs.existsSync(path.join(dir, 'index.html'))
      ? 'index.html'
      : fs.readdirSync(dir).filter((f) => f.endsWith('.html')).sort()[0];
  } catch {
    html = undefined;
  }
  if (!html) return res.status(404).send('管理页面不存在');
  return res.sendFile(path.join(dir, html));
});

/** 健康检查（给 pm2 / nginx / 监控用） */
app.get('/health', (req, res) => {
  const data = readVersionFile();
  let introSize = null;
  try {
    introSize = fs.statSync(INTRO_FILE).size;
  } catch {
    introSize = null;
  }
  res.json({
    ok: true,
    uptime: Math.round(process.uptime()),
    latest: data.latest,
    versions: data.versions.length,
    introBytes: introSize,
  });
});

/** 404 */
app.use((req, res) => {
  res.status(404).json({ error: `未找到: ${req.method} ${req.path}` });
});

/** 统一错误处理：不把堆栈吐给客户端 */
app.use((err, req, res, next) => {
  console.error('[错误]', req.method, req.originalUrl, err);
  if (res.headersSent) return next(err);
  // body 不是合法 JSON 时 express.json 会抛这个
  if (err.type === 'entity.parse.failed') {
    return res.status(400).json({ error: '请求体不是合法 JSON' });
  }
  res.status(500).json({ error: '服务器内部错误' });
});

// ==================== 启动 ====================

const server = app.listen(PORT, HOST, () => {
  console.log(`服务器运行在 http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}`);
  console.log(`前端管理页面: http://localhost:${PORT}/`);
  if (!ADMIN_TOKEN) {
    console.warn('[警告] 未配置 ADMIN_TOKEN：增删版本 / 指定 latest 的接口已禁用（503）');
  }
  console.log(`版本文件: ${VERSION_FILE}`);
  console.log(`简介表: ${INTRO_FILE}`);
});

/** 端口被占用时给出人话提示，而不是一大段堆栈 */
/** 慢速攻击防护：慢读连接不能无限期占着文件句柄 */
server.headersTimeout = 10000;
server.requestTimeout = 30000;
server.timeout = 120000;

server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`[致命] 端口 ${PORT} 已被占用，请先停掉旧进程或换 PORT 环境变量`);
  } else {
    console.error('[致命] 启动失败:', err);
  }
  process.exit(1);
});

/** 优雅退出：pm2 restart / systemd stop 时先停止接收新连接 */
function shutdown(signal) {
  console.log(`收到 ${signal}，正在关闭...`);
  server.close(() => process.exit(0));
  // 10 秒内没关完就强退
  setTimeout(() => process.exit(1), 10000).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

module.exports = app;