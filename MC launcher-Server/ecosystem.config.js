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
      env: {
        NODE_ENV: 'production',
        PORT: 5003,

        ADMIN_TOKEN: process.env.ADMIN_TOKEN || ''
      },
      env_production: {
        NODE_ENV: 'production',
        PORT: 5003,

        ADMIN_TOKEN: process.env.ADMIN_TOKEN || ''
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

      autorestart: true,

      daemon: true,

      instance_var: 'INSTANCE_ID'
    }
  ],

  
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
