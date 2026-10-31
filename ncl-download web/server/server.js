const express = require('express');
const mime = require('mime');
const path = require('path');
const fs = require('fs');

const app = express();
const PORT = process.env.PORT || 5005;
const PUBLIC_DIR = path.join('/www/wwwroot/ncl-download-web/public');

// 中间件
app.use(express.static(PUBLIC_DIR));
app.use(express.json({ limit: '10mb' }));

// 设置 MIME 类型
app.use((req, res, next) => {
    res.setHeader('Cache-Control', 'public, max-age=86400');
    next();
});

// 404 处理
app.use((req, res) => {
    res.status(404).send('404 - Not Found');
});

app.get('/', (req, res) => {
    res.sendFile(path.join(PUBLIC_DIR, 'index.html'));
});

app.listen(PORT, () => {
    console.log(`服务器运行在 http://localhost:${PORT}`);
});

function formatSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}