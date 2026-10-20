// crypto-test.js — verifies work/re/pie/crypto.js inside a bare vm sandbox
// (no Buffer/require/atob/TextEncoder injected) and cross-checks every result
// against node's own crypto module. Live API calls hit the sandbox fixture.
'use strict';

const fs = require('fs');
const vm = require('vm');
const path = require('path');
const nc = require('crypto');
const https = require('https');

const DIR = __dirname;
const PK = (f) => path.join(DIR, 'assets', f);

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('PASS ' + name); }
  else { fail++; console.log('FAIL ' + name + (extra ? '  [' + extra + ']' : '')); }
}
function eq(name, a, b) {
  const sa = typeof a === 'string' ? a : Buffer.from(a).toString('hex');
  const sb = typeof b === 'string' ? b : Buffer.from(b).toString('hex');
  ok(name, sa === sb, sa.slice(0, 60) + ' != ' + sb.slice(0, 60));
}

// ---------------------------------------------------------------- sandbox
const src = fs.readFileSync(path.join(DIR, 'crypto.js'), 'utf8');
const sandbox = {};
const ctx = vm.createContext(sandbox);
vm.runInContext(src, ctx, { filename: 'crypto.js' });
const H = ctx.HGCrypto;

console.log('--- sandbox fidelity ---');
ok('HGCrypto exported into sandbox global', !!H);
for (const g of ['Buffer', 'require', 'process', 'module', 'atob', 'btoa', 'TextEncoder', 'crypto']) {
  console.log('  info sandbox typeof ' + g + ' = ' + vm.runInContext('typeof globalThis.' + g, ctx));
}
ok('no Buffer in sandbox', vm.runInContext('typeof globalThis.Buffer', ctx) === 'undefined');
ok('no require in sandbox', vm.runInContext('typeof globalThis.require', ctx) === 'undefined');
ok('no atob/btoa in sandbox', vm.runInContext('typeof globalThis.atob+typeof globalThis.btoa', ctx) === 'undefinedundefined');

// ---------------------------------------------------------------- base64
console.log('--- base64 ---');
const rnd = nc.randomBytes(200);
const b64py2 = vm.runInContext('HGCrypto.b64encode(arr)', Object.assign(ctx, { arr: Array.from(rnd) }));
eq('b64encode matches Buffer', b64py2, rnd.toString('base64'));
const back = vm.runInContext('Array.from(HGCrypto.b64decode(s))', Object.assign(ctx, { s: rnd.toString('base64') }));
eq('b64decode matches Buffer', Buffer.from(back), rnd);
const b64short = vm.runInContext('HGCrypto.b64encode(arr)', Object.assign(ctx, { arr: [1, 2] }));
eq('b64encode 2-byte tail', b64short, Buffer.from([1, 2]).toString('base64'));
const ws = Buffer.from(vm.runInContext('Array.from(HGCrypto.b64decode("aGVs\\n bG8="))', ctx)).toString();
ok('b64decode tolerates whitespace', ws === 'hello', ws);
const url = Buffer.from(vm.runInContext('Array.from(HGCrypto.b64decode("Pz8_"))', ctx)).toString();
ok('b64decode accepts URL-safe alphabet', url === '???', url);

// ---------------------------------------------------------------- utf8
console.log('--- utf8 ---');
const s = 'hello 黄果 🍆 测试\u0000end';
eq('utf8Encode matches Buffer', Buffer.from(vm.runInContext('Array.from(HGCrypto.utf8Encode(x))', Object.assign(ctx, { x: s }))), Buffer.from(s, 'utf8'));
const dec = vm.runInContext('HGCrypto.utf8Decode(arr)', Object.assign(ctx, { arr: Array.from(Buffer.from(s, 'utf8')) }));
ok('utf8Decode round-trip', dec === s, JSON.stringify(dec));

// ---------------------------------------------------------------- AES
console.log('--- AES-CBC ---');
for (const klen of [16, 24, 32]) {
  const key = nc.randomBytes(klen), iv = nc.randomBytes(16);
  const pt = Buffer.from('the quick brown fox jumps over 狗 ' + klen);
  const c = nc.createCipheriv('aes-' + (klen * 8) + '-cbc', key, iv);
  const nodeCt = Buffer.concat([c.update(pt), c.final()]);
  const oursCt = Buffer.from(vm.runInContext('Array.from(HGCrypto.aesCbcEncrypt(k,iv,d))',
    Object.assign(ctx, { k: Array.from(key), iv: Array.from(iv), d: Array.from(pt) })));
  eq('AES-' + klen * 8 + ' encrypt == node', oursCt, nodeCt);
  const oursPt = Buffer.from(vm.runInContext('Array.from(HGCrypto.aesCbcDecrypt(k,iv,d))',
    Object.assign(ctx, { k: Array.from(key), iv: Array.from(iv), d: Array.from(nodeCt) })));
  eq('AES-' + klen * 8 + ' decrypt(node ct) == plaintext', oursPt, pt);
  // no-pad round trip
  const raw = nc.randomBytes(64);
  const npEnc = Buffer.from(vm.runInContext('Array.from(HGCrypto.aesCbcEncrypt(k,iv,d,{pad:false}))',
    Object.assign(ctx, { k: Array.from(key), iv: Array.from(iv), d: Array.from(raw) })));
  const nd = nc.createCipheriv('aes-' + (klen * 8) + '-cbc', key, iv); nd.setAutoPadding(false);
  eq('AES-' + klen * 8 + ' unpad encrypt == node', npEnc, Buffer.concat([nd.update(raw), nd.final()]));
  const npDec = Buffer.from(vm.runInContext('Array.from(HGCrypto.aesCbcDecrypt(k,iv,d,{unpad:false}))',
    Object.assign(ctx, { k: Array.from(key), iv: Array.from(iv), d: Array.from(npEnc) })));
  eq('AES-' + klen * 8 + ' unpad decrypt', npDec, raw);
}

