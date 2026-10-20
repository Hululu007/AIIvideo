#!/bin/sh
# 组装 pie.js：crypto.js（纯 JS 密码学） + site.js（站点逻辑），把两个 PEM 常量填真值。
set -e
cd "$(dirname "$0")"
node - <<'NODE'
const fs = require('fs');
const out = '../../android/app/src/test/resources/spiders/pie.js';
const cryptoJs = fs.readFileSync('crypto.js', 'utf8');
const site = fs.readFileSync('site.js', 'utf8');
const client = fs.readFileSync('assets/client_private.key', 'utf8').trim();
const service = fs.readFileSync('assets/service_public.key', 'utf8').trim();
const esc = s => s.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, '\\n');
const body = '// 水果派（pie）spider 源 —— 内置 RSA/AES 信封实现（无外部依赖，只用 BigInt/typed array）。\n'
  + '// 生成方式：work/re/pie/build.sh（crypto.js + site.js），别手改这个文件。\n\n'
  + cryptoJs + '\n\n'
  + site.replace('__CLIENT_KEY_PEM__', esc(client)).replace('__SERVICE_KEY_PEM__', esc(service));
fs.writeFileSync(out, body);
console.log('wrote ' + out + ' ' + body.length + ' bytes');
NODE
