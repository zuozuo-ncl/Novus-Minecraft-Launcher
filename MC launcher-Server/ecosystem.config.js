module.exports = {
  apps: [
    {
      name: 'mc-launcher-server',
      script: 'server.js',
      cwd: '/www/wwwroot/MC launcher-Server',
      instances: 1,
      exec_mode: 'fork',
      watch: false,
      max_memory_restart: '500M',
      // 注意：在 ecosystem 文件里显式给出的变量会**遮蔽**同名环境变量。
      // 反馈功能需要的 SMTP_* / TARGET_EMAIL 也必须在这里透传，
      // 否则即便 .env 里写了，pm2 生命周期里也读不到。
      env: {
        NODE_ENV: 'production',
        PORT: 5003,
        // 写接口令牌：务必在服务器上改成随机长串，未设置时增删版本接口会 503
        ADMIN_TOKEN: process.env.ADMIN_TOKEN || '',
        SMTP_HOST: process.env.SMTP_HOST || '',
        SMTP_PORT: process.env.SMTP_PORT || '465',
        SMTP_SECURE: process.env.SMTP_SECURE || 'true',
        SMTP_USER: process.env.SMTP_USER || '',
        SMTP_PASS: process.env.SMTP_PASS || '',
        TARGET_EMAIL: process.env.TARGET_EMAIL || ''
      },
      env_production: {
        NODE_ENV: 'production',
        PORT: 5003,
        // 写接口令牌：务必在服务器上改成随机长串，未设置时增删版本接口会 503
        ADMIN_TOKEN: process.env.ADMIN_TOKEN || '',
        SMTP_HOST: process.env.SMTP_HOST || '',
        SMTP_PORT: process.env.SMTP_PORT || '465',
        SMTP_SECURE: process.env.SMTP_SECURE || 'true',
        SMTP_USER: process.env.SMTP_USER || '',
        SMTP_PASS: process.env.SMTP_PASS || '',
        TARGET_EMAIL: process.env.TARGET_EMAIL || ''
      },
      error_file: './logs/pm2-error.log',
      out_file: './logs/pm2-out.log',
      log_file: './logs/pm2-combined.log',
      time: true,
      log_date_format: 'YYYY-MM-DD HH:mm:ss Z',
      merge_logs: true,
      max_restarts: 10,
      min_uptime: '10s',
      restart_delay: 5000,
      kill_timeout: 5000,
      listen_timeout: 10000,
      // 自动重启配置
      autorestart: true,
      // 守护进程模式
      daemon: true,
      // 实例 ID
      instance_var: 'INSTANCE_ID'
    }
  ],
  
  // 部署配置 (可选)
  deploy: {
    production: {
      user: 'www',
      host: 'your-server-ip',
      ref: 'origin/main',
      repo: 'git@github.com:your-repo/mc-launcher-server.git',
      path: '/www/wwwroot/MC launcher-Server',
      'pre-deploy-local': '',
      'post-deploy': 'npm install --production && pm2 reload ecosystem.config.js --env production',
      'pre-setup': ''
    }
  }
};