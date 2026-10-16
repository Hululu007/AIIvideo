const fs = require('fs');
require('/tmp/agedm/shim.cjs');
global.window.Domain = 'jx.wuzhoupai.com';
require('node:vm').runInThisContext(fs.readFileSync('/tmp/agedm/tinygo_exec.js', 'utf8'));
const go = new global.Go();
const D352 = 'D66B00DF8EE11E814EC574DF8522B446FF1095133B310F45A64E06D8F078B310B54C95C78BFD336193531951AC8A01FD6D7998AB5D19ABCCCF9925E769D0BF0A554F80FE8C50281524624AF2FE2A3EF5D2D806798579E341C00C5552637C0298D17E6DC531A2809B5CE21F6EABA0EAFD81C8CE7B820BD7BBBCDA9504CD8A6AAED89F3AE8D2BD8D8F28549E4AA97A88BE9CD17275E7F308C3C7503421212E83C11B0E30187030C23B8116858D3B1CAAAE';
(async () => {
  const { instance } = await WebAssembly.instantiate(fs.readFileSync('jx.wuzhoupai.com.wasm'), go.importObject);
  go.run(instance).catch(() => {});
  await new Promise(r => setTimeout(r, 1000));
  const enc = globalThis.hxm_encrypt, dec = globalThis.hxm_decrypt;

  console.log('=== 在浏览器里解出过 URL 的那条 Data，长度', D352.length, '===');
  try { console.log('  Node 这边:', String(dec(D352)).slice(0, 110)); }
  catch (e) { console.log('  失败:', e.message.slice(0, 70)); }

  console.log('\n=== 自洽性：自己加密再自己解密 ===');
  const t = enc('hello world');
  console.log('  enc →', t.slice(0, 40), '…');
  console.log('  dec →', String(dec(t)));

  console.log('\n=== encrypt 是否仍与浏览器一致（回归）===');
  console.log('  enc("test") =', enc('test'), enc('test') === 'fbe2789fdb0178f0c0b3358145a2ea62' ? '✓ 与浏览器一致' : '✗ 不一致');
})();
