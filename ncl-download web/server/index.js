const express = require('express');
const mime = require('mime');
const path = require('path');
const fs = require('fs');

const app = express();
const PORT = process.env.PORT || 3000;
const PUBLIC_DIR = path.join(__dirname, '..');
const BOOTSTRAPPER_DIR = path.join(PUBLIC_DIR, 'bootstrapper');

app.use(express.static(PUBLIC_DIR));
app.use(express.json({ limit: '10mb' }));

app.use((req, res, next) => {
    res.setHeader('Cache-Control', 'public, max-age=86400');
    next();
});

app.get('/api/bootstrapper', (req, res) => {
    const files = fs.readdirSync(BOOTSTRAPPER_DIR).filter(f => f.endsWith('.exe'));
    const entries = files.map(f => {
        const fullPath = path.join(BOOTSTRAPPER_DIR, f);
        const stat = fs.statSync(fullPath);
        return {
            name: f.replace('.exe', ''),
            path: `/bootstrapper/${f}`,
            size: stat.size,
            sizeHuman: formatSize(stat.size),
            modified: stat.mtime.toISOString()
        };
    });
    res.json({ success: true, count: entries.length, entries });
});

app.get('/health', (req, res) => {
    res.json({ status: 'ok', uptime: process.uptime(), time: new Date().toISOString() });
});

app.use('/bootstrapper', express.static(BOOTSTRAPPER_DIR, {
    maxAge: '1d',
    dotfile: 'ignore'
}));

app.use((req, res) => {
    res.status(404).send('404 - Not Found');
});

app.use((err, req, res, next) => {
    console.error(err.stack);
    res.status(500).send('服务器内部错误');
});

app.listen(PORT, () => {
    console.log(`服务器运行在 http://localhost:${PORT}`);
    console.log(`静态文件服务: ${PUBLIC_DIR}`);
    console.log(`引导器目录: ${BOOTSTRAPPER_DIR}`);
});

function formatSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}
