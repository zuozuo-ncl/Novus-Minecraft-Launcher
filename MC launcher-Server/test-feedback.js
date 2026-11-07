// 极简 SMTP 服务器与 MIME 解码：供 test-feedback-api.js 使用
const net = require('net');

const CRLF = '\r\n';
const CRLF2 = CRLF + CRLF;
const CRLF_RE = /\r?\n/;

function startSmtp({ port = 0 } = {}) {
  const mails = [];
  const server = net.createServer((sock) => {
    let inData = false;
    let buf = '';
    let mail = { from: '', to: [], data: '' };
    const reply = (s) => sock.write(s + CRLF);
    reply('220 fake-smtp');
    sock.on('data', (chunk) => {
      buf += chunk.toString('utf8');
      let idx;
      while ((idx = buf.indexOf(CRLF)) >= 0) {
        const line = buf.slice(0, idx);
        buf = buf.slice(idx + CRLF.length);
        if (inData) {
          if (line === '.') {
            inData = false;
            mails.push(mail);
            mail = { from: '', to: [], data: '' };
            reply('250 OK queued');
          } else {
            mail.data += line + CRLF;
          }
          continue;
        }
        const cmd = line.toUpperCase();
        if (cmd.startsWith('EHLO') || cmd.startsWith('HELO')) {
          reply('250-fake-smtp');
          reply('250 AUTH PLAIN LOGIN');
        } else if (cmd.startsWith('AUTH')) {
          reply('235 Authentication successful');
        } else if (cmd.startsWith('MAIL FROM')) {
          mail.from = line.slice(line.indexOf(':') + 1).trim();
          reply('250 OK');
        } else if (cmd.startsWith('RCPT TO')) {
          mail.to.push(line.slice(line.indexOf(':') + 1).trim());
          reply('250 OK');
        } else if (cmd === 'DATA') {
          inData = true;
          reply('354 End data with <CR><LF>.<CR><LF>');
        } else if (cmd === 'QUIT') {
          reply('221 Bye');
          sock.end();
        } else {
          reply('250 OK');
        }
      }
    });
  });
  return new Promise((resolve) => {
    server.listen(port, '127.0.0.1', () => resolve({ server, port: server.address().port, mails }));
  });
}

const CTE_RE = /Content-Transfer-Encoding:\s*(\S+)/i;

/** 解码 MIME 邮件里各分段的正文（base64 / quoted-printable）。 */
function decodeBody(raw) {
  const lines = raw.split(CRLF_RE);
  const chunks = [];
  let enc = null;
  let body = [];

  const flush = () => {
    if (!enc) { body = []; return; }
    const joined = body.join('\n');
    if (enc === 'base64') {
      chunks.push(Buffer.from(joined.replace(/\s+/g, ''), 'base64').toString('utf8'));
    } else if (enc === 'quoted-printable') {
      const bytes = joined
          .replace(/=\n/g, '')
          .replace(/=([0-9A-F]{2})/gi, (_, h) => String.fromCharCode(parseInt(h, 16)));
      chunks.push(Buffer.from(bytes, 'latin1').toString('utf8'));
    } else {
      chunks.push(joined);
    }
    enc = null;
    body = [];
  };

  for (const line of lines) {
    const cte = CTE_RE.exec(line);
    if (cte) {
      flush();
      enc = cte[1].toLowerCase();
      continue;
    }
    if (enc === null) continue;
    if (line.trim().startsWith('--')) continue;             // boundary
    if (line.trim() === '' && body.length === 0) continue;  // 头/正文分界
    body.push(line);
  }
  flush();
  return chunks.join('\n');
}

/** 邮件头区（第一个空行之前）是否包含某字段 —— 用于判断 SMTP 头注入 */
function hasHeader(raw, name) {
  const head = raw.split(CRLF2)[0];
  return new RegExp('^' + name + ':', 'im').test(head);
}

module.exports = { startSmtp, decodeBody, hasHeader };