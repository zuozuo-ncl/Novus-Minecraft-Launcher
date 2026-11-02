const http = require('http');

function request(method, path, data) {
  return new Promise((resolve, reject) => {
    const postData = data ? JSON.stringify(data) : '';
    const options = {
      hostname: 'localhost',
      port: 5003,
      path: path,
      method: method,
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(postData)
      }
    };
    
    const req = http.request(options, (res) => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        try {
          resolve(JSON.parse(body));
        } catch {
          resolve(body);
        }
      });
    });
    
    req.on('error', reject);
    if (postData) req.write(postData);
    req.end();
  });
}

function postJSON(path, data) {
  return request('POST', path, data);
}

function putJSON(path, data) {
  return request('PUT', path, data);
}

function getJSON(path) {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: 'localhost',
      port: 5003,
      path: path,
      method: 'GET'
    };
    
    const req = http.request(options, (res) => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        try {
          resolve(JSON.parse(body));
        } catch {
          resolve(body);
        }
      });
    });
    
    req.on('error', reject);
    req.end();
  });
}

async function test() {
  console.log('=== 测试 API ===\n');
  
  // 1. 检查更新 - 当前版本是最新
  console.log('1. 检查更新 (当前版本 1.0.0 - 最新):');
  let result = await postJSON('/api/check-update', { currentVersion: '1.0.0' });
  console.log(JSON.stringify(result, null, 2));
  
  // 2. 获取所有版本
  console.log('\n2. 获取所有版本:');
  result = await getJSON('/api/versions');
  console.log(JSON.stringify(result, null, 2));
  
  // 3. 添加新版本 1.1.0
  console.log('\n3. 添加新版本 1.1.0:');
  result = await postJSON('/api/versions', { version: '1.1.0' });
  console.log(JSON.stringify(result, null, 2));
  
  // 4. 检查更新 - 旧版本 1.0.0
  console.log('\n4. 检查更新 (当前版本 1.0.0 - 有新版本 1.1.0):');
  result = await postJSON('/api/check-update', { currentVersion: '1.0.0' });
  console.log(JSON.stringify(result, null, 2));
  
  // 5. 检查更新 - 最新版本 1.1.0
  console.log('\n5. 检查更新 (当前版本 1.1.0 - 最新):');
  result = await postJSON('/api/check-update', { currentVersion: '1.1.0' });
  console.log(JSON.stringify(result, null, 2));
  
  // 6. 设置 1.1.0 为最新（已经是）
  console.log('\n6. 设置 1.1.0 为最新版本:');
  result = await putJSON('/api/versions/latest', { version: '1.1.0' });
  console.log(JSON.stringify(result, null, 2));
  
  // 7. 添加版本 2.0.0
  console.log('\n7. 添加版本 2.0.0:');
  result = await postJSON('/api/versions', { version: '2.0.0' });
  console.log(JSON.stringify(result, null, 2));
  
  // 8. 设置 2.0.0 为最新
  console.log('\n8. 设置 2.0.0 为最新版本:');
  result = await putJSON('/api/versions/latest', { version: '2.0.0' });
  console.log(JSON.stringify(result, null, 2));
  
  // 9. 检查更新 - 版本 1.1.0
  console.log('\n9. 检查更新 (当前版本 1.1.0 - 有新版本 2.0.0):');
  result = await postJSON('/api/check-update', { currentVersion: '1.1.0' });
  console.log(JSON.stringify(result, null, 2));
  
  // 10. 删除版本 1.0.0
  console.log('\n10. 删除版本 1.0.0:');
  const delOptions = {
    hostname: 'localhost',
    port: 5003,
    path: '/api/versions/1.0.0',
    method: 'DELETE'
  };
  result = await new Promise((resolve, reject) => {
    const req = http.request(delOptions, (res) => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        try {
          resolve(JSON.parse(body));
        } catch {
          resolve(body);
        }
      });
    });
    req.on('error', reject);
    req.end();
  });
  console.log(JSON.stringify(result, null, 2));
  
  console.log('\n=== 测试完成 ===');
}

test().catch(console.error);