const fs = require('fs'), crypto = require('crypto'), https = require('https');
require('/tmp/agedm/shim.cjs');
global.window.Domain = 'jx.wuzhoupai.com';
require('node:vm').runInThisContext(fs.readFileSync('/tmp/agedm/tinygo_exec.js', 'utf8'));

const HOST = 'jx.wuzhoupai.com';
const CAPTURED_URL = '48DFEC7E15BB93F5264C29B86644172DB7FBABC87FD30046C521C3A4BDF771B16A97D3D2C1FF718F48DA196A72DB059D';
// 浏览器里那条成功响应的 Data（原文）
const BROWSER_DATA = '4C1C3E16CDAE65AD686123ED7AF52861742BC54EAFC5162FAFAF62DCBE102E31941DE0B163C12455C597F0B44BA7850A0DA1C49B2760879C13DEC201EFC2F30135C6C9F597D70BB0C010E3E0C6490816054532F120204E33F65E8529638F88CB4AB243D4702FCB8B64D193B48CE60F95D022947A53250C98DA7C28F287E061A18469161FD3F7AFE6794810AC2AE2992CDFDA633E35475CB794B6BA61DFCFB2180C14687F277EA66882E484CAD719FDEA0D22AAFB39135E1F7521B4B473A3D57C6F5480498B58EC11EC90359FC98A4C66E3D23820D47F71CE9B17E3D47AB3906362B2BE817AE1592C4B7CA3B1DD772F8B744F828EBA7836826E4BCCE11255FA01DFF414DA5C6074134FB4900284CEE5AFE2419556E582EA1E4F4F8BEE47CAC3AC218F13B2CA9A538CE2887F302A2DC8FC2312A5EFC4DA8814BE6D590CF6CA53F89BC7C97AC72E8F0C2BE5FD45F58F396142F1A5398497869E717F5715085D27F8460375CAF3A1988A9E82C77B6AA0349F930E2C0BAB3C134D5F55CB55CBD26C41F393DFCFB95CD1D9136894C876DDECFBEC0E898DCAF1B5E5C297992F75B7046AE367B3858930512A5CFE0114DE4DAA812C1BC2B64BBF0FD58B704F33B8762D4D1053776E6DE3AC8FFCD8850E839F0739';

function post(body, headers) {
  return new Promise((res, rej) => {
    const req = https.request({ hostname: HOST, port: 8443, path: '/vip/Api.php', method: 'POST',
      rejectUnauthorized: false,
      headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'X-Requested-With': 'XMLHttpRequest', 'Origin': `https://${HOST}:8443`,
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36',
        ...headers } },
      r => { let d = ''; r.on('data', c => d += c); r.on('end', () => res(d)); });
    req.on('error', rej); req.write(body); req.end();
  });
}

const go = new global.Go();
(async () => {
  const { instance } = await WebAssembly.instantiate(fs.readFileSync('jx.wuzhoupai.com.wasm'), go.importObject);
  go.run(instance).catch(() => {});
  await new Promise(r => setTimeout(r, 1000));
  const enc = globalThis.hxm_encrypt, dec = globalThis.hxm_decrypt;

  console.log('=== 先看浏览器那条 Data 能不能在我们这边解开 ===');
  console.log('  长度', BROWSER_DATA.length, '(必须是 32 的倍数:', BROWSER_DATA.length % 32 === 0, ')');
  try { console.log('  解密 →', String(dec(BROWSER_DATA)).slice(0, 100)); }
  catch (e) { console.log('  失败:', e.message.slice(0, 80)); }

  console.log('\n=== 用不同 url 字段发请求，看 Code ===');
  const time = String(Math.floor(Date.now() / 1000));
  const uuid = crypto.randomUUID().toUpperCase();
  for (const [label, urlField] of [
      ['捕获的 url', CAPTURED_URL],
      ['enc(随机uuid)', enc(crypto.randomUUID().toUpperCase()).toUpperCase()],
      ['空', ''],
  ]) {
    const json = JSON.stringify({ url: urlField, wap: '0', ios: '0', host: HOST, referer: '', time });
    const Params = enc(json).toUpperCase();
    const sign = enc(`${HOST} | ${uuid} | ${time} | V3.2 | ${Params}`).toUpperCase();
    const raw = await post('Params=' + Params, { 'video-parse-sign': sign, 'video-parse-time': time,
      'video-parse-uuid': uuid, 'video-parse-version': 'V3.2' });
    const j = JSON.parse(raw);
    let out = '';
    if (j.Data) { try { out = String(dec(j.Data)); } catch (e) { out = 'ERR ' + e.message.slice(0, 40); } }
    console.log(`  ${label.padEnd(18)} Code=${j.Code} Data=${j.Data ? j.Data.length : 0} → ${out.slice(0, 90)}`);
  }
})();
