// MissAV（missav.live）—— spider 版。
//
// 语义照抄 MissavParser.java（parseList / parseCategories / parseDetail / parsePlay /
// unpackScript），MissavAdapter.java 负责地址拼装。解析全部是纯字符串 + 正则 ——
// 这个站最脆的是播放器里那段 packed JS，而它错了的表现是「详情能打开、播放一直转圈」，
// 没有离线可复现的抓手时根本没法查。所以这段必须在沙箱里跑得起来。
//
// ── Cloudflare：先试，拿到挑战页才报错 ────────────────────────────────
// 这个站在盾后面，但**不是所有出口都拿挑战页**（Mac 上裸 curl 实测 200）。
// 所以这里的态度是：带完整浏览器头直连，拿到内容就正常解析；只有真的识别出
// 挑战页才抛明确的中文错误。判据照 SiteFetchWebView.looksLikeChallenge 的语义
// （Just a moment / challenges.cloudflare.com / cf-browser-verification / _cf_chl_opt），
// 再加上「文本很短且含 challenge」这一条兜底 —— 只看 "Just a moment" 一个词
// 会漏掉 shield 换了文案的情况。
//
// 关键：**不要在挑战页上静默返回空列表**。空列表的语义是「这个站今天没内容」，
// 和「这个站要求人机验证」是两回事，用户看到的结果一样但修法完全相反。
//
// ── 动作映射（对齐 MissavAdapter）────────────────────────────────────
//   home                 → /dm539/cn/new
//   category(slug,page)  → /dm{cateId}/cn/{slug} · /dm{id}/cn/genres/{名字} · /cn/{固定导航}
//   search(q,page)       → /cn/search?q=&page=
//   detail(id)           → /cn/{id}
//   play(id)             → /cn/{id}（从详情页那段 packed JS 里解 m3u8）
(function () {
  var BASE = 'https://missav.live';

  /** 列表页实测 12 张卡（首页）。取它当「还有下一页」的门槛。 */
  var PAGE_SIZE = 12;

  /** 首页那排胶囊：站点首页只有「最近更新」一排，featured 也用它（见 home）。 */
  var FEATURED_LIMIT = 8;

  /** 类型页每页 36 个（实测）。 */
  var GENRE_PAGE_SIZE = 36;

  /** 类型页抓前几页。见 genres 里那段取舍说明。 */
  var GENRE_PAGES = 3;

  /**
   * 浏览器头。缺 UA 会直接被盾挡，缺 Referer 部分图片/接口会 403。
   * 这是「直连也能过」的前提 —— 剥掉 UA 之后同一个地址大概率拿挑战页。
   */
  var HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 '
      + '(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
    'Referer': BASE + '/'
  };

  // ── 取页面 + 挑战页判据 ───────────────────────────────────────────

  /**
   * 挑战页判据（对齐 SiteFetchWebView.looksLikeChallenge 的语义，外加兜底）。
   *
   * 前四条是 Java 那边实测出来的标记：站点换盾版本时具体词会变，但这几个
   * 特征在 Cloudflare 的几种挑战页里至少命中一个。
   * 最后一条「很短 + 含 challenge」是兜底：有些 shield 版本只留一个
   * `<title>Attention Required</title>` + 一段 challenge 脚本，前面四条全不命中。
   */
  function looksLikeChallenge(html) {
    if (!html) return false;
    if (html.indexOf('Just a moment') >= 0) return true;
    if (html.indexOf('challenges.cloudflare.com') >= 0) return true;
    if (html.indexOf('cf-browser-verification') >= 0) return true;
    if (html.indexOf('_cf_chl_opt') >= 0) return true;
    if (html.indexOf('cf-chl') >= 0) return true;
    // 挑战页都很短（几 KB），内容页动辄几十上百 KB。两个条件同时成立才算 ——
    // 只看「含 challenge」会把正常页面里提到这个词的情况误判。
    if (html.length < 12000 && /challenge/i.test(html)) return true;
    return false;
  }

  function challengeError(url) {
    return new Error('这个源需要过 Cloudflare 验证，spider 版暂不支持：' + url
      + ' 拿到的是站点的验证页而不是内容。'
      + '可能原因：出口 IP 被 Cloudflare 判定为可疑（换网络 / 开代理后可以再试），'
      + '或者站点刚把防护等级调高了。');
  }

  async function fetchPage(ctx, url) {
    var res;
    try {
      res = await ctx.call('net', { url: url, headers: HEADERS });
    } catch (e) {
      throw new Error('missav 请求失败 ' + url + '：' + ((e && e.message) || e)
        + '（这个站在 Cloudflare 后面，出口被拦时就是这个样子）');
    }
    if (!res || res.ok !== true) {
      throw new Error('missav 请求失败 ' + url + '：' + ((res && res.error) || '没有响应'));
    }
    var html = String(res.body == null ? '' : res.body);
    if (html.length === 0) {
      throw new Error('missav 返回空页面：' + url + '（可能是盾把响应吞了）');
    }
    // 先试再判：只有真拿到挑战页才报错，不是一上来就抛。
    if (looksLikeChallenge(html)) throw challengeError(url);
    return html;
  }

  function enc(s) {
    return encodeURIComponent(String(s == null ? '' : s));
  }

  // ── 文本工具（对齐 MissavParser.decodeEntities / stripTags / clean）──

  /**
   * HTML 实体解码。**双重编码要解两次**（实测标题里出现过 `&amp;#39;`，
   * 只解一次会留 `&#39;` 字面量）。
   */
  function decodeEntities(s) {
    if (s == null) return '';
    var out = String(s);
    for (var pass = 0; pass < 2; pass++) {
      var before = out;
      out = out.replace(/&lt;/g, '<').replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"').replace(/&#39;/g, "'")
        .replace(/&#039;/g, "'").replace(/&apos;/g, "'")
        .replace(/&#x27;/g, "'").replace(/&nbsp;/g, ' ')
        .replace(/&amp;/g, '&');
      // 数字实体（&#NN; / &#xHH;）
      out = out.replace(/&#x([0-9a-fA-F]{1,6});/g, function (m, hex) {
        try { return String.fromCharCode(parseInt(hex, 16)); } catch (e) { return m; }
      });
      out = out.replace(/&#([0-9]{1,7});/g, function (m, dec) {
        try { return String.fromCharCode(parseInt(dec, 10)); } catch (e) { return m; }
      });
      if (out === before) break;
    }
    return out;
  }

  function stripTags(s) {
    return String(s == null ? '' : s).replace(/<[^>]*>/g, '');
  }

  function clean(s) {
    return String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
  }

  // ── 属性取值（对齐 MissavParser.firstAttr / attrValue）─────────────
  //
  // 双引号 / 单引号 / 无引号三种都吃，并且**用捕获组的第 2~4 组**取值 ——
  // 这跟 Java 那边一致：第 1 组是整个 (…) 包裹，2/3/4 分别是 "…"/'…'/裸值。

  var P_HREF = /href\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))/i;
  var P_IMG_SRC = /<img[^>]*\ssrc\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))/i;
  var P_IMG_DATA_SRC = /<img[^>]*\sdata-src\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))/i;
  var P_ALT = /alt\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))/i;

  function attrValue(m) {
    for (var i = 2; i <= 4; i++) {
      var v = m[i];
      if (v != null && v.length > 0) return v;
    }
    return '';
  }

  function firstAttr(src, re) {
    var m = re.exec(src);
    return m ? attrValue(m) : '';
  }

  // ── 列表页（对齐 MissavParser.parseList）──────────────────────────
  //
  // 卡片切分锚点写成 `<div\b[^>]*class="…thumbnail…"`：**不能假设 class 是第一个属性**。
  // 真实卡片前面挂着 Alpine 的 @mouseenter / @click，写成 `<div\s+class=` 在真实页面上
  // 一张卡都切不出来（2026-10-18 踩过）。\bthumbnail\b 的词边界也是必须的：
  // CSS 里有 taboola-thumbnails-container 这种广告类名，含 thumbnail 子串。
  var P_CARD_SPLIT = /<div\b[^>]*\bclass\s*=\s*"[^"]*\bthumbnail\b[^"]*"/gi;

  /** 时长 `1:16:25` / `45:30`。要求前后不是字母数字，免得命中「FCT-210: 无码」。 */
  var P_DURATION = /(^|[^0-9A-Za-z])((?:\d{1,2}:)?\d{1,2}:\d{2})(?![0-9])/;

  /** 番号：FCT-210 / ABC-123 / 1pondo-123456 这种「字母+连字符+数字」。 */
  var P_CODE = /(^|[^0-9A-Za-z])([A-Za-z]{2,10}-[0-9]{2,7})(?![0-9A-Za-z])/;

  function extractIdFromUrl(url) {
    var s = String(url == null ? '' : url).trim();
    var h = s.indexOf('#');
    if (h >= 0) s = s.substring(0, h);
    var q = s.indexOf('?');
    if (q >= 0) s = s.substring(0, q);
    while (s.length > 0 && s.charAt(s.length - 1) === '/') s = s.substring(0, s.length - 1);
    var slash = s.lastIndexOf('/');
    var last = slash >= 0 ? s.substring(slash + 1) : s;
    return last;
  }

  function extractCode(title) {
    if (!title) return '';
    var m = P_CODE.exec(String(title));
    return m ? m[2].toUpperCase() : '';
  }

  function firstDuration(src) {
    var m = P_DURATION.exec(String(src == null ? '' : src));
    return m ? m[2] : '';
  }

  function titleFrom(card) {
    // 取 class 里带 text-secondary 的那个 a（正文标题链接）。第一个 a 是封面图上的
    // 图片链接，它那条的文本不是标题。
    var m = /<a[^>]*class="[^"]*text-secondary[^"]*"[^>]*>([\s\S]*?)<\/a>/i.exec(card);
    if (m) {
      var t = clean(stripTags(decodeEntities(m[1])));
      if (t) return t;
    }
    // 兜底：class 带 truncate 的容器
    var d = /<div[^>]*class="[^"]*truncate[^"]*"[^>]*>([\s\S]*?)<\/div>/i.exec(card);
    if (d) return clean(stripTags(decodeEntities(d[1])));
    return '';
  }

  function parseOneCard(card) {
    var href = firstAttr(card, P_HREF);
    var id = extractIdFromUrl(href);
    if (!id) return null;

    var out = { id: id };

    // 封面：判据不是「src 为空」，而是「src 是不是一张真图」。站点用 lozad 懒加载，
    // 第 8 张之后的卡片 src 是 1×1 的 base64 占位图（data:image/png;base64,…）——
    // 按「空才试 data-src」写的话，那几张永远拿到占位图，表现成列表尾部一排灰块。
    var cover = firstAttr(card, P_IMG_SRC);
    if (cover.indexOf('data:') === 0) cover = '';
    if (!cover) {
      var dm = P_IMG_DATA_SRC.exec(card);
      if (dm) {
        var v = attrValue(dm);
        if (v.indexOf('data:') !== 0) cover = v;
      }
    }
    out.cover = cover;

    out.duration = firstDuration(card);

    var title = titleFrom(card);
    if (!title) title = clean(firstAttr(card, P_ALT));
    out.title = title;

    var code = extractCode(title);
    if (!code) code = extractCode(firstAttr(card, P_ALT));
    out.code = code;
    return out;
  }

  function parseList(html) {
    var out = [];
    if (!html) return out;
    // 先记下每张卡片正文的起点，再按「下一张的起点」切 —— 不能在一次 find 循环里
    // 用 m.end()：那个值在循环外只是最后一个匹配的终点，会把所有卡片切到同一起点，
    // 列表只剩一张卡。
    var starts = [];
    P_CARD_SPLIT.lastIndex = 0;
    var m;
    while ((m = P_CARD_SPLIT.exec(html)) !== null) {
      starts.push(m.index + m[0].length);
      if (m[0].length === 0) P_CARD_SPLIT.lastIndex++;
    }
    for (var i = 0; i < starts.length; i++) {
      var from = starts[i];
      var to = (i + 1 < starts.length) ? starts[i + 1] : html.length;
      if (to <= from) continue;
      var item = parseOneCard(html.substring(from, to));
      if (item && item.id) out.push(item);
    }
    return out;
  }

  // ── 类型页（对齐 MissavParser.parseCategories）────────────────────
  //
  // 用 `/genres/` 路由定位而不是 class=text-nord13：class 名是 tailwind 调色板语义
  // （nord13/nord10），换主题就全废；`/genres/` 是路由，稳得多。
  var P_GENRE_HREF = /<a[^>]*href\s*=\s*"([^"]*\/genres\/[^"]*)"[^>]*>([\s\S]*?)<\/a>/gi;
  var P_GENRE_COUNT = /([0-9][0-9,，\s]{0,12}?)\s*条影片/;
  var P_GENRE_ID = /\/(dm[0-9]+)(?:\/|$)/i;

  /** 从 href 里取分类 id（dm96）。 */
  function genreId(href) {
    // ★ 同 qualities：P_GENRE_ID 没有 g，不能进 exec 循环（无 g 的 exec 不推进
    //   lastIndex，循环就是死循环）。match 一次拿结果，天然安全。
    var hit = String(href).match(P_GENRE_ID);
    if (!hit) return '';
    var m = /\/(dm[0-9]+)(?:\/|$)/i.exec(hit[0]);
    return m ? m[1] : '';
  }

  function parseCategories(html) {
    var out = [];
    if (!html) return out;
    var seen = {};
    P_GENRE_HREF.lastIndex = 0;
    var m;
    while ((m = P_GENRE_HREF.exec(html)) !== null) {
      var href = m[1];
      var id = genreId(href);
      if (!id) continue;
      id = id.toLowerCase();
      var txt = clean(stripTags(decodeEntities(m[2])));
      var count = 0;
      var c = P_GENRE_COUNT.exec(txt);
      if (c) {
        var digits = c[1].replace(/[^0-9]/g, '');
        count = digits ? parseInt(digits, 10) : 0;
        if (isNaN(count)) count = 0;
      }
      var isLabel = count === 0 && txt.length > 0;

      if (seen[id] && !isLabel) {
        // 第二次出现（带条数的那个）：就地更新 count
        for (var k = 0; k < out.length; k++) {
          if (out[k].id === id) {
            if (count > 0) out[k].count = count;
            break;
          }
        }
        continue;
      }
      if (seen[id]) continue;
      seen[id] = 1;
      out.push({ id: id, name: count === 0 ? txt : '', count: count });
    }
    return out;
  }

  // ── 详情页（对齐 MissavParser.parseDetail）────────────────────────

  /**
   * 读某个 og meta 的 content。
   *
   * ★ 用 `String.match` 一次性取全部 meta 标签，不要用一条**模块级**全局正则的
   *   `exec` 循环：那是共享状态，`meta()` 在 parseDetail 里会被叫十几次，
   *   lastIndex 互相打架，一旦某次落进 `continue` 分支就原地打转、整页卡死。
   *   症状是「详情一直转圈、不报错」—— 这里踩过。
   */
  function meta(html, key) {
    // 每次现建正则：这个函数被高频调用，但正则很便宜，正确性比省那点开销重要。
    var tags = String(html).match(
      /<meta[^>]+(?:property|name)\s*=\s*["']og:([a-z0-9_:]+)["'][^>]*>/gi) || [];
    for (var i = 0; i < tags.length; i++) {
      var k = /og:([a-z0-9_:]+)/i.exec(tags[i]);
      if (!k || k[1].toLowerCase() !== key.toLowerCase()) continue;
      var c = /content\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))/i.exec(tags[i]);
      if (c) return clean(attrValue(c));
    }
    return '';
  }

  function parseDetail(html) {
    var o = { id: '', title: '', cover: '', description: '', actors: [],
      releaseDate: '', duration: '', code: '', playUrl: '', qualities: [] };
    if (!html) return o;

    // id：优先 og:url 末段，其次页面里第一个 /cn/<id> 链接。
    var id = '';
    var ogUrl = meta(html, 'url');
    if (ogUrl) id = extractIdFromUrl(ogUrl);
    if (!id) {
      var m = /href\s*=\s*"([^"]*\/cn\/[a-z0-9-]+)"/i.exec(html);
      if (m) id = extractIdFromUrl(m[1]);
    }
    o.id = id;

    var title = clean(stripTags(decodeEntities(meta(html, 'title'))));
    // og:title 常是「FCT-210 标题…」，若有 twitter:image:alt 且更干净就换上去。
    var alt = clean(stripTags(decodeEntities(meta(html, 'image:alt'))));
    if (alt && alt.length > title.length) title = alt;
    o.title = title;

    o.cover = meta(html, 'image');
    o.description = clean(stripTags(decodeEntities(meta(html, 'description'))));

    var actor = meta(html, 'video:actor');
    var actors = [];
    var parts = actor.split(/[,，]/);
    for (var i = 0; i < parts.length; i++) {
      var n = clean(stripTags(decodeEntities(parts[i])));
      if (n) actors.push(n);
    }
    o.actors = actors;

    var release = meta(html, 'video:release_date');
    if (release.length > 10) release = release.substring(0, 10);   // 只要 YYYY-MM-DD
    o.releaseDate = release;

    var duration = meta(html, 'video:duration');
    if (!duration) duration = firstDuration(html);
    o.duration = duration;

    o.code = extractCode(title || id);
    o.playUrl = playUrl(html);
    o.qualities = qualities(html);
    return o;
  }

  // ── packed JS 解包（对齐 MissavParser.unpackScript）────────────────
  //
  // 真实页面上的调用形态（2026-10-18 实测）：
  //
  //   …;while(c--){…}}return p}('f=\'8://7.6/5-4-3-2-1/e.0\';…',
  //     16,16,'m3u8|78531695c531|ab7a|…|source'.split('|'),0,{}))
  //
  // 两个捕获组，**顺序不能反**：① 是代码载荷（一堆数字/字母占位符），② 是词典。
  //
  // ★ 锚点是 `}(`，不是 `})(`（连踩两次）。真实页面里这段是**内联**的 ——
  //   packer 函数体收尾的花括号直接接调用括号 `return p}('…'`。而手工 eval 时会看到
  //   另一种形态 `eval((function(){…})('…',…))`，那个多一层右括号。
  //   两个形态只差一个 `)`，正则用错哪一个都完全不匹配，而且 playUrl 静默变空串、
  //   不报任何错 —— 是靠拿真实页面跑才看出来的。所以写成 `\}\s*\(?` 两个都吃。
  //
  // ★ 不要去匹配 packer 的函数体：里面嵌套三层花括号（while(c--){ if(k[c]){…} }），
  //   正则要写成「一层 + 任意层」的循环形式，又难读又容易漏。只锚定函数体**结束**的
  //   那个 `(`，往后两个参数就是平铺的字符串和数字。
  var P_PACKED = /\}\s*\(?\s*'((?:[^'\\]|\\.)*)'\s*,\s*\d+\s*,\s*\d+\s*,\s*'((?:[^'\\]|\\.)*)'/gi;

  var P_M3U8 = /https?:\/\/[A-Za-z0-9._~:\/?#\[\]@!$&'()*+,;=%-]*?\.m3u8/gi;
  var P_QUALITY = /\/(360p|480p|720p|1080p)\//i;

  /** JS 单引号串里的转义：载荷里全是 `\'f=\'…\';` 这种形态。 */
  function unescapeJs(s) {
    if (s == null) return '';
    return String(s)
      .replace(/\\'/g, "'")
      .replace(/\\"/g, '"')
      .replace(/\\n/g, '\n')
      .replace(/\\r/g, '');
  }

  /** 正则转义，替代 Java 的 Matcher.quoteReplacement（词典里可能有 $ 和 \）。 */
  function quoteReplacement(s) {
    return String(s).replace(/([\\$])/g, '\\$1');
  }

  function unpackScript(html) {
    if (!html) return '';
    var out = [];
    // 一页里可能有多段 packed（实测第一段是播放地址，第二段是别的反爬逻辑），
    // 所以从头往后逐段找，而不是只取第一段。
    //
    // ★ 用「同一条全局正则 + lastIndex 自然推进」遍历，**不要**一边 exec 一边
    //   `rest = rest.substring(...)` 换字符串：正则的 lastIndex 会留在旧串上，
    //   配上一个带 `\s*` 的正则就可能在边界上原地打转（这里踩过，表现为整页卡死）。
    P_PACKED.lastIndex = 0;
    var guard = 0;
    var m;
    while (guard++ < 40 && (m = P_PACKED.exec(html)) !== null) {
      if (m[0].length === 0) { P_PACKED.lastIndex++; continue; }   // 防零长匹配死循环
      var payload = unescapeJs(m[1]);
      var words = m[2].split('|');
      if (words.length === 0) break;

      // 词典第 i 个词回填载荷里的占位符 i —— 两个反直觉点，连踩两次才定位：
      //
      // ① 占位符不是十进制。packer 里是 `e = function(c){ return c.toString(36) }`，
      //    所以索引 10~15 在载荷里长成 a b c d e f。载荷 `f='8://7.6/5-4-3-2-1/e.0'`
      //    里的 `e` 就是索引 14，不是字母 e、也不是数字 14 —— 按十进制替换永远不命中，
      //    结果是播放地址变成 `…/e.m3u8`（少一段，看起来像站点改版）。
      // ② 索引从 0 数，不是从 1。依据：`https` 必须是 `8://` 里的 `8`，
      //    而 `https` 是词典第 9 项（下标 8）。
      //
      // ★ 从大到小替换：\b 是词边界，但 "13" 和 "3" 在纯数字串里会互相干扰 ——
      //    先替 1 会让 "13" 里的 "1" 抢先命中。
      var src = payload;
      for (var i = words.length - 1; i >= 0; i--) {
        var w = words[i].trim();
        if (!w) continue;
        var key = i.toString(36);          // ★ base-36
        src = src.replace(new RegExp('\\b' + key + '\\b', 'g'), quoteReplacement(w));
      }
      out.push(src);
    }
    return out.join('\n');
  }

  function collectM3u8(src, seen, list) {
    if (!src) return;
    P_M3U8.lastIndex = 0;
    var m;
    while ((m = P_M3U8.exec(src)) !== null) {
      var u = m[0];
      if (seen[u]) continue;
      seen[u] = 1;
      list.push(u);
    }
  }

  /**
   * 全部 m3u8（去重、保持出现顺序）。先从解包源码里找，找不到再退回原始 HTML
   * 直接搜（有些页面直接内嵌 <source src=…m3u8>）。
   */
  function allPlayUrls(html) {
    var seen = {};
    var list = [];
    if (!html) return list;
    collectM3u8(unpackScript(html), seen, list);
    if (list.length === 0) collectM3u8(html, seen, list);
    return list;
  }

  function playUrl(html) {
    var urls = allPlayUrls(html);
    return urls.length ? urls[0] : '';
  }

  /** 画质档位，降序（高清在前），让 UI 第一个就是最清晰的。 */
  function qualities(html) {
    var seen = {};
    var list = [];
    var urls = allPlayUrls(html);
    for (var i = 0; i < urls.length; i++) {
      // ★ 用 match 而不是 exec 循环：P_QUALITY 没有 g，无 g 的 exec 永远返回
      //   同一个结果、lastIndex 不推进，循环里就是永久挂死（这里踩过：
      //   整页卡住不报错，`node spider-check.js` 六十秒回不来）。
      //   match 一次拿全部命中，不受 lastIndex 影响。
      var hits = urls[i].match(P_QUALITY) || [];
      for (var k = 0; k < hits.length; k++) {
        var q = hits[k].replace(/\//g, '').toLowerCase();
        if (seen[q]) continue;
        seen[q] = 1;
        list.push(q);
      }
    }
    list.sort(function (a, b) {
      return parseInt(String(b).replace('p', ''), 10) - parseInt(String(a).replace('p', ''), 10);
    });
    return list;
  }

  // ── 地址拼装（对齐 MissavAdapter.categoryUrl）──────────────────────
  //
  // slug 有三种形状，靠首段区分：
  //   genres/dm106/剧情  → /dm106/cn/genres/剧情    类型页（多一层 genres）
  //   dm597/fc2          → /dm597/cn/fc2            普通分类（nav 二级项）
  //   new                → /cn/new                  固定导航
  // 拼错了站点回的是 404 内容页，parseList 一张卡都认不出来，日志里还永远是 ok ——
  // 症状是「已显示 0 部作品」。分类 id 在**路径最前面**是站点的规定。
  function categoryUrl(base, slug, page) {
    var s = String(slug == null ? '' : slug).replace(/^\/+/, '');
    var slash = s.indexOf('/');
    var headPart = slash > 0 ? s.substring(0, slash) : '';
    var path = '';
    if (headPart === 'genres' && slash > 0) {
      var rest = s.substring(slash + 1);
      var sep = rest.indexOf('/');
      var dmId = sep > 0 ? rest.substring(0, sep) : rest;
      var name = sep > 0 ? rest.substring(sep + 1) : '';
      path += '/' + enc(dmId) + '/cn/genres';
      if (name) path += '/' + enc(name);
    } else if (slash > 0 && /^dm\d+$/.test(headPart)) {
      path += '/' + enc(headPart) + '/cn/' + enc(s.substring(slash + 1));
    } else {
      path += '/cn/' + enc(s);
    }
    if (page > 1) path += '?page=' + page;
    return base + path;
  }

  function pageOf(page) {
    var n = parseInt(page, 10);
    return isNaN(n) || n < 1 ? 1 : n;
  }

  /**
   * 「全部类型」页 —— 对齐 MissavAdapter.genres。
   *
   * 站点把分类摊在 42 页里（实测每页 36 个）。全量 42 页不现实：每页一次请求，
   * 串行下来十几秒，加源体验会崩。取前 GENRE_PAGES 页是折中 —— 站点把常用分类
   * 排在前面，三页一百多个覆盖了实际会用到的全部。
   *
   * 输出形状按 spider 模型：items[] 是分类项 {id,name,count,cate}。
   * **spider 模型没有「标签分组」这个结构**，所以不套 Java 那边 adaptTags 要的
   * {code,tags[]} 信封 —— 那是前端旧管线的形状，转换层会处理。
   *
   * 终止条件用「这一页有没有带来新分类」，**不要**用「这页够不够 36 个」：
   * 站点最后一页不满，而且第一页实测只解析出 35 个（36 个里有重复 id）。
   * 按 36 判断的话第一页就 break 了，多页配置形同虚设 —— 2026-10-18 真机日志
   * 「抓了 35 个分类」就是这么来的。
   */
  async function genres(ctx, page) {
    var raw = [];
    var fetched = {};
    for (var i = 0; i < GENRE_PAGES; i++) {
      var url = BASE + '/cn/genres?page=' + (page + i);
      var html;
      try {
        html = await fetchPage(ctx, url);
      } catch (e) {
        // 后面几页拿不到不该让整页报错：第一页有内容就已经有用了。
        if (raw.length === 0) throw e;
        break;
      }
      var part = parseCategories(html);
      if (part.length === 0) break;
      var added = 0;
      for (var k = 0; k < part.length; k++) {
        var one = part[k];
        if (!one.id || fetched[one.id]) continue;
        fetched[one.id] = 1;
        raw.push(one);
        added++;
      }
      if (added === 0) break;
    }

    ctx.call('log', { msg: 'missav 类型：抓了 ' + raw.length + ' 个分类（起始页 ' + page + '）' });

    var items = [];
    for (var j = 0; j < raw.length; j++) {
      var c = raw[j];
      // cate 是「点进去查列表时该传的 slug」，和 MissavAdapter 产出的
      // `genres/{dmId}/{名字}` 同一个形状 —— category() 认这个前缀。
      items.push({
        id: c.id,
        name: c.name,
        count: c.count || 0,
        cate: 'genres/' + c.id + '/' + c.name
      });
    }
    // 类型页本身也有分页（站点 42 页），但这里不报 hasMore ——
    // 前端拿「类型页的分页」去翻「影片列表」是错的。
    return { items: items, page: page, hasMore: false };
  }

  function full(card) {
    // 卡片封面是站点的绝对地址（fourhoi.com 那个 CDN），原样交出去。
    var out = {
      id: card.id,
      title: card.title,
      cover: card.cover,
      duration: card.duration || '',
      tags: []
    };
    if (card.code) out.badge = card.code;
    return out;
  }

  // ── 能力 ──────────────────────────────────────────────────────────

  __spiderSite = {
    id: 'missav',
    name: 'MissAV',
    version: 1,
    requires: '>=1',
    // net 白名单：站点 + 播放 CDN。surrit.com 是 m3u8 的域，它和站点一样挡裸请求，
    // 但解密出来的地址要给播放器用，前端需要能直连 —— 列出来免得播放被白名单绊住。
    hosts: ['missav.live', 'surrit.com'],

    /**
     * 首页 = 最近更新。
     *
     * featured 和 latest 内容相同是**有意的**：站点的「精选」在页脚的 /cn/release
     * （新作上市），那是一个独立列表而不是首页的一部分。宁可两行都显示最近更新，
     * 也不要 featured 那一行空着 —— 空行比重复行更像故障。spider 只给一个平铺
     * items，分区是 UI 的决定。
     */
    async home(ctx) {
      var html = await fetchPage(ctx, BASE + '/dm539/cn/new');
      var cards = parseList(html);
      if (cards.length === 0) {
        throw new Error('missav 首页没解析到卡片（共 ' + html.length + ' 字节，'
          + '含 thumbnail=' + (html.indexOf('thumbnail') >= 0) + '）。'
          + '可能：站点改版换了卡片结构、或拿到的是被盾改写过的页面。');
      }
      // ★ 全量交出去，不要按 FEATURED_LIMIT 截断。
      //   Java 那边 featured 取前 8 条、latest 取全量 12 条，两个字段都填了；
      //   spider 只有一个 items，截成 8 就等于把后 4 部片从首页删掉了
      //   （MissavAdapterTest 钉的正是 12）。要一排「精选」是 UI 的事，
      //   UI 自己 slice(0, 8) 即可，源不该替它丢数据。
      var items = [];
      for (var i = 0; i < cards.length; i++) items.push(full(cards[i]));
      return { items: items, page: 1, hasMore: cards.length >= PAGE_SIZE };
    },

    /**
     * 分类。
     * args.slug → 'new' / 'dm597/fc2' / 'genres/dm106/剧情'
     * args.page → 页码
     */
    async category(ctx, args) {
      args = args || {};
      // ref / slug / cate 三个键都认：spider-check 的 args 用的是 `ref`，
      // 前端传的是 `slug`，手敲调试时习惯写 `cate`。读少一个的表现是
      // 「分类页永远报缺参数」，而错误信息看起来像是站点的问题。
      var slug = String(args.slug || args.cate || args.ref || '');
      if (!slug) throw new Error('missav 分类少了 slug（例如 dm597/fc2、genres/dm96/高清 或 new）');
      var page = pageOf(args.page);

      // 「全部类型」是另一份数据、另一种形状（分类胶囊而不是影片卡片），
      // 所以按 slug 首段分派，不能当普通列表解析。
      //
      // 判据写成「整个是 genres」或「genres/ 开头」两种精确形态，不用裸的
      // indexOf('genres') === 0 —— 那样 `genresfoo` 也会命中，然后被当成
      // 类型页去抓一个不存在的地址。
      if (slug === 'genres' || slug.indexOf('genres/') === 0) return await genres(ctx, page);

      var url = categoryUrl(BASE, slug, page);
      var html = await fetchPage(ctx, url);
      var cards = parseList(html);
      if (cards.length === 0 && page === 1) {
        throw new Error('missav 这个分类 0 张卡：' + url + '（' + html.length + ' 字节）。'
          + '可能：slug 拼错了（分类 id 要在路径最前面）、站点改版、或者盾把这个地址改写成了 404 内容页。');
      }
      var items = [];
      for (var i = 0; i < cards.length; i++) items.push(full(cards[i]));
      // 站点没给总页数，用「这页满不满」推 —— 满页说明后面还有。
      return { items: items, page: page, hasMore: cards.length >= PAGE_SIZE };
    },

    async search(ctx, args) {
      args = args || {};
      var keyword = String(args.q || args.keyword || '');
      var page = pageOf(args.page);
      if (!keyword) return { items: [], page: 1, hasMore: false };
      var url = BASE + '/cn/search?q=' + enc(keyword) + '&page=' + page;
      var html = await fetchPage(ctx, url);
      var cards = parseList(html);
      var items = [];
      for (var i = 0; i < cards.length; i++) items.push(full(cards[i]));
      // 搜不到就是 0 条，不报错：空结果比一个错误提示好用，用户能分清
      // 「没搜到」和「搜不了」（后者会被 fetchPage 直接抛出来）。
      return { items: items, page: page, hasMore: cards.length >= PAGE_SIZE };
    },

    async detail(ctx, args) {
      args = args || {};
      var id = String((args.ref || args.id) || '').trim();
      if (!id) throw new Error('missav 详情缺少 id（就是番号那个末段，例如 fct-210）');
      var html = await fetchPage(ctx, BASE + '/cn/' + enc(id));
      var d = parseDetail(html);
      if (!d.id) {
        throw new Error('missav 详情页解析不出影片（id=' + id + '）。可能站点改版了，'
          + '或者拿到的是 404 内容页（页面 ' + html.length + ' 字节）。');
      }

      // 这个站一部片就是一集，没有「集数列表」。单集站也必须给一个单元素数组 ——
      // 不给的话前端 asArray(...episodes) 拿到空，详情页一集都不渲染，
      // 用户看到的是「无法拉到集数」。把「站点没有集数体系」翻译成「有一集」，
      // 这是适配层的责任。
      var episodes = [{
        no: 1,
        title: d.title || id,
        line: 'default',
        ref: id,
        episodeNumber: 1,
        duration: d.duration || ''
      }];

      var out = {
        id: d.id,
        title: d.title || id,
        cover: d.cover,
        description: d.description,
        duration: d.duration || '',
        category: '',
        tags: d.actors || [],
        episodes: episodes,
        episodeCount: 1,
        lines: [{ line: 'default', name: '默认线路' }],
        related: []
      };
      if (d.code) out.badge = d.code;
      if (d.releaseDate) out.extra = { releaseDate: d.releaseDate };
      // 详情里顺手把播放地址也解出来：播放那次要再拉一遍同一个页面，
      // 每次过盾 1.5~4 秒，能从详情省一次是一次。play 仍然会自己再解析一次
      // （地址零缓存），这里只是为了前端能提前知道有没有。
      if (d.playUrl) out.playUrl = d.playUrl;
      return out;
    },

    /**
     * 播放。地址**每次现取**（拉详情页 → 解 packed JS）。
     *
     * 站点一部片一集，所以 line 只有一个 'default'，alts 就是全部画质档。
     */
    async play(ctx, args) {
      args = args || {};
      var id = String((args.ref || args.id) || '').trim();
      if (!id) throw new Error('missav 播放缺少 id');
      var url = BASE + '/cn/' + enc(id);
      var html = await fetchPage(ctx, url);

      var urls = allPlayUrls(html);
      if (urls.length === 0) {
        throw new Error('这一部没解析出播放地址（id=' + id + '）。'
          + '可能：片源下架、页面里的播放器那段 packed JS 改了（解包锚点不再命中），'
          + '或者这一部本来就没有可播源。');
      }

      var main = urls[0];
      var alts = [];
      for (var i = 0; i < urls.length; i++) {
        var q = '';
        var m = /\/(360p|480p|720p|1080p)\//i.exec(urls[i]);
        if (m) q = m[1].toLowerCase();
        alts.push({
          line: q || ('source' + (i + 1)),
          name: q || ('线路 ' + (i + 1)),
          url: urls[i]
        });
      }

      // 前端 resolveUrl 读的是 source || url，两个都给，别名怎么配都不会漏。
      return {
        url: main,
        source: main,
        alts: alts,
        episodeNumber: 1,
        title: ''
      };
    }
  };
})();
