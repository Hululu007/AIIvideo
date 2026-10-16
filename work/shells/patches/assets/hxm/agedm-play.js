/* AGE动漫 播放层解析。
 *
 * 链路（2026-10 逆出来的，逐环在真机上核对过）：
 *
 *   ① agedm 详情里 playlists[线路][集] = "age_…"     —— 一条密文，不是地址
 *   ② GET  /vip/?url=<token>                        —— 服务端把会话参数嵌在 HTML 里
 *        var Vurl / var Time / var Version + 两个 <meta id="…">
 *   ③ url 字段就是 Vurl 原样（别再做一次加密）
 *      Params = hxm_encrypt(JSON{url,wap,ios,host,referer,time})
 *      sign   = hxm_encrypt("host | uuid | time | V3.2 | <完整 Params>")
 *   ④ POST /vip/Api.php  →  {Status,Appkey,Version,Code,Data}
 *   ⑤ 密钥 = md5(Code + id1 + id2去掉"viewport" + Appkey + Version)
 *        key = 前 16 个字符（当 ASCII 字节用）  iv = 后 16 个字符
 *      AES-128-CBC(Data) → {"url":"<hex>"}
 *   ⑥ m3u8 = decodeURIComponent( hxm_decrypt(json.url) )
 *
 * 踩过的坑：
 *   - token 里已经带了 %2F，地址里不能再 encodeURIComponent 一次，否则服务端
 *     解析不出来，只丢一个 32 位的占位 Vurl 回来
 *   - sign 里的 Params 是完整的，截断会拿 Code=4
 *   - Time 要用页面下发的那个，自己的时间戳会拿 Code=1
 *   - 网络走 Java 桥（HG_HTTP），直连会被 CORS 的预检拦掉
 *   - url 字段用 Vurl 原样，再加密一次拿 Code=1
 */
(function () {
  const JX = 'https://jx.wuzhoupai.com:8443';
  const HOST = 'jx.wuzhoupai.com';
  const UA = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36';

  const b64 = new Uint8Array(16);
  function uuid() {
    crypto.getRandomValues(b64);
    const h = [];
    for (let i = 0; i < 16; i++) h.push(b64[i].toString(16).padStart(2, '0'));
    return (h.slice(0, 4).join('') + '-' + h.slice(4, 6).join('') + '-' + h.slice(6, 8).join('') + '-'
      + h.slice(8, 10).join('') + '-' + h.slice(10, 16).join('')).toUpperCase();
  }

  async function pageVars(token, tries) {
    let last = '';
    for (let i = 0; i < (tries || 6); i++) {
      // token 自带 %2F，原样拼进地址
      const html = await window.HG_HTTP.get(JX + '/vip/?url=' + token);
      const gv = k => {
        const m = html.match(new RegExp('var\\s+' + k + '\\s*=\\s*"([^"]*)"'));
        return m ? m[1] : '';
      };
      const ids = [...html.matchAll(/<meta[^>]*\sid="([^"]+)"/g)].map(m => m[1]);
      const Vurl = gv('Vurl');
      // 服务端认得 token 时给的 Vurl 是「加密后的 token」，长度是 32 的整数倍且 >= 96；
      // 认不出来时丢一个 32 位的占位符回来
      if (Vurl && Vurl.length >= 96 && Vurl.length % 32 === 0) {
        return { Vurl, Time: gv('Time'), Version: gv('Version'), ids };
      }
      last = Vurl;
      await new Promise(r => setTimeout(r, 700));
    }
    throw new Error('播放页没给出有效会话参数（Vurl 长度 ' + (last ? last.length : 0) + '）');
  }

  async function aesCbcDecrypt(md5hex, hexCipher) {
    const enc = new TextEncoder();
    const key = await crypto.subtle.importKey('raw', enc.encode(md5hex.slice(0, 16)), 'AES-CBC', false, ['decrypt']);
    const iv = enc.encode(md5hex.slice(16, 32));
    const ct = new Uint8Array(hexCipher.length / 2);
    for (let i = 0; i < ct.length; i++) ct[i] = parseInt(hexCipher.substr(i * 2, 2), 16);
    const pt = await crypto.subtle.decrypt({ name: 'AES-CBC', iv }, key, ct);
    return new TextDecoder().decode(pt);
  }

  async function resolve(token, onStep) {
    const note = onStep || function () {};
    if (!token || token.indexOf('age_') !== 0) throw new Error('不是 age_ 开头的播放密文');

    note('取播放页…');
    const pv = await pageVars(token, 6);

    note('算签名…');
    const Params = await window.HG_HXM.encrypt(JSON.stringify({
      url: pv.Vurl, wap: '0', ios: '0', host: HOST, referer: '', time: pv.Time
    })).then(s => s.toUpperCase());
    const id = uuid();
    const sign = await window.HG_HXM.encrypt(
      HOST + ' | ' + id + ' | ' + pv.Time + ' | ' + pv.Version + ' | ' + Params
    ).then(s => s.toUpperCase());

    note('请求解析…');
    const raw = await window.HG_HTTP.post(JX + '/vip/Api.php', 'Params=' + Params, {
      'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
      'X-Requested-With': 'XMLHttpRequest',
      'Accept': 'application/json, text/javascript, */*; q=0.01',
      'Video-Parse-Sign': sign, 'Video-Parse-Time': pv.Time,
      'Video-Parse-Uuid': id, 'Video-Parse-Version': pv.Version
    });
    let body;
    try { body = JSON.parse(raw); } catch (e) { throw new Error('解析接口返回的不是 JSON：' + String(raw).slice(0, 80)); }

    const material = String(body.Code) + pv.ids.join('').replace('viewport', '') + body.Appkey + pv.Version;
    const md5hex = window.HG_MD5(material);

    note('解密…');
    let info;
    try {
      info = JSON.parse(await aesCbcDecrypt(md5hex, body.Data));
    } catch (e) {
      throw new Error('解析结果解不开（Code=' + body.Code + '）');
    }
    // code 200 / msg「数据正常」是成功；拿不到 url 才是失败
    if (!info.url) throw new Error('对面没给地址：' + (info.msg || '') + '（code ' + (info.code || '?') + '）');

    const url = decodeURIComponent(await window.HG_HXM.decrypt(info.url));
    if (!/^https?:/.test(url)) throw new Error('解出来的不是地址：' + String(url).slice(0, 60));
    return url;
  }

  window.HG_AGEDM_PLAY = { resolve };
})();
