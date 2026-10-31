'use strict';

const express = require('express');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');
const { pipeline } = require('stream');

const app = express();

const PORT = Number(process.env.PORT) || 5003;

const HOST = process.env.HOST || '0.0.0.0';

const VERSION_FILE = process.env.VERSION_FILE || path.join(__dirname, 'version.json');
const INTRO_FILE = process.env.INTRO_FILE || path.join(__dirname, 'mcmod_intros.json');

const SHA_FILE = process.env.SHA_FILE || path.join(__dirname, 'artifacts.json');

let shaCache = { mtimeMs: 0, byVersion: {}, valid: false };

const SHA256_RE = /^[0-9a-fA-F]{64}$/;

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

function withSha(version) {
  return { version, sha256: shaFor(version) };
}

const FALLBACK_VERSION = '1.0.0';

const VERSION_RE = /^\d+(\.\d+){0,3}$/;

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

function writeVersionFile(data) {
  const tmp = `${VERSION_FILE}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2) + '\n', 'utf8');
  fs.renameSync(tmp, VERSION_FILE);
}

let writeQueue = Promise.resolve();
function mutateVersions(fn) {
  const task = writeQueue.then(async () => {
    const current = readVersionFile();

    const result = await fn(current);
    if (result && result.error) {
      return result;
    }
    writeVersionFile(result);
    return { data: result };
  });

  writeQueue = task.catch((err) => {
    console.error('[writeQueue] Task failed:', err.message);
  });
  return task;
}

function ok(res, data) {
  res.json({ success: true, data });
}

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

app.disable('x-powered-by');
app.use(express.json({ limit: '50mb' }));

app.use('/api', (req, res, next) => {
  res.setHeader('Cache-Control', 'no-store');

  const rawEnd = res.end.bind(res);
  res.end = function (chunk, encoding) {
    res.removeHeader('ETag');
    return rawEnd(chunk, encoding);
  };
  next();
});

app.use(express.static(path.join(__dirname, 'public'), { index: false }));

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

app.post('/api/versions', requireAdmin, async (req, res, next) => {
  try {
    const version = req.body && req.body.version;
    if (!version || typeof version !== 'string') {
      return res.status(400).json({ error: '缺少 version 参数' });
    }
    if (!VERSION_RE.test(version)) {
      return res.status(400).json({ error: `版本号格式不正确（示例：1.0.2）：${version}` });
    }

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

        latest: compareVersions(version, data.latest) > 0 ? version : data.latest,
      });
      return next;
    });

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

app.get('/mcmod_intros.json.meta', (req, res, next) => {
  fs.stat(INTRO_FILE, (err, st) => {
    if (err) {
      return res.status(404).json({ error: '简介表不存在' });
    }

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

    res.setHeader('Cache-Control', 'public, max-age=3600');
    res.setHeader('Content-Type', 'application/json; charset=utf-8');

    if (req.headers['if-none-match'] === etag) {
      return res.status(304).end();
    }

    
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

app.get('/', (req, res) => {
  const dir = path.join(__dirname, 'public');
  let html;
  try {

    html = fs.existsSync(path.join(dir, 'index.html'))
      ? 'index.html'
      : fs.readdirSync(dir).filter((f) => f.endsWith('.html')).sort()[0];
  } catch {
    html = undefined;
  }
  if (!html) return res.status(404).send('管理页面不存在');
  return res.sendFile(path.join(dir, html));
});

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

app.use((req, res) => {
  res.status(404).json({ error: `未找到: ${req.method} ${req.path}` });
});

app.use((err, req, res, next) => {
  console.error('[错误]', req.method, req.originalUrl, err);
  if (res.headersSent) return next(err);

  if (err.type === 'entity.parse.failed') {
    return res.status(400).json({ error: '请求体不是合法 JSON' });
  }
  res.status(500).json({ error: '服务器内部错误' });
});

const server = app.listen(PORT, HOST, () => {
  console.log(`服务器运行在 http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}`);
  console.log(`前端管理页面: http://localhost:${PORT}/`);
  if (!ADMIN_TOKEN) {
    console.warn('[警告] 未配置 ADMIN_TOKEN：增删版本 / 指定 latest 的接口已禁用（503）');
  }
  console.log(`版本文件: ${VERSION_FILE}`);
  console.log(`简介表: ${INTRO_FILE}`);
});

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

function shutdown(signal) {
  console.log(`收到 ${signal}，正在关闭...`);
  server.close(() => process.exit(0));

  setTimeout(() => process.exit(1), 10000).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

module.exports = app;
