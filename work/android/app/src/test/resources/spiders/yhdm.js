// 樱花动漫（yhdm.one）—— 阶段 1 的第一个真源。
//
// 语义照抄 YhdmAdapter.java（parseCards / list / search / detailFrom / playFrom / epOf），
// 不重新发明解析：同一个 HTML，这里和 Java 适配器算出来的字段必须一致。
// 但只用字符串 + 正则 —— 这个文件要能在没有 DOM 的沙箱里跑离线回归
// （scripts/spider-check.js 在 vm 里加载它），用了 DOMParser / document /
// window 就等于把这个源从此没有离线回归，只能靠真机点。
//
// 站点形状（2026-10-18 实测，裸请求可拉，无 CF 无 UA 检查）：
//   GET /                          首页，卡片 li 里带 /vod/{id}.html
//   GET /list/?{查询串}&page=N     列表第 N 页（第 1 页可省 &page=1）
//   GET /latest/                   最近更新，一列 128 条，无分页
//   GET /search?q={词}             搜索（page 参数会被忽略，永远只有第一页）
//   GET /vod/{id}.html             详情：h1.names / /cover/{id}.jpg / 全部剧集 ep-panel
//   GET /_get_plays/{id}/{ep}      JSON：video_plays[].play_data 是第三方采集站的 m3u8
//
// 三条从 Java 那边继承下来的规矩：
//   ① 封面懒加载，data-original 优先，src 是占位图。
//   ② 剧集标识用站点自己的 ep 段（ep1 / ep163 / zheng_pian / hd），不是序号。
//   ③ 播放地址每次现取，不缓存 —— 线路会换，采集合天天变。
(function () {
  var BASE = 'https://yhdm.one';

  // ── 模型说明（和 Java 适配器的差异写在这）──────────────────────────
  //
  // detail.episodes[i] 同时给两套键：
  //   no / ref / line / title   —— spider 模型（ref 是站点自己的标识，不是 URL）
  //   episodeNumber / id        —— Java 适配器同名字段，保持逐字段一致
  // detail.lines 只有一条默认线路：站点把「线路」放在每一集的 _get_plays 里
  // （WJ / SN / YH…），详情页本身不列线路。真要换线，看 play 返回的 alts。
  // play 的 ref 支持两种写法：'2026476948'（视频 id，ep 默认 ep1）和
  // '2026476948:ep163'（detail.id + episodes[i].ref 拼出来的精确写法）。
  //
  // ── 这次补的两个能力（stage 2）───────────────────────────────────────
  //
  // category(search) 都返回 {items, page, hasMore}，和 home 同一个「一页」形状。
  // hasMore 的判据是 Java 的 pages 语义：page < pages 才算还有下一页。
  // search 永远 hasMore=false —— 站点忽略 page 参数（Java 那边也是，
  // 注释写着「实测两种写法字节级一致」），不是我们丢数据。
  //
  // ── 播放线路：为什么不做活线探测（有意的取舍，不是没做完）─────────────
  //
  // Java 的 playFrom 是「挑第一条活线路」：顺序探测前 5 条，一路验到**分片**
  // （拿第一个分片的前 2KB，认 200/206），命中就停，还会把分片域名登记进
  // DirectHosts。spider 这边做不到同样的事，三个原因按重要性排：
  //
  // ★ ① **判据在这个 ABI 上不成立。** Java 的分片判据是「拿到 ≥1KB 字节」，
  //      因为 AiiHttp.getRaw 返回 byte[]，2KB 是它自己截的。而 net 的契约是
  //      `body` 是**解码后的字符串**（SpiderRuntime 里 `new String(raw, charset)`）。
  //      一个 1.3MB 的二进制 ts 走 UTF-8 解码，非法字节全变 U+FFFD ——
  //      JS 拿到的是一串替换符，**没有可信的字节数**可以拿来判活。
  //      这不是"能不能读"的问题，是"读回来的东西还能不能当证据"的问题。
  //      分片挂掉和分片正常，在 net 里都表现为"一段乱码"。
  //
  // ★ ② **代价没法用 "3 条线 × 2 次请求" 估算。** net 的 GET 在失败时**重试
  //      一次**（SpiderRuntime 里那个 `for (attempt < 2)`），单次上限 15s。
  //      所以一次探测调用的最坏成本是 30s，不是几百毫秒：
  //
  //        3 条线 × 2 次（主列表+子列表） = 6 次调用 → 最坏 6×30 = 180s
  //        3 条线 × 3 次（验到分片）      = 9 次调用 → 最坏 9×30 = 270s
  //
  //      而 app.js 给 spider 的 timeoutMs 是 **45s**（spiderArgsFor 那条
  //      bridgeCall）。最坏情况远超预算，而且撞线的是**每一条死线**——
  //      越是需要探测的时候（线路全在抖），越会 45s 超时把整个 play 杀掉。
  //      现在的实现反而不受影响：1 次请求拿到全部线路，秒回。
  //
  // ★ ③ **只验到子列表这一层，恰好解决不了最要紧的那个案例。** Java 注释里
  //      那张 2026-10-18 的真机表，12 条线路按"挂在哪一层"归类：
  //
  //        jszy/lzzy/ffzy/ukzy/wjzy2/snzy   主列表就挂      ← 1 次请求能筛掉
  //        hnzy/jyzy/xlzy                   子列表就挂      ← 2 次请求能筛掉
  //        ikzy                             前三层都通，**分片 TLS 被掐**
  //        mdzy                             全链 206，能播
  //
  //      也就是说：除 ikzy 外，其余 11 条验到子列表都够。而 ikzy 正是当年
  //      「点了播不了、转两秒报解不了」的那个案例 —— 它排第一，只验到子列表
  //      会把它判成活，等于什么都没做。要筛掉它必须验到分片，回到 ① 的死路。
  //
  // ── 所以这里给的方案 ─────────────────────────────────────────────────
  //
  // 按**原顺序**取第一条当 url，**全部线路原样进 alts**（顺序不动，让用户在
  // 播放器里能换）。探测交给播放器：它拿到就是一条 m3u8，起播失败有自己的
  // 报错，而且失败后线路列表就在界面上。
  //
  // 这和 Java 的差别是"默认选中哪条"，不是"能不能播"：
  //   Java   挑第一条活线 → 大概率一次就中
  //   spider 给第一条      → 第一条是死线时要用户手动切一下
  //
  // **我知道第一条可能是死线，这是有意的取舍。** 要补回探测必须动 ABI ——
  // 让 net 能给「原始字节 / 只取前 N 字节」（Range 或 keepBinary），
  // 那时候 ① 的判据才成立，② 的成本才有意义（只取 2KB 的话单次几十毫秒）。

  // ── 文本工具（对齐 HtmlKit）────────────────────────────────────────

  function unescape(s) {
    return String(s == null ? '' : s)
      .replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'")
      .replace(/&#039;/g, "'")
      .replace(/&apos;/g, "'")
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, '&')
      .replace(/\\u0026/g, '&')
      .replace(/\\\//g, '/');
  }

  function text(s) {
    return unescape(String(s == null ? '' : s).replace(/<[^>]*>/g, ' '))
      .replace(/\s+/g, ' ').trim();
  }

  function abs(base, src) {
    if (!src) return '';
    if (/^https?:\/\//i.test(src)) return src;
    if (src.indexOf('//') === 0) return 'https:' + src;
    if (src.charAt(0) === '/') return base + src;
    return base + '/' + src;
  }

  // HtmlKit.normalizeUrl 的等价实现：`https://a.com//x/y.jpg` → `https://a.com/x/y.jpg`。
  // 只归一化 host 之后那一段的首个斜杠，后面的连续斜杠不动（路径里的 // 是语义）。
  function normalizeUrl(url) {
    var s = String(url == null ? '' : url).trim();
    var scheme = s.indexOf('://');
    if (scheme < 0) return s;
    var i = scheme + 3;
    while (i < s.length && s.charAt(i) !== '/') i++;
    if (i >= s.length) return s;
    var j = i;
    while (j < s.length && s.charAt(j) === '/') j++;
    return s.substring(0, i) + '/' + s.substring(j);
  }

  // HtmlKit.enc 的等价实现：RFC 3986 unreserved 原样保留，其余按 UTF-8 转 %XX。
  // **不用 encodeURIComponent** 的三个理由（抄 Java 注释）：
  //   ① 它把 `!*'()` 也编掉，而那四个是保留字符，编了有的站 404；
  //   ② `~` 在 RFC 3986 里是 unreserved，本该原样保留 —— 多数服务器容忍，但
  //      按原样比对签名的站会 403；
  //   ③ 它的空格是 `%20`（这点其实对），但它不区分上面的字符集差异。
  // 中文必须编成 %XX，否则标签和搜索词全是乱码 —— 这条 Mrds66 踩过。
  function enc(s) {
    var raw = String(s == null ? '' : s);
    var out = '';
    for (var i = 0; i < raw.length; i++) {
      var c = raw.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
          || c === '-' || c === '_' || c === '.' || c === '~') {
        out += c;
        continue;
      }
      var code = raw.charCodeAt(i);
      // BMP 之外的字符（emoji 等）在 JS 里是两个 UTF-16 码元，要按码点转才能
      // 拿到 4 字节的 UTF-8。改用 codePointAt + surrogate 判定，避免把代理对
      // 拆成两个 3 字节序列（Java 那边 char 也是这么错的，但站点不接受代理对，
      // 这里按码点实现，宁可多对一次也不发出非法 UTF-8）。
      if (code >= 0xd800 && code <= 0xdbff && i + 1 < raw.length) {
        var next = raw.charCodeAt(i + 1);
        if (next >= 0xdc00 && next <= 0xdfff) {
          var cp = 0x10000 + ((code - 0xd800) << 10) + (next - 0xdc00);
          out += '%' + hex2(0xf0 | (cp >> 18))
              + '%' + hex2(0x80 | ((cp >> 12) & 0x3f))
              + '%' + hex2(0x80 | ((cp >> 6) & 0x3f))
              + '%' + hex2(0x80 | (cp & 0x3f));
          i++;
          continue;
        }
      }
      if (code < 0x80) {
        out += '%' + hex2(code);
      } else if (code < 0x800) {
        out += '%' + hex2(0xc0 | (code >> 6)) + '%' + hex2(0x80 | (code & 0x3f));
      } else {
        out += '%' + hex2(0xe0 | (code >> 12))
            + '%' + hex2(0x80 | ((code >> 6) & 0x3f))
            + '%' + hex2(0x80 | (code & 0x3f));
      }
    }
    return out;
  }

  function hex2(n) {
    var s = (n & 0xff).toString(16).toUpperCase();
    return s.length < 2 ? '0' + s : s;
  }

  // ── 取页面 ────────────────────────────────────────────────────────

  // net 失败时 body 是空的、ok=false：这里必须抛可读错误，不能返回空列表 ——
  // 空列表会让用户看到「这个站没有内容」，和「这个站今天挂了」是两回事。
  async function fetchText(ctx, url) {
    var res;
    try {
      res = await ctx.call('net', { url: url });
    } catch (e) {
      throw new Error('yhdm 请求失败 ' + url + '：' + ((e && e.message) || e));
    }
    if (!res || res.ok !== true) {
      throw new Error('yhdm 请求失败 ' + url + '：' + ((res && res.error) || '没有响应'));
    }
    return String(res.body == null ? '' : res.body);
  }

  // ── 卡片解析（对齐 YhdmAdapter.parseCards）────────────────────────

  var LI_RE = /<li\b[^>]*>[\s\S]*?<\/li>/gi;
  var VOD_ID_RE = /\/vod\/(\d+)\.html/;
  var TITLE_ATTR_RE = /title=["']([^"']+)["'][^>]*href=["']\/vod\/\d+\.html/;
  var H_TITLE_RE = /<h[56][^>]*>([\s\S]*?)<\/h[56]>/i;
  var ALT_RE = /alt=["']([^"']{2,})["']/;
  var DATA_ORIGINAL_RE = /data-original=["']([^"']+)["']/i;
  var IMG_SRC_RE = /<img[^>]*src=["']([^"']*\.(?:jpg|jpeg|png|webp))["']/i;
  var EP_TIP_RE = /<span>(更至[^<]*)<\/span>/;
  var CATE_LINE_RE = /<div class="small">\s*([^<]*?)\s*<\/div>/;

  function cardTitle(chunk) {
    var m = TITLE_ATTR_RE.exec(chunk);
    if (m) {
      var s = unescape(m[1]).replace(/\s+/g, ' ').trim();
      if (s) return s;
    }
    m = H_TITLE_RE.exec(chunk);
    if (m) {
      s = text(m[1]);
      if (s) return s;
    }
    m = ALT_RE.exec(chunk);
    if (m) {
      s = unescape(m[1]).replace(/\s+/g, ' ').trim();
      if (s) return s;
    }
    return '';
  }

  function cardCover(chunk, base) {
    var m = DATA_ORIGINAL_RE.exec(chunk);
    if (!m) m = IMG_SRC_RE.exec(chunk);
    if (!m) return '';
    return abs(base, normalizeUrl(String(m[1] || '').trim()));
  }

  function parseCards(html, base) {
    var out = [];
    var seen = {};
    LI_RE.lastIndex = 0;
    var m;
    while ((m = LI_RE.exec(html)) !== null) {
      var chunk = m[0];
      var idm = VOD_ID_RE.exec(chunk);
      if (!idm) continue;
      var id = idm[1];
      if (seen[id]) continue;
      seen[id] = 1;                     // Java 也是先占位再判标题，别调换顺序
      var title = cardTitle(chunk);
      if (!title) continue;
      var card = { id: id, title: title, cover: cardCover(chunk, base) };
      var ep = EP_TIP_RE.exec(chunk);
      if (ep) {
        var badge = text(ep[1]);
        if (badge) card.badge = badge;
      }
      var cm = CATE_LINE_RE.exec(chunk);
      if (cm) {
        var cate = text(cm[1]);
        if (cate) card.category = cate;
      }
      out.push(card);
    }
    return out;
  }

  // ── 详情解析（对齐 YhdmAdapter.detailFrom）────────────────────────

  var DETAIL_COVER_RE = /<img[^>]*src=["'](\/cover\/[^"']+)["']/i;
  var H1_NAMES_RE = /<h1[^>]*class=["'][^"']*\bnames\b[^"']*["'][^>]*>([\s\S]*?)<\/h1>/i;
  var INTRO_DIV_RE = /<div class="small"[^>]*>([\s\S]*?)<\/div>/i;
  var META_ROW_RE = /<(?:div|span)[^>]*>\s*([^<>]*(?:原名|别名|地区|年代|首播)：[^<>]*)</g;
  var UPDATE_BADGE_RE = /color:\s*red;?">([^<]*)</;
  var GENRE_LINK_RE = /<a[^>]*href=["']\/list\?genre=[^"']*["'][^>]*>([\s\S]*?)<\/a>/gi;
  var BREADCRUMB_RE = /<a[^>]*href=["']\/list\/\?[^"']*["'][^>]*>([\s\S]*?)<\/a>/i;
  var EP_ANCHOR_RE = /title=["']([^"']*)["'][^>]*href=["']\/vod-play\/\d+\/([^"']+?)\.html["']/g;

  function h1Title(html) {
    var m = H1_NAMES_RE.exec(html);
    return m ? text(m[1]) : '';
  }

  function intro(html) {
    var head = html.indexOf('动漫介绍');
    if (head < 0) return '';
    var tail = html.indexOf('推荐动漫', head);
    if (tail < 0) tail = Math.min(html.length, head + 8000);
    var block = html.substring(head, tail);
    var m = INTRO_DIV_RE.exec(block);
    if (!m) return '';
    var s = text(m[1]);
    if (s.indexOf('简介：') === 0) s = s.substring(3).trim();
    return s;
  }

  function metaLines(html) {
    var start = html.indexOf('原名：');
    if (start < 0) return '';
    var end = html.indexOf('更新至', start);
    if (end < 0 || end - start > 4000) end = Math.min(html.length, start + 2000);
    var block = html.substring(start, end);
    var out = [];
    var m;
    META_ROW_RE.lastIndex = 0;
    while ((m = META_ROW_RE.exec(block)) !== null) {
      var s = text(m[1]);
      if (s) out.push(s);
    }
    return out.join('\n');
  }

  function updateBadge(html) {
    var m = UPDATE_BADGE_RE.exec(html);
    return m ? text(m[1]) : '';
  }

  function detailTags(html) {
    var start = html.indexOf('原名：');
    var end = start < 0 ? 0 : html.indexOf('更新至', start);
    if (end < 0) end = Math.min(html.length, start + 4000);
    var block = start < 0 ? '' : html.substring(start, end);
    var out = [];
    var seen = {};
    var m;
    GENRE_LINK_RE.lastIndex = 0;
    while ((m = GENRE_LINK_RE.exec(block)) !== null) {
      var s = text(m[1]);
      if (!s || seen[s]) continue;
      seen[s] = 1;
      out.push(s);
    }
    return out;
  }

  function detailCategory(html) {
    var m = BREADCRUMB_RE.exec(html);
    return m ? text(m[1]) : '';
  }

  // 剧集锚点只从「全部剧集」那块切：页面下方「其他播放源」的 <a class="swa">
  // 是线路不是剧集，混进来会让选集格子多出一堆重复项。
  function episodeAnchors(html) {
    var out = [];
    var start = html.indexOf('全部剧集');
    if (start < 0) start = html.indexOf('ep-panel');
    if (start < 0) return out;
    var end = html.indexOf('</section>', start);
    if (end < 0) end = Math.min(html.length, start + 20000);
    var block = html.substring(start, end);
    var seen = {};
    var m;
    EP_ANCHOR_RE.lastIndex = 0;
    while ((m = EP_ANCHOR_RE.exec(block)) !== null) {
      var ep = m[2];
      if (!ep || seen[ep]) continue;
      seen[ep] = 1;
      var title = unescape(m[1]).trim();
      out.push({ ep: ep, title: title || ep });
    }
    return out;
  }

  function epIndex(ep) {
    var m = /ep(\d+)/.exec(String(ep || ''));
    return m ? parseInt(m[1], 10) : 1;
  }

  // ── 分页（对齐 YhdmAdapter.totalPages）──────────────────────────────
  //
  // 「页次：1/215页」→ 215。读不到返回 0，由 pageHasMore 兜底。
  var PAGE_TOTAL_RE = /页次：\s*\d+\/\s*(\d+)\s*页/;

  function totalPages(html) {
    var m = PAGE_TOTAL_RE.exec(html);
    if (!m) return 0;
    var n = parseInt(m[1], 10);
    return isFinite(n) ? n : 0;
  }

  // 和 AdapterShape.pagination 同一条规矩：拿不到总数时，「这一页有内容」
  // 就当还有下一页（猜「有」最多多请求一次，猜「没有」会让后面几十页
  // 永远翻不到）。一旦有总数，就严格按 page < pages。
  function pageHasMore(page, got, pages) {
    if (got <= 0) return false;
    if (pages > 0) return page < pages;
    return true;
  }

  // ── 分类表（照抄 YhdmAdapter.CATEGORIES_TABLE）────────────────────

  var CATEGORIES_TABLE = [
    ['jp', 'country=jp'],
    ['cn', 'country=cn'],
    ['us', 'country=us'],
    ['movie', 'type=movie'],
    ['剧场版', 'type=剧场版'],
    ['OVA', 'type=OVA'],
    ['TV', 'type=TV'],
    ['latest', '/latest/']
  ];

  function categoryQuery(slug) {
    for (var i = 0; i < CATEGORIES_TABLE.length; i++) {
      if (CATEGORIES_TABLE[i][0] === slug) return CATEGORIES_TABLE[i][1];
    }
    return '';
  }

  // 对齐 YhdmAdapter.list()：latest 走 /latest/（无分页，一列 128 条）；
  // 其余拼 /list/?{查询串}，page > 1 才追加 &page=N。
  function listUrl(slug, page) {
    var q = categoryQuery(slug);
    if (slug === 'latest' || q === '/latest/') return BASE + '/latest/';
    var url = BASE + '/list';
    if (q.indexOf('/list') === 0) {
      // 完整路径形式（more 组用的）。带上分页，语义和 Java 一致。
      url = BASE + q;
      if (page > 1) url += (url.indexOf('?') >= 0 ? '&' : '?') + 'page=' + page;
      return url;
    }
    url += (q.indexOf('?') === 0 ? '' : '/?') + (q || 'country=jp');
    if (page > 1) url += '&page=' + page;
    return url;
  }

  // ── 能力 ──────────────────────────────────────────────────────────

  function splitRef(ref) {
    var raw = String(ref == null ? '' : ref).trim();
    var i = raw.indexOf(':');
    if (i < 0) return { id: raw, ep: '' };
    return { id: raw.substring(0, i), ep: raw.substring(i + 1) };
  }

  function pageOf(args) {
    var n = parseInt((args && args.page) || 1, 10);
    return isFinite(n) && n > 0 ? n : 1;
  }

  __spiderSite = {
    id: 'yhdm',
    name: '樱花动漫',
    version: 1,
    requires: '>=1',
    // net 白名单：播放页、_get_plays、封面全在这个域。
    // 播放地址本身挂在第三方采集站（ppqrrs.com / wdstbn.com …），但 spider
    // 不请求它们 —— 地址由前端交给播放器，所以白名单只写站点域，和 Java 那边
    // 「只有 yhdm.one 走代理」的判断一致。
    hosts: ['yhdm.one'],

    // 首页一次 /：卡片全在 <li> 里。Java 那边还按前 8 条切 featured，
    // 这里只给一个平铺的 items —— 分区是 UI 的决定，不是源的决定。
    // （model.js 的 home() 拿 items 自己切 featured。）
    async home(ctx) {
      var html = await fetchText(ctx, BASE + '/');
      var items = parseCards(html, BASE);
      if (items.length === 0) {
        throw new Error('yhdm 首页没解析到卡片（可能：站点改版、被墙、或拿到的是挑战页）');
      }
      return { items: items, page: 1, hasMore: false };
    },

    // 分类列表。slug 是站点 /list 的查询串别名（jp / cn / 剧场版 / latest…），
    // 不认识的 slug 退回 country=jp（和 Java 一样，别抛：前端分类表可能比
    // 这里新，抛了等于整页白屏）。
    async category(ctx, args) {
      var slug = String((args && (args.slug || args.cateId)) || '').trim();
      var page = pageOf(args);
      var url = listUrl(slug, page);
      var html = await fetchText(ctx, url);
      var items = parseCards(html, BASE);
      if (items.length === 0 && page === 1) {
        throw new Error('yhdm 分类「' + (slug || '默认') + '」一页都没解析到卡片'
          + '（可能：站点改版、或这个分类已经空了）');
      }
      // latest 没有分页（实测 ?page=2 与第一页字节一致），按单页返回。
      var pages = slug === 'latest' ? 1 : totalPages(html);
      return { items: items, page: page, hasMore: pageHasMore(page, items.length, pages) };
    },

    // 搜索。站点忽略 page 参数，永远只有第一页 —— hasMore 恒 false。
    // 空词不发请求（站点对空 q 会回首页那份列表，看起来像「搜到了」）。
    async search(ctx, args) {
      var keyword = String((args && args.keyword) || '').trim();
      if (!keyword) throw new Error('yhdm 搜索词为空，没有发请求（检查一下输入框是不是空的）');
      var html = await fetchText(ctx, BASE + '/search?q=' + enc(keyword));
      var items = parseCards(html, BASE);
      return { items: items, page: 1, hasMore: false };
    },

    async detail(ctx, args) {
      var ref = splitRef(args && args.ref);
      var id = ref.id;
      if (!id) throw new Error('yhdm 详情缺少 ref（视频 id）');
      if (!/^\d+$/.test(id)) throw new Error('yhdm 详情 ref 不是视频 id：' + id);

      var html = await fetchText(ctx, BASE + '/vod/' + id + '.html');
      var title = h1Title(html);
      if (!title) {
        throw new Error('详情页里没找到标题（id=' + id + '）。可能：影片已删除、或站点改版。');
      }
      var anchors = episodeAnchors(html);
      if (anchors.length === 0) {
        throw new Error('详情页里没有剧集（id=' + id + '）。可能：影片下架。');
      }

      var badge = updateBadge(html);
      var meta = metaLines(html);
      var episodes = [];
      var episodeIds = [];
      for (var i = 0; i < anchors.length; i++) {
        var a = anchors[i];
        var no = i + 1;
        episodes.push({
          no: no,                 // spider 模型：序号，前端选集格子用
          title: a.title,
          line: 'default',        // 详情页只有一套剧集列表，线路在 _get_plays 里
          ref: a.ep,              // spider 模型：站点自己的标识（ep1 / 正片 / hd）
          episodeNumber: no,      // Java 适配器同名字段
          id: a.ep,
          duration: ''
        });
        episodeIds.push(a.ep);
      }

      var out = {
        id: id,
        title: title,
        cover: (function () {
          var m = DETAIL_COVER_RE.exec(html);
          return m ? BASE + m[1] : '';
        })(),
        description: intro(html),
        duration: '',
        score: '',
        category: detailCategory(html),
        tags: detailTags(html),
        extra: meta ? { meta: meta } : {},
        episodes: episodes,
        episodeCount: episodes.length,
        episodeIds: episodeIds,
        lines: [{ line: 'default', name: '默认线路' }],
        related: []
      };
      if (badge) out.badge = badge;
      return out;
    },

    // 播放地址每次现取（Java 那边也一样：线路会换、采集站天天变）。
    // ref 支持 'id' 或 'id:ep'；line 传 alts 里的某条线路标签就选它，否则取第一条。
    //
    // ⚠ 这里**不挑活线**，直接给原顺序第一条。为什么不做探测（三条硬理由 +
    //   那条真机表的逐层归类）写在文件头「播放线路：为什么不做活线探测」那段。
    //   一句话：net 返回的是解码后的字符串、GET 失败会重试导致单次最坏 30s、
    //   而 app.js 只给 45s 预算；且只验到子列表恰好漏掉 ikzy 那个案例。
    async play(ctx, args) {
      var ref = splitRef(args && args.ref);
      var id = ref.id;
      var ep = ref.ep || String((args && args.ep) || '') || 'ep1';
      if (!id) throw new Error('yhdm 播放缺少 ref');
      if (!/^\d+$/.test(id)) throw new Error('yhdm 播放 ref 不是视频 id：' + id);

      var body = await fetchText(ctx, BASE + '/_get_plays/' + id + '/' + enc(ep));
      var json;
      try {
        json = JSON.parse(body);
      } catch (e) {
        throw new Error('yhdm _get_plays 返回的不是 JSON（id=' + id + ' ep=' + ep + '）。'
          + '可能：站点改了接口，或者被劫持成了错误页。');
      }

      var plays = json && json.video_plays;
      if (!plays || typeof plays.length !== 'number') plays = [];
      var alts = [];
      for (var i = 0; i < plays.length; i++) {
        var one = plays[i] || {};
        var url = String(one.play_data == null ? '' : one.play_data).trim();
        if (!url) continue;
        var label = String(one.src_site == null ? '' : one.src_site).toUpperCase();
        alts.push({ line: label || ('LINE' + (i + 1)), url: normalizeUrl(url) });
      }
      if (alts.length === 0) {
        throw new Error('这一集没有可用线路（id=' + id + ' ep=' + ep + '）。'
          + '可能：线路全挂了、或站点改了 _get_plays 的返回。');
      }

      var want = String((args && args.line) || '').trim().toLowerCase();
      var picked = alts[0].url;
      if (want) {
        for (var k = 0; k < alts.length; k++) {
          if (alts[k].line.toLowerCase() === want) {
            picked = alts[k].url;
            break;
          }
        }
      }

      return {
        url: picked,
        source: picked,        // 前端 resolveUrl 读的是 source || url，两个都给
        alts: alts,
        episodeNumber: epIndex(ep)
      };
    }
  };
})();