// ---------------------------------------------------------------- RSA cross-check
console.log('--- RSA ---');
const privPem = fs.readFileSync(PK('client_private.key'), 'utf8');
const pubPem = fs.readFileSync(PK('service_public.key'), 'utf8');
const derivedPubPem = nc.createPublicKey(nc.createPrivateKey(privPem)).export({ type: 'spki', format: 'pem' });

// node encrypt -> our decrypt
for (let i = 0; i < 4; i++) {
  const msg = nc.randomBytes(1 + i * 40);
  const ct = nc.publicEncrypt({ key: derivedPubPem, padding: nc.constants.RSA_PKCS1_PADDING }, msg);
  const out = Buffer.from(vm.runInContext('Array.from(HGCrypto.rsaDecryptPKCS1v15(pem, ct))',
    Object.assign(ctx, { pem: privPem, ct: Array.from(ct) })));
  eq('rsaDecrypt len=' + msg.length, out, msg);
}
// our encrypt -> node decrypt
for (const rngKind of ['mathrandom', 'injected']) {
  const msg = Buffer.from('{"key":"abc","iv":"def"}');
  const opts = rngKind === 'injected' ? '{ rng: function(n){ var b=new Uint8Array(n); for (var i=0;i<n;i++) b[i]=1+((i*37+11)%255); return b; } }' : '';
  const ct = Buffer.from(vm.runInContext('Array.from(HGCrypto.rsaEncryptPKCS1v15(pem, msg, ' + (opts || 'undefined') + '))',
    Object.assign(ctx, { pem: derivedPubPem, msg: Array.from(msg) })));
  ok('rsaEncrypt len 256 (' + rngKind + ')', ct.length === 256, String(ct.length));
  const backMsg = nc.privateDecrypt({ key: privPem, padding: nc.constants.RSA_PKCS1_PADDING }, ct);
  eq('rsaEncrypt->node decrypt (' + rngKind + ')', backMsg, msg);
  // PS must be non-zero-padded correctly: re-check structure
  const em = nc.privateDecrypt({ key: privPem, padding: nc.constants.RSA_NO_PADDING }, ct);
  ok('EM header 00 02 (' + rngKind + ')', em[0] === 0 && em[1] === 2);
}
// PKCS#1 vs PKCS#8 parse of the same key must agree
const privPemPkcs1 = nc.createPrivateKey(privPem).export({ type: 'pkcs1', format: 'pem' });
const ctProbe = nc.publicEncrypt({ key: derivedPubPem, padding: nc.constants.RSA_PKCS1_PADDING }, Buffer.from('x'));
const fromPkcs1 = Buffer.from(vm.runInContext('Array.from(HGCrypto.rsaDecryptPKCS1v15(pem,ct))', Object.assign(ctx, { pem: privPemPkcs1, ct: Array.from(ctProbe) })));
eq('PKCS#1 PEM parses identically', fromPkcs1, Buffer.from('x'));

// ---------------------------------------------------------------- live envelope
function post(urlPath, body) {
  return new Promise((resolve, reject) => {
    const req = https.request({
      host: 'api.3hvkdg.com', port: 443, path: urlPath, method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }
    }, res => {
      const chunks = [];
      res.on('data', d => chunks.push(d));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks) }));
    });
    req.on('error', reject);
    req.write(body); req.end();
  });
}

