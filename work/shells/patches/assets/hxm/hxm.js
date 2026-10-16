/* AGE动漫 播放层的加解密器。
 *
 * 对面把签名逻辑编成了一个 307KB 的 TinyGo wasm（原始字节留着 1.16 的 goroot 路径），
 * 密钥常量查表 + 逐字节比较域名，抠不出来——所以直接把它的 wasm 请进来当黑盒用。
 * 只做纯计算，不碰网络。
 *
 * 关键：window.Domain 必须在 go.run() 之前设好，它是初始化时读一次，
 * 而且必须是精确的 "jx.wuzhoupai.com"（差一个字符就落到另一套密钥上）。
 *
 * wasm 字节以 base64 内联在 hxm.wasm.js 里，不用 fetch：资源处理器按扩展名分派 MIME，
 * .wasm 不在它的表里；走 <script> 也就不用动 CSP 的 connect-src。
 */
(function () {
  const DOMAIN = 'jx.wuzhoupai.com';
  let booting = null;

  function boot() {
    if (booting) return booting;
    booting = new Promise(function (resolve, reject) {
      if (typeof Go !== 'function') { reject(new Error('wasm 胶水没加载')); return; }
      if (typeof WebAssembly === 'undefined') { reject(new Error('这个 WebView 不支持 WebAssembly')); return; }
      if (typeof window.HXM_WASM_B64 !== 'string') { reject(new Error('hxm.wasm.js 没加载')); return; }
      (function () {
        const bin = atob(window.HXM_WASM_B64);
        const buf = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
        const go = new Go();
        return WebAssembly.instantiate(buf, go.importObject).then(function (res) {
          window.Domain = DOMAIN;           // ← 必须在 go.run 之前
          go.run(res.instance).catch(function () {});   // Go 的 main 不结束，挂着是正常的
          setTimeout(function () {
            if (typeof window.hxm_encrypt !== 'function' || typeof window.hxm_decrypt !== 'function') {
              reject(new Error('wasm 起来了但没导出 hxm_encrypt / hxm_decrypt'));
            } else {
              resolve();
            }
          }, 60);
        });
      })().catch(reject);
    });
    return booting;
  }

  window.HG_HXM = {
    ready: boot,
    encrypt: function (s) { return boot().then(function () { return String(window.hxm_encrypt(String(s))); }); },
    decrypt: function (s) { return boot().then(function () { return String(window.hxm_decrypt(String(s))); }); }
  };
})();
