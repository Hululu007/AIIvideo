// age_ token → 真 m3u8。用站点自己的 wasm 当加解密器。
const fs = require('fs'), crypto = require('crypto'), https = require('https');
require('/tmp/agedm/shim.cjs');
global.window.Domain = 'jx.wuzhoupai.com';
require('node:vm').runInThisContext(fs.readFileSync('/tmp/agedm/tinygo_exec.js', 'utf8'));

const ORIG_TOKEN = 'age_9038q%2FPqdy6BkARi0qqhtFM9Y7k7quGi3AFvqEODuajL8DhiCQli9c7JHVd7R7DxnUYcduZjSleSnHL%2Ff3S9ZKQH';
const KNOWN_URL_FIELD = 'F4E1A1EDD7C024331FC1A394C5D9D44489B4442BEC0779B2B85C6BFB6C0A39AFFC142D400482C8F3157358C9FD51FFBE';

function post(url, body, headers) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const req = https.request({ hostname: u.hostname, port: u.port, path: u.pathname,
      method: 'POST', rejectUnauthorized: false,
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'User-Agent': 'Mozilla/5.0', ...headers } },
      res => { let d = ''; res.on('data', c => d += c); res.on('end', () => resolve({ status: res.statusCode, body: d })); });
    req.on('error', reject); req.write(body); req.end();
  });
}

const go = new global.Go();
(async () => {
  const { instance } = await WebAssembly.instantiate(fs.readFileSync('jx.wuzhoupai.com.wasm'), go.importObject);
  go.run(instance).catch(() => {});
  await new Promise(r => setTimeout(r, 1000));
  const enc = globalThis.hxm_encrypt, dec = globalThis.hxm_decrypt;

  // ① 那个 url 字段是怎么来的
  const tok = decodeURIComponent(ORIG_TOKEN);
  const raw = Buffer.from(tok.slice(4), 'base64');
  const cands = {
    'enc(token)': enc(ORIG_TOKEN),
    'enc(decodeURIComponent)': enc(tok),
    'enc(token[4:])': enc(tok.slice(4)),
    'enc(base64 bytes)': enc(raw.toString('binary')),
    'enc(raw hex)': enc(raw.toString('hex')),
  };
  console.log('=== 找 url 字段的来历（目标 ' + KNOWN_URL_FIELD.slice(0, 20) + '…）===');
  for (const [n, v] of Object.entries(cands)) {
    console.log(`  ${v === KNOWN_URL_FIELD ? '✓' : ' '} ${n.padEnd(24)} ${String(v).toUpperCase().slice(0, 40)}… len=${String(v).length}`);
  }
  console.log('  目标长度:', KNOWN_URL_FIELD.length);
  console.log('  token 长度:', ORIG_TOKEN.length, ' 去前缀:', tok.length - 4, ' 解base64:', raw.length, '字节');

  // ② 直接拿捕获到的 url 字段走完流程
  const time = Math.floor(Date.now() / 1000);
  const uuid = crypto.randomUUID().toUpperCase();
  const host = 'jx.wuzhoupai.com';
  const paramsJson = JSON.stringify({ url: KNOWN_URL_FIELD, wap: '0', ios: '0', host, referer: '', time: String(time) });
  const Params = enc(paramsJson).toUpperCase();
  const signSrc = `${host} | ${uuid} | ${time} | V3.2 | ${Params.slice(0, 300)}`;
  const sign = enc(signSrc);
  console.log('\n=== 发请求 ===');
  console.log('  Params 长度', Params.length, ' sign 长度', sign.length);
  const r = await post('https://jx.wuzhoupai.com:8443/vip/Api.php', 'Params=' + Params, {
    'video-parse-sign': sign, 'video-parse-time': String(time),
    'video-parse-uuid': uuid, 'video-parse-version': 'V3.2',
  });
  console.log('  HTTP', r.status);
  console.log('  ', r.body.slice(0, 200));
  let j; try { j = JSON.parse(r.body); } catch (e) {}
  if (j && j.Data) {
    console.log('\n=== 解密 ===');
    const url = dec(j.Data);
    console.log('  原始:', String(url).slice(0, 120));
    console.log('  解码:', decodeURIComponent(String(url)));
  }
})();