async function envelopeFlow(label, requestBody) {
  const r = await post('/v2_0/getSingleItem', requestBody);
  ok(label + ' HTTP 200', r.status === 200, String(r.status));
  const env = JSON.parse(r.body.toString('utf8'));
  ok(label + ' envelope has encrypt_key/encrypt_data', !!env.encrypt_key && !!env.encrypt_data);

  // node cross-check
  const kjson = JSON.parse(nc.privateDecrypt({ key: privPem, padding: nc.constants.RSA_PKCS1_PADDING },
    Buffer.from(env.encrypt_key, 'base64')).toString('utf8'));
  const nd = nc.createDecipheriv('aes-128-cbc', Buffer.from(kjson.key, 'base64'), Buffer.from(kjson.iv, 'base64'));
  const nodePlain = Buffer.concat([nd.update(Buffer.from(env.encrypt_data, 'base64')), nd.final()]);

  // our chain
  const ourKjson = vm.runInContext(
    'HGCrypto.utf8Decode(HGCrypto.rsaDecryptPKCS1v15(pem, HGCrypto.b64decode(ek)))',
    Object.assign(ctx, { pem: privPem, ek: env.encrypt_key }));
  const ourK = JSON.parse(ourKjson);
  eq(label + ' rsa key-json matches node', JSON.stringify(ourK), JSON.stringify(kjson));
  const ourPlain = Buffer.from(vm.runInContext(
    'Array.from(HGCrypto.aesCbcDecrypt(HGCrypto.b64decode(k.key), HGCrypto.b64decode(k.iv), HGCrypto.b64decode(ed)))',
    Object.assign(ctx, { k: ourK, ed: env.encrypt_data })));
  eq(label + ' AES payload matches node', ourPlain, nodePlain);
  let parsed = null;
  try { parsed = JSON.parse(ourPlain.toString('utf8')); } catch (e) { }
  ok(label + ' payload is JSON', !!parsed);
  return { env, parsed, nodePlain };
}

(async () => {
  console.log('--- live envelope: captured direction ---');
  const a = await envelopeFlow('[captured]', '{}');
  console.log('  decrypted: code=' + a.parsed.code + ' msg=' + a.parsed.msg);

  console.log('--- live envelope: request direction {"libraryId":1} ---');
  const reqPlay = '{"libraryId":1}';
  const body = vm.runInContext(`(function(){
    var key = new Uint8Array(16), iv = new Uint8Array(16);
    for (var i=0;i<16;i++){ key[i]=(Math.random()*256)&0xff; iv[i]=(Math.random()*256)&0xff; }
    var inner = HGCrypto.utf8Encode('{"key":"'+HGCrypto.b64encode(key)+'","iv":"'+HGCrypto.b64encode(iv)+'"}');
    var ct = HGCrypto.aesCbcEncrypt(key, iv, HGCrypto.utf8Encode(${JSON.stringify(reqPlay)}));
    var ek = HGCrypto.rsaEncryptPKCS1v15(pub, inner);
    return JSON.stringify({ encrypt_key: HGCrypto.b64encode(ek), encrypt_data: HGCrypto.b64encode(ct) });
  })()`, Object.assign(ctx, { pub: pubPem }));
  const b = await envelopeFlow('[request]', body);
  console.log('  decrypted: code=' + b.parsed.code + ' msg=' + b.parsed.msg + ' item=' + (b.parsed.data && b.parsed.data.item ? b.parsed.data.item.library_id : 'n/a'));
  ok('[request] code === 1', b.parsed.code === 1, JSON.stringify(b.parsed).slice(0, 120));

  // ------------------------------------------------------------ timings
  console.log('--- timings ---');
  Object.assign(ctx, { pem: privPem, ct: Array.from(Buffer.from(a.env.encrypt_key, 'base64')) });
  let t0 = process.hrtime.bigint();
  const N = 20;
  for (let i = 0; i < N; i++) vm.runInContext('HGCrypto.rsaDecryptPKCS1v15(pem, ct)', ctx);
  const rsaMs = Number(process.hrtime.bigint() - t0) / 1e6 / N;

  const big = nc.randomBytes(1024);
  const bigCt = Buffer.from(vm.runInContext('Array.from(HGCrypto.aesCbcEncrypt(k,iv,d))',
    Object.assign(ctx, { k: Array.from(Buffer.from('0123456789abcdef')), iv: Array.from(Buffer.alloc(16)), d: Array.from(big) })));
  ok('AES 1024B padding adds 1 block', bigCt.length === 1040, String(bigCt.length));
  Object.assign(ctx, { k: Array.from(Buffer.from('0123456789abcdef')), iv: Array.from(Buffer.alloc(16)), d: Array.from(bigCt) });
  t0 = process.hrtime.bigint();
  const M = 500;
  for (let i = 0; i < M; i++) vm.runInContext('HGCrypto.aesCbcDecrypt(k,iv,d,{unpad:false})', ctx);
  const aesMs = Number(process.hrtime.bigint() - t0) / 1e6 / M;
  const aes1k = Buffer.from(vm.runInContext('Array.from(HGCrypto.aesCbcDecrypt(k,iv,d))', ctx));
  eq('AES 1040B decrypt correctness', aes1k, big);

  console.log('TIMING rsaDecryptPKCS1v15 (2048-bit, CRT) = ' + rsaMs.toFixed(2) + ' ms/op (avg of ' + N + ' in vm)');
  console.log('TIMING aesCbcDecrypt 1040 bytes = ' + aesMs.toFixed(3) + ' ms/op (avg of ' + M + ' in vm)');
  console.log('TIMING aesCbcDecrypt 1040 bytes = ' + (aesMs * 1000).toFixed(1) + ' us/op');

  console.log('--- summary ---');
  console.log('PASS=' + pass + ' FAIL=' + fail);
  process.exit(fail ? 1 : 0);
})();
