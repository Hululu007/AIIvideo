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

  async function pageVars(token, tries, note) {
    const total = tries || 6;
    // ── 为什么要有总预算 ──────────────────────────────────────────────
    // Java 那边单次请求的读写超时是 15 秒，而这里最多重试 6 次 —— 光算次数的话
    // 最坏是 6 × 15s ≈ 90 秒，中间一个字都不变。用户看到的就是「一直在加载」。
    //
    // 封在 12 秒的**墙上时间**上，并且每次把进度报出去。报进度和封顶一样重要：
    // 转圈转到一半会说话，人就不会以为卡死了。
    const started = Date.now();
    const BUDGET = 12000;
    let last = '';
    for (let i = 0; i < total; i++) {
      if (i > 0 && Date.now() - started > BUDGET) break;
      if (note) note('取播放页… ' + (i + 1) + '/' + total);
      // token 自带 %2F，原样拼进地址
      const html = await window.HG_HTTP.get(JX + '/vip/?url=' + token);
      const gv = k => {
        const m = html.match(new RegExp('var\\s+' + k + '\\s*=\\s*"([^"]*)"'));
        return m ? m[1] : '';
      };
      const ids = [...html.matchAll(/<meta[^>]*\sid="([^"]+)"/g)].map(m => m[1]);
      const Vurl = gv('Vurl');
      // 服务端认得 token 时给的 Vurl 是「加密后的 token」，长度是 32 的整数倍且 >= 96；
      // 认不出来时丢一个 32 位的占位符回来 —— 那种重试有意义，它自己会换节点重算。
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

    const pv = await pageVars(token, 6, note);

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

    // Code=10 是唯一「服务端受理了」的取值。别的值（实测 9）会给一段**用 Code
    // 自己推出来的密钥解不开的** Data —— 384 位密文，换八种 key 材料全都不对，
    // 说明那就是它自己的错误载荷，不是给我们解的东西。
    //
    // 早先这里不管 Code，直接往下解，于是失败信息是「解析结果解不开（Code=9）」：
    // 技术上没错，但对用户等于没说。现在把它翻成人话 —— 而且**这条线路到此为止**，
    // 交给 resolveAny 去试下一条。
    if (String(body.Code) !== '10') {
      const dead = new Error('这条线路服务端没受理（Code=' + body.Code + '）');
      dead.lineDead = true;
      throw dead;
    }

    const material = String(body.Code) + pv.ids.join('').replace('viewport', '') + body.Appkey + pv.Version;
    const md5hex = window.HG_MD5(material);

    note('解密…');
    let info;
    try {
      info = JSON.parse(await aesCbcDecrypt(md5hex, body.Data));
    } catch (e) {
      const dead = new Error('这条线路的解析结果解不开');
      dead.lineDead = true;
      throw dead;
    }
    if (!info.url) {
      const dead = new Error('这条线路没给地址：' + (info.msg || '') + '（code ' + (info.code || '?') + '）');
      dead.lineDead = true;
      throw dead;
    }

    const url = decodeURIComponent(await window.HG_HXM.decrypt(info.url));
    if (!/^https?:/.test(url)) {
      const dead = new Error('这条线路解出来的不是地址');
      dead.lineDead = true;
      throw dead;
    }
    return url;
  }

  /**
   * 按顺序把每条线路试一遍，谁先出 m3u8 用谁。
   *
   * 线路之间是**独立**的：服务端拒了一条，不代表别的也不行。实测「萝球社！OAD」
   * 只有一条 `tt` 线路且那条回 Code=9 —— 这种是真没救，报错就是报错；但大多数片
   * 有 5 条线路，以前只试第一条，第一条挂了后面四条从没被碰过。
   *
   * 逐条试的代价是多花几秒，所以线路名和序号要报出去：用户看到「线路 ffm3u8
   * （2/5）」，就知道这不是卡死，是在一个个试。
   */
  async function resolveAny(lines, onStep) {
    const note = onStep || function () {};
    if (!lines || !lines.length) throw new Error('这部剧没有任何线路能给播放密文');
    let last = '';
    for (let i = 0; i < lines.length; i++) {
      const name = String(lines[i][0] || '');
      const token = String(lines[i][1] || '');
      if (lines.length > 1) note('线路 ' + name + '（' + (i + 1) + '/' + lines.length + '）');
      try {
        return await resolve(token, note);
      } catch (e) {
        last = (e && e.message) ? e.message : String(e);
        if (i + 1 < lines.length) note('『' + name + '』不行，换下一条…');
      }
    }
    throw new Error(last + '；' + lines.length + ' 条线路都试过了');
  }

  window.HG_AGEDM_PLAY = { resolve: resolve, resolveAny: resolveAny };
})();
