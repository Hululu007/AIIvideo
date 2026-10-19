// 91porn（KVS 模板）—— 单站 HTML 型 spider 源。
//
// 语义照抄 Porn91Adapter.java（parseCards / playSource / strencode / detail /
// pagination / totalCount），不重新发明解析。只用字符串 + 正则，**不许用 DOM**。
//
// ── 接口形状（2026-10-18 实测，全部裸请求可拉，无鉴权）──────────────
//   GET /v.php                                   最新 24 条
//   GET /v.php?category=<code>&page=N            分类列表，每页 24
//   GET /view_video.php?viewkey=<vk>             详情 + 播放地址
//   GET /search_result.php?search_id=<q>&search_type=search_videos&page=N
//
// ── 六个必须踩过去的坑（每一条都对应一个实测事故）────────────────────
//
// ① **首页有年龄门，`/` 不是内容页。** GET / 返回 1616 字节的确认页
//    （"我已年满18岁…"），零张卡。内容入口是 /v.php。所以 home() 打 /v.php。
//    拿 / 当入口的症状是「首页一片空白但分类页正常」，最难查。
//
// ② **卡片会重复，重复那份带不同的 c 参数。** 一页 24 条卡，viewkey= 出现
//    38 次 —— 站点把同一条视频在 col-lg-3 和 col-lg-8 两种栅格里各画一遍。
//    去重键用 viewkey，**不是**封面 URL（实测同一张图会挂在不同视频上）。
//
// ③ **viewkey 有两种形态。** 1555330457（纯数字 10 位）和 51dc03e8e27a694f2fc7
//    （20 位小写十六进制）。写成 \d+ 会漏一半，而且漏得没声音。
//
// ④ **播放地址在 document.write(strencode2("…")) 里，页面里还有一个诱饵。**
//    <video> 里躺着一条被注释掉的 <source src="https://ccm.91p52.com/358999.mp4…">，
//    它**全站固定** —— 按 <source 抠就会把所有片子播成同一部。所以顺序必须是：
//    先解 strencode2，解不出来再退回裸 <source>，且退回前先剥注释。
//
// ⑤ **签名 TTL 约 22 分钟。** URL 里的 `e=` 是过期时间戳（签发到过期 1343 秒）。
//    所以 play 每次实时抓详情页，**零缓存**；也正因如此它不读 detail 的缓存。
//
// ⑥ **游客标记 ga 没有的话，详情页每次换一部片。** 同一个 viewkey 连请求五次，
//    五次五个不同的 mp4（标题不变）—— 站点给无标记访客走「随便给一部」的
//    渲染路径。带上 `Cookie: ga=<任意值>` 之后 poster/mp4 才和列表卡片对上。
//    所以第一次请求就要 keep:['ga'] 把站点的标记捡回来，之后每次带上。
//
// ⑦ **「这个站没有标签体系」是旧结论，错的。** 它没有话题标签，但顶部那排
//    导航链接（`/v.php?category=ori|hot|top|hd|rf|tf|long|longer|md|mf`）就是
//    它的标签栏 —— 一个分类就是一个标签。于是 tags() 从导航里抠，
//    tag-list 复用 category（slug 就是 category code），两件事都不需要新的
//    解析逻辑；导航抓不到时退回 CATEGORIES 那十个。
//
//    这条在 Java 时代修过一次（1.0.30「全部类型 0 个标签」），迁成 spider 时
//    丢了：详情里 `tags: []` 是站点的形状没错，但「全部类型」页问的是整站的
//    分类清单，那是有的。症状是点标签只看到一句「这个 spider 源暂不支持
//    tag-list」—— 看起来像能力缺失，其实是这里少了一个函数。
//
// ── 白名单 ───────────────────────────────────────────────────────────
// 站点域是镜像轮换的（DEFAULT_HOST 只是当前这一个），所以白名单同时写
// 当前域和已知的老域；镜像换了要改这里 —— 这是 Java 侧 origin 由配置给的
// 那套做法的静态替代。封面/媒体在 CDN 上，但 spider 不请求它们，不写。
(function () {
  // ── 站点根（镜像地址，写死是有意的，但要知道改哪）──────────────────────
  //
  // 这个站的域名换得比版本还快（901.26702.app 只是当前这一个，发布页上常年
  // 挂着备用域）。Java 那边 origin 由用户在装源时填的地址决定，适配器只往上
  // 拼路径 —— spider 没有等价的配置入口，所以这里只能是常量。
  //
  // ★ 站点换域时改**两处**，都在本文件里，改完不用动别的：
  //     ① 这一行的 BASE
  //     ② `__spiderSite.hosts` 那个数组（net 的白名单只认它，漏了的表现是
  //        每个请求都报「net 拒绝：主机不在白名单」，而不是解析出错）
  //   两处必须一致：BASE 里的 host 没进 hosts，第一请求就被拦。
  //
  // ★ 将来 spider 有了「读源配置」的入口（阶段 3 讨论中的 web.spider+origin），
  //   这里就是接入点：把 BASE 换成从配置里读的 origin，hosts 跟着从同一个值
  //   推导出来，那份写死的列表就可以删了。
  var BASE = 'https://901.26702.app';


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

  function stripHtml(s) {
    return String(s == null ? '' : s).replace(/<[^>]*>/g, ' ');
  }

  function stripTags(s) {
    return String(s == null ? '' : s).replace(/<[^>]+>/g, '').trim();
  }

  function text(s) {
    return unescape(stripHtml(s)).replace(/\s+/g, ' ').trim();
  }

  // HtmlKit.normalizeUrl：`https://a.com//x/y.mp4` → `https://a.com/x/y.mp4`。
  // ★ 这个站在**媒体地址**上真有双斜杠（实测 strencode2 解出来是
  //   `https://la.btc620.com//mp43/1252596.mp4`），所以这条不是可选的。
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

  function hex2(n) {
    var s = (n & 0xff).toString(16).toUpperCase();
    return s.length < 2 ? '0' + s : s;
  }

  // HtmlKit.enc。
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

  // HtmlKit.percentDecode：**只解 %XX，不动 `+`**。
  //
  // ★ 这是本文件最容易写错的一处。不用 decodeURIComponent 有两个理由：
  //   ① 它会把 `+` 解成空格，而签名段 `f=` 是 base64 风格（实测带 + 和 /），
  //      解错了站点回 403，报错看着像「没权限」—— 其实是这里；
  //   ② 它对非法转义（`100%zz`）抛异常，而站点偶尔吐出这种残片，
  //      一抛整个播放就没了。这里按字节攒，非法转义原样保留。
  //
  // 中文必须按**字节**攒再一次性 UTF-8 解：`%E5%90%83` 是三个字节，
  // 逐个当 char 追加会解出「å]ç」（Mrds66 那边修过一次）。
  function percentDecode(raw) {
    var s = String(raw == null ? '' : raw);
    if (!s) return '';
    var out = '';
    var bytes = [];
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      if (c === '%' && i + 2 < s.length) {
        var hi = hexVal(s.charAt(i + 1));
        var lo = hexVal(s.charAt(i + 2));
        if (hi >= 0 && lo >= 0) {
          bytes.push((hi << 4) | lo);
          i += 2;
          continue;
        }
      }
      // 遇到非转义字符：先把攒着的字节吐出去
      if (bytes.length > 0) {
        out += bytesToString(bytes);
        bytes = [];
      }
      out += c;
    }
    if (bytes.length > 0) out += bytesToString(bytes);
    return out;
  }

  function hexVal(ch) {
    if (ch >= '0' && ch <= '9') return ch.charCodeAt(0) - 48;
    if (ch >= 'a' && ch <= 'f') return ch.charCodeAt(0) - 87;
    if (ch >= 'A' && ch <= 'F') return ch.charCodeAt(0) - 55;
    return -1;
  }

  // UTF-8 字节数组 → 字符串。手写解码，因为 TextDecoder 在离线沙箱里没有。
  // 非法的续字节按 U+FFFD 处理（不抛，站点偶尔吐残片）。
  function bytesToString(bytes) {
    var out = '';
    var i = 0;
    while (i < bytes.length) {
      var b = bytes[i];
      if (b < 0x80) {
        out += String.fromCharCode(b);
        i++;
      } else if ((b & 0xe0) === 0xc0) {
        if (i + 1 < bytes.length && (bytes[i + 1] & 0xc0) === 0x80) {
          out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f));
          i += 2;
        } else { out += '\ufffd'; i++; }
      } else if ((b & 0xf0) === 0xe0) {
        if (i + 2 < bytes.length && (bytes[i + 1] & 0xc0) === 0x80
            && (bytes[i + 2] & 0xc0) === 0x80) {
          out += String.fromCharCode(((b & 0x0f) << 12)
            | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f));
          i += 3;
        } else { out += '\ufffd'; i++; }
      } else if ((b & 0xf8) === 0xf0) {
        if (i + 3 < bytes.length && (bytes[i + 1] & 0xc0) === 0x80
            && (bytes[i + 2] & 0xc0) === 0x80 && (bytes[i + 3] & 0xc0) === 0x80) {
          var cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12)
            | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f);
          cp -= 0x10000;
          out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
          i += 4;
        } else { out += '\ufffd'; i++; }
      } else {
        out += '\ufffd';
        i++;
      }
    }
    return out;
  }

  // ── 请求 + 游客标记 ────────────────────────────────────────────────
  //
  // HEADERS 里 **User-Agent 是必须的，不是装饰**。2026-10-18 实测：
  //
  //   UA = okhttp/4.10.0  → HTTP 200, 0 字节（v.php 和 view_video.php 都是）
  //   UA = 不带           → 200, 72KB，内容正常
  //   UA = 安卓 Chrome    → 200, 72KB，内容正常
  //
  // 也就是说 OkHttp 默认 UA 被挡了，而且挡法是「静默返回空」而不是 403 ——
  // 这个坑最难查，因为所有状态码都是 200。所以空响应必须显式抛。
  var HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36'
      + ' (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8'
  };

  // 站点没下发标记时的兜底值。实测 `ga=1` 一样管用 —— 站点只判断
  // 「这个访客带没带标记」，不校验内容。留着是防「Set-Cookie 被中间设备剥掉」。
  var FALLBACK_GUEST = '1';

  // 进程内的游客标记。**一次抓取生命周期内有效**：每次 cap 调用是独立的
  // WebView 页面，所以这个变量在每个 cap 里都要重新捡一次 —— 这是设计上
  // 的代价（多一次首页请求），换来的是不依赖跨调用的隐藏状态。
  // 用 undefined 表示「还没试过」，'' 表示「试过了没拿到」。
  var guest = null;
  // ensureGuest 那一次 /v.php 的正文（见 ensureGuest 里的说明）。
  var guestPage = '';

  function headersWith(token) {
    var h = {};
    for (var k in HEADERS) {
      if (Object.prototype.hasOwnProperty.call(HEADERS, k)) h[k] = HEADERS[k];
    }
    // 没有标记时**不能**凭空造一个 Cookie 头 —— 第一次请求正是靠这个
    // 让站点下发一个。
    if (token) h['Cookie'] = 'ga=' + token;
    return h;
  }

  // 一次 GET。返回 { ok, body, cookies }。
  // 失败统一抛中文错误（空响应要说清是 UA 的问题）。
  async function fetchPage(ctx, url, keepGuest) {
    var args = { url: url, headers: headersWith(guest) };
    if (keepGuest) args.keep = ['ga'];

    var res;
    try {
      res = await ctx.call('net', args);
    } catch (e) {
      throw new Error('porn91 请求失败 ' + url + '：' + ((e && e.message) || e)
        + '（可能：当前网络连不上这个镜像）');
    }
    if (!res || res.ok !== true) {
      throw new Error('porn91 请求失败 ' + url + '：' + ((res && res.error) || '没有响应')
        + '（可能：镜像域名换了、或站点拦了这个请求）');
    }

    var body = String(res.body == null ? '' : res.body);
    if (body.length === 0) {
      // ★ 站点对不认识的 UA 回 200 + 0 字节。不在这里拦的话，上层拿到空串
      //   会一路解析成「0 条卡片」，看起来像「这个站没内容」—— 真实原因是 UA。
      throw new Error('站点返回了空响应（HTTP 200 但 0 字节）。'
        + '这个站按 User-Agent 分流：非浏览器 UA 直接回空。'
        + '可能：net 的 headers 被改掉了，或者镜像换了域名。');
    }
    return { body: body, cookies: (res && res.cookies) || {} };
  }

  // 拿/换游客标记。
  //
  // 逻辑和 Java 的 get() 一致，但**没有缓存**：第一次请求拿到的正文是
  // 「随机一部片」，不能拿它解析标题封面 —— 必须带着新标记重取一次。
  // 所以这里的做法是：调用方先用 needGuest() 检查一次，拿到标记后正常取。
  async function ensureGuest(ctx) {
    if (guest) return guest;
    var first = await fetchPage(ctx, BASE + '/v.php', true);
    // 留一份首页正文：tags() 要的导航栏就在这一份里。不带标记时**列表和导航
    // 都是对的**（只有详情会随机换片），所以这里可以直接复用，省掉一次 70KB 的请求。
    guestPage = first.body;
    var token = '';
    if (first.cookies && typeof first.cookies.ga === 'string') {
      token = first.cookies.ga.trim();
    }
    guest = token || FALLBACK_GUEST;
    if (!token) {
      // 站点没下发 —— 用兜底值继续。日志留痕，但不算失败。
      ctx.call('log', { msg: 'porn91 站点没下发 ga，用兜底值继续' });
    }
    return guest;
  }

  // 详情/播放专用：先确保有标记，再取页。
  // ★ **旧版正文要重取**。没有标记的那一次渲染出来的是随机片，拿它解析出来
  //   的标题封面全是错的（Java 那边也是这个做法，注释写着「这一份正文是随机片」）。
  async function fetchDetailPage(ctx, url) {
    var had = guest;
    await ensureGuest(ctx);
    var res = await fetchPage(ctx, url, false);
    if (!had && guest) {
      // 刚拿到标记：这一份可能是不带标记时渲染的。为了稳，重取一次。
      // （Java 那边无条件重取；这里只在「标记是新拿的」时候重取，
      //  免得每次详情都发两遍。）
      res = await fetchPage(ctx, url, false);
    }
    return res.body;
  }

  // ── 卡片解析（对齐 Porn91Adapter.parseCards）──────────────────────
  //
  // **不按容器 class 找**，而是按 `viewkey=` 出现的位置切块 —— 首页和分类页
  // 是两套栅格（col-lg-3 / col-lg-8），按 class 找会漏掉一半。
  //
  // 切法：以每个 <a href="…view_video.php?viewkey=…"> 为起点，往后截到
  // 下一个 `col-xs-12` 为止。这样封面、时长、标题、播放数全在块里，
  // 且不会把邻卡的数据吃进来。
  var CARD_RE = /<a[^>]+href=["'](?:https?:\/\/[^"']*)?view_video(?:_hd)?\.php\?viewkey=([0-9a-zA-Z]+)/gi;

  function cardTitle(chunk) {
    var m = /<span[^>]*class=["'][^"']*video-title[^"']*["'][^>]*>([\s\S]*?)<\/span>/i.exec(chunk);
    if (!m) return '';
    // 标题里可能带实体，而且实测有**双重编码**（&amp;#39;）—— 解两次。
    return unescape(unescape(stripHtml(m[1]))).replace(/\s+/g, ' ').trim();
  }

  function cardCover(chunk) {
    var m = /<img[^>]*class=["'][^"']*img-responsive[^"']*["'][^>]*\bsrc=["']([^"']+)["']/i.exec(chunk);
    if (m) return normalizeUrl(m[1]);
    // 兜底：任意 img 的 src，但跳过站点自己的图标和广告 gif
    var any = /<img[^>]*\bsrc=["']([^"']+)["']/gi;
    var one;
    while ((one = any.exec(chunk)) !== null) {
      var url = normalizeUrl(one[1]);
      if (!url) continue;
      if (url.indexOf('/images/') >= 0) continue;              // logo、arrowright.gif
      if (url.indexOf('fans.91selfie.com') >= 0) continue;      // 广告位 gif
      if (url.indexOf('cryptosign') >= 0) continue;
      if (url.indexOf('jhgfdsaqpwoeiru') >= 0) continue;
      return url;
    }
    return '';
  }

  function textOfClass(chunk, cls) {
    var m = new RegExp('class=["\'][^"\']*\\b' + cls + '\\b[^"\']*["\'][^>]*>([^<]{0,60})', 'i')
      .exec(chunk);
    return m ? unescape(m[1]).trim() : '';
  }

  // 列表卡里的 `<span class="info">Views:</span> 1951&nbsp;`
  function cardInfo(chunk, label) {
    var m = new RegExp(label + ':\\s*</span>\\s*([0-9][0-9,&nbsp;\\s]{0,20})', 'i').exec(chunk);
    if (!m) return '';
    return unescape(m[1]).replace(/[^0-9]/g, '');
  }

  function parseCards(html) {
    var out = [];
    var seen = {};
    CARD_RE.lastIndex = 0;
    var m;
    while ((m = CARD_RE.exec(html)) !== null) {
      var id = m[1];
      if (!id) continue;
      if (seen[id]) continue;          // 坑 ②：同一条在两种栅格里各画一遍
      seen[id] = 1;

      var start = m.index;
      var nextCard = html.indexOf('col-xs-12', start + m[0].length);
      var end = nextCard > 0 ? Math.min(nextCard, start + 4000)
                             : Math.min(html.length, start + 4000);
      var chunk = html.substring(start, end);

      var card = { id: id };
      card.title = cardTitle(chunk);
      card.cover = cardCover(chunk);

      // badge 放时长（00:05:11），前端画在封面右下角。
      var dur = textOfClass(chunk, 'duration');
      if (dur) card.badge = dur.trim();

      var views = cardInfo(chunk, 'Views');
      if (views) card.score = views + ' 次播放';
      out.push(card);
    }
    return out;
  }

  // ── 播放地址（对齐 Porn91Adapter.playSource / strencode）──────────

  // `document.write(strencode2("…"))` —— 取那一整串百分号编码。
  var STRENCODE_RE = /strencode2\(\s*["']([^"']+)["']\s*\)/i;
  // `<source src='…'>` / `<source src="…">`，跨行、属性顺序不固定。
  var SOURCE_SRC = /<source[^>]*\bsrc=["']([^"']+)["']/gi;
  // HTML 注释。剥它才能避开那条全站固定的诱饵 source。
  var COMMENT_RE = /<!--[\s\S]*?-->/g;

  // strencode2 只是个百分号解码，不是加密（定义在 /js/m2.js，jsjiami 混淆过，
  // 行为等价于「只解 %XX，不动 +」）。这里不复刻那个混淆函数，只做它最后的
  // 那一步 —— 见 percentDecode 上面那段说明。
  function strencode(html) {
    var m = STRENCODE_RE.exec(html);
    return m ? percentDecode(m[1]) : '';
  }

  function playSource(html) {
    // ① strencode2 解出来的那份（真的）
    var decoded = strencode(html);
    if (decoded) {
      SOURCE_SRC.lastIndex = 0;
      var m = SOURCE_SRC.exec(decoded);
      if (m) {
        var url = normalizeUrl(m[1]);
        if (url) return url;
      }
    }
    // ② 退回：**先剥注释**再抠裸 <source>，并跳过诱饵
    var stripped = html.replace(COMMENT_RE, '');
    SOURCE_SRC.lastIndex = 0;
    var one;
    while ((one = SOURCE_SRC.exec(stripped)) !== null) {
      var u = normalizeUrl(one[1]);
      if (u && u.indexOf('358999') < 0) return u;
    }
    return '';
  }

  // ── 详情元数据（对齐 Porn91Adapter.detail 里那几段）─────────────────

  // 第一个 `<h4 class="login_register_header">` 就是片名。
  // 后面还有两个同 class 的（"Video Information" / "Comment On This Video"），
  // 所以只取第一个。
  function firstH4(html) {
    var m = /<h4[^>]*class=["'][^"']*login_register_header[^"']*["'][^>]*>([\s\S]*?)<\/h4>/i
      .exec(html);
    if (!m) return '';
    return unescape(stripHtml(m[1])).replace(/\s+/g, ' ').trim();
  }

  // `<span class="info">Runtime: <span class="video-info-span">00:15:50</span></span>`
  // 用 label 定位，因为值在嵌套的第二个 span 里，按 class 取会撞车
  // （Views / Comments / Favorites / Point 全是同一个 class）。
  function infoSpan(html, label) {
    var m = new RegExp(label + ':\\s*<span[^>]*>([^<]{0,40})</span>', 'i').exec(html);
    return m ? unescape(m[1]).trim() : '';
  }

  // `<span class="info">Added: </span> <span class="title-yakov">2026-10-17</span>`
  function addedDate(html) {
    var m = /Added:\s*<\/span>\s*<span[^>]*>\s*([0-9]{4}-[0-9]{2}-[0-9]{2})/i.exec(html);
    return m ? m[1] : '';
  }

  // `<a href="…/uprofile.php?UID=…"><span class="title">王良明</span></a>`
  function uploader(html) {
    var m = /uprofile\.php\?UID=[^"']*"[^>]*>\s*<span[^>]*class=["'][^"']*title[^"']*["'][^>]*>([^<]{1,60})<\/span>/i
      .exec(html);
    return m ? unescape(m[1]).trim() : '';
  }

  // `<div id=v_desc class="more title" > … </div>`
  function description(html) {
    var m = /<div[^>]*id=["']?v_desc["']?[^>]*>([\s\S]*?)<\/div>/i.exec(html);
    if (!m) return '';
    return unescape(stripHtml(m[1])).replace(/\s+/g, ' ').trim();
  }

  // 取第一个匹配属性名的值（如 poster），顺手归一化双斜杠。
  function attr(html, name) {
    var m = new RegExp('<[a-z]+[^>]*\\b' + name + '=["\']([^"\']+)["\']', 'i').exec(html);
    return m ? normalizeUrl(unescape(m[1])) : '';
  }

  function between(html, open, close) {
    var a = html.indexOf(open);
    if (a < 0) return '';
    var b = html.indexOf(close, a + open.length);
    return b < 0 ? '' : html.substring(a + open.length, b);
  }

  function parseRelated(html, selfId) {
    var out = [];
    var cards = parseCards(html);
    for (var i = 0; i < cards.length && out.length < 12; i++) {
      if (cards[i].id === selfId) continue;
      out.push(cards[i]);
    }
    return out;
  }

  // ── 分页（对齐 Porn91Adapter.pagination / totalCount）──────────────
  //
  // 页数从页面那句 "Videos 1 - 24 of 15573" 算。**两个来源都要认**：
  //   分类页  <div class="videopaging">Videos 1 - 24 of 15573</div>
  //   搜索页  <h4>… (Result Videos 1 - 24 of 121)</h4>  ← 没有 videopaging
  // 算不出来时**不猜**：给 hasMore = 这一页有没有内容。
  var TOTAL_RE = /Videos\s+[0-9,\s]+-\s*[0-9,\s]+of\s+([0-9,]+)/i;

  function totalCount(html) {
    var m = TOTAL_RE.exec(html);
    if (!m) return 0;
    var n = parseInt(String(m[1]).replace(/,/g, ''), 10);
    return isFinite(n) ? n : 0;
  }

  function pageHasMore(page, got, html) {
    if (got <= 0) return false;
    var total = totalCount(html);
    if (total > 0) {
      var pages = Math.ceil(total / got);
      return page < pages;
    }
    // 拿不到总数：这一页有内容就当还有下一页（猜「有」最多多请求一次，
    // 猜「没有」会让后面几十页永远翻不到）。
    return true;
  }

  // ── 分类表（照抄 Porn91Site.CATEGORIES）──────────────────────────
  var CATEGORIES = [
    ['ori', '91原创'], ['hot', '热门'], ['top', '本周榜'], ['hd', '高清'],
    ['rf', '精选'], ['tf', '收藏'], ['long', '10分钟+'], ['longer', '20分钟+'],
    ['md', '讨论多'], ['mf', '最多收藏']
  ];

  // 标签名**以站点导航原文为准**，表只做兜底。理由和 1.0.30 那次一样：
  // 站点自己在改叫法（实测 2026-10-19：hot 叫「当前最热」不是「热门」，
  // top 叫「本月最热」不是「本周榜」，rf 叫「最近加精」不是「精选」），
  // 抄一份表就等于把名字冻在某一天的快照上，站点改名我们还显示旧名。
  //
  // 反过来也踩过：站点按 Accept-Language 分流（英文夹具里是 91 / Hot /
  // Top this Mon.），所以文本一旦不像「一条分类名」（空、超长、带标签残留），
  // 就退回表 —— 宁可显示旧中文名，也不要在标签页上显示一句英文句子。
  var LABEL_MAX = 16;

  function labelOf(code, raw) {
    var text = unescape(stripHtml(raw || '')).replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
    if (text && text.length <= LABEL_MAX && text.indexOf('<') < 0) return text;
    for (var i = 0; i < CATEGORIES.length; i++) {
      if (CATEGORIES[i][0] === code) return CATEGORIES[i][1];
    }
    return text || code;
  }

  // 导航栏的 `<li><A href="…/v.php?category=ori&viewtype=basic">91</A></li>`。
  // 大写 A 是站点自己的写法（夹具里就是 `<A>`），别写成 `<a` 不带 i。
  var NAV_RE = /<a[^>]+href=["'](?:https?:\/\/[^"']*)?v\.php\?category=([0-9a-zA-Z_]+)[^"']*["'][^>]*>([\s\S]{0,160}?)<\/a>/gi;

  // 导航 → 标签清单。同一条 code 会重复出现（top 有本周榜和上周榜两条，
  // 只是 m 参数不同），按 code 去重、保留第一次。
  //
  // **不足 3 条就当没抓到。** 站点改版时可能只剩一个「分类」链接，
  // 拿那一条去渲染整页「全部类型」比明确回退到 CATEGORIES 更糟 ——
  // 用户看到的是「1 个标签」，会以为这个站缩水了。
  function navigationTags(html) {
    var out = [];
    var seen = {};
    var m;
    NAV_RE.lastIndex = 0;
    while ((m = NAV_RE.exec(String(html || ''))) !== null) {
      var code = m[1];
      if (!code || seen[code]) continue;
      var name = labelOf(code, m[2]);
      if (!name) continue;
      seen[code] = 1;
      out.push({ name: name, slug: code, group: 'category' });
    }
    return out.length >= 3 ? out : [];
  }

  function fallbackTags() {
    return CATEGORIES.map(function (one) {
      return { name: one[1], slug: one[0], group: 'category' };
    });
  }

  // 分类页 / 标签页共用的一次请求。两件事在站点上是同一个页面，
  // 唯一差别是入参名字（tag-list 给 slug，离线回归给 ref）。
  async function categoryPage(ctx, args) {
    var code = String((args && (args.slug || args.cateId || args.ref)) || '').trim();
    var page = pageOf(args);
    if (!code) code = 'ori';
    await ensureGuest(ctx);
    var url = BASE + '/v.php?category=' + enc(code) + '&viewtype=basic&page=' + page;
    var res = await fetchPage(ctx, url, false);
    var items = parseCards(res.body);
    if (items.length === 0 && page === 1) {
      throw new Error('porn91 分类「' + code + '」一页都没解析到卡片'
        + '（可能：这个 code 不在站点的分类表里、或站点改版）');
    }
    return { items: items, page: page, hasMore: pageHasMore(page, items.length, res.body) };
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
    id: 'porn91',
    name: '91Porn',
    version: 1,
    requires: '>=1',
    // 站点域（含已知镜像）。封面和媒体在 CDN 上，但 spider 不请求它们。
    hosts: ['901.26702.app', '91porn.com'],

    // 首页。打 `/v.php`（不是 `/`，那个是年龄门 —— 坑 ①）。
    //
    // 分区怎么来：站点首页只有一列 Latest Videos，没有独立的分区接口，
    // 所以 Java 那边做两次请求拼 featured + latest。spider 这边**只打一次**：
    // 分区是 UI 的决定，model.js 的 home() 会拿 items 自己切 featured。
    // 多打一次 rf 只为了换个分区，收益小于首屏慢一倍。
    //
    // 这一版**必须带游客标记**：不带的话首页列表本身是对的（列表页不受影响），
    // 但接下来点进去的详情会是随机片。所以 home 顺手把标记捡回来 ——
    // 这是唯一一个「顺路」的机会，详情是独立的一次 WebView 调用。
    async home(ctx) {
      await ensureGuest(ctx);
      var res = await fetchPage(ctx, BASE + '/v.php', false);
      var items = parseCards(res.body);
      if (items.length === 0) {
        throw new Error('porn91 首页没解析到卡片（可能：站点改版、镜像换了、'
          + '或者拿到的是年龄确认页）');
      }
      return { items: items, page: 1, hasMore: false };
    },

    // 分类列表。slug 是站点的 category code（ori / hot / top / …）。
    // 不认识的 code 会被站点当成默认列表（回全站最新）**不会报错** ——
    // 所以校验靠分类表：只有那十个实测过的 code。
    async category(ctx, args) {
      return categoryPage(ctx, args);
    },

    // 标签列表 → 就是分类列表（坑 ⑦：这个站的标签栏跟分类栏是同一排链接）。
    // 单独留一个 cap 名，是因为别的源（hanime1 / mrds66）的 tag-list 语义是
    // 「话题标签」，前端按动作名分派，两边不能混着来。
    async tagList(ctx, args) {
      return categoryPage(ctx, args);
    },

    // 「全部类型」页的标签清单。首页导航栏就是标签栏。
    //
    // 拿不到首页**不报错**：分类表是静态的，退回那十条至少页面能用。
    // 这条降级和 detail 的 tags:[] 不冲突 —— 一个是整站有哪些分类，
    // 一个是这一部片挂了哪些标签（站点不提供）。
    async tags(ctx) {
      await ensureGuest(ctx);
      var body = guestPage;
      if (!body) {
        // 正常走不到（ensureGuest 必然留下正文）。留着是防它哪天被改成缓存分支。
        try {
          body = (await fetchPage(ctx, BASE + '/v.php', false)).body;
        } catch (e) {
          ctx.call('log', { msg: 'porn91 标签导航抓取失败，用内置分类表：' + ((e && e.message) || e) });
          return { items: fallbackTags() };
        }
      }
      var items = navigationTags(body);
      if (items.length === 0) {
        ctx.call('log', { msg: 'porn91 导航栏里没抠到分类链接，用内置分类表' });
        return { items: fallbackTags() };
      }
      return { items: items };
    },

    // 列表卡片的标题/封面校正（坑 ⑧）。
    //
    // 站点在网格页上把**标题和封面挂到别的 viewkey 上**：同一页抓两次，23 条里
    // 有 4 条的标题会变；点进去看到的是那个 viewkey 的真身，于是「货不对版」——
    // 标题、封面、描述、视频全是另一部片，而三者内部还是自洽的，看着像串了。
    //
    // 唯一可信的是详情页（同一个 viewkey 连取三次，标题/封面/直链稳定且互相同号：
    // thumb/1253049.jpg ↔ mp43/1253049.mp4）。
    //
    // ── 用 netAll，不用循环 net ─────────────────────────────────────
    //
    // `ctx.call('net')` 是同步桥，源里写 `Promise.all(ids.map(fetch))` 看着像并发，
    // 实际一条一条排队。2026-10-19 实测：8 个 worker 拉 24 条详情跑到 45 秒被看门狗
    // 杀掉，零结果 —— 当时只能把校正搬到服务器上做，等于给这个源加了一个外部依赖。
    //
    // netAll 是 Java 侧线程池：一次往返带 24 个请求，8 并发，实测 3 轮走完。
    // 源又变回自包含的一个 js 文件。
    async reconcile(ctx, args) {
      var raw = (args && args.ids) || [];
      if (typeof raw === 'string') raw = raw.split(',');
      var ids = [];
      for (var i = 0; i < raw.length; i++) {
        var one = String(raw[i] == null ? '' : raw[i]).trim();
        if (!one) continue;
        // 和 detail 同一条校验：viewkey 只有纯数字和二十位十六进制两种。
        if (!/^[0-9a-zA-Z]+$/.test(one)) continue;
        if (ids.indexOf(one) >= 0) continue;
        ids.push(one);
        if (ids.length >= 24) break;      // 一页 24 条
      }
      if (ids.length === 0) return { items: [] };

      // 校对详情页也要带游客标记，否则站点会给随机一部片（坑 ⑥）。
      await ensureGuest(ctx);
      var requests = [];
      for (var k = 0; k < ids.length; k++) {
        requests.push({
          url: BASE + '/view_video.php?viewkey=' + enc(ids[k]),
          headers: headersWith(guest)
        });
      }

      var res;
      try {
        res = await ctx.call('netAll', { requests: requests, concurrency: 8 });
      } catch (e) {
        ctx.call('log', { msg: 'porn91 校正失败：' + ((e && e.message) || e) });
        return { items: [] };
      }
      var results = (res && res.results) || [];
      var out = [];
      for (var j = 0; j < results.length && j < ids.length; j++) {
        var got = results[j] || {};
        if (got.ok !== true) continue;          // 单条失败保留原值
        var html = String(got.body || '');
        if (!html) continue;                    // 空响应（UA 被分流）也保留原值
        var title = firstH4(html);
        if (!title) title = stripHtml(between(html, '<title>', '</title>')).trim();
        var cover = attr(html, 'poster');
        if (title || cover) out.push({ id: ids[j], title: title || '', cover: cover || '' });
      }
      return { items: out };
    },

    // 搜索。空关键词**不发请求**：站点对空 search_id 会回首页那份列表，
    // 看起来像「搜索成功了但结果全是热门」，比失败更糟。
    async search(ctx, args) {
      var keyword = String((args && (args.keyword || args.ref)) || '').trim();
      var page = pageOf(args);
      if (!keyword) {
        throw new Error('porn91 搜索词为空，没有发请求（检查一下输入框是不是空的）');
      }
      await ensureGuest(ctx);
      var url = BASE + '/search_result.php?search_id=' + enc(keyword)
        + '&search_type=search_videos&page=' + page;
      var res = await fetchPage(ctx, url, false);
      var items = parseCards(res.body);
      return { items: items, page: page, hasMore: pageHasMore(page, items.length, res.body) };
    },

    // 详情。元数据全在 DOM 里，这个站没写 OG meta。
    // ★ **拿不到 tags**：站点没有标签体系，只有十个固定分类。给空数组是
    //   站点的形状，不是解析失败。
    async detail(ctx, args) {
      var ref = splitRef(args && args.ref);
      var id = ref.id;
      if (!id) throw new Error('porn91 详情缺少 ref（viewkey）');
      if (!/^[0-9a-zA-Z]+$/.test(id)) {
        throw new Error('porn91 详情 ref 不是合法 viewkey：' + id
          + '（应当是纯数字或二十位十六进制）');
      }

      var html = await fetchDetailPage(ctx, BASE + '/view_video.php?viewkey=' + enc(id));

      var title = firstH4(html);
      if (!title) title = stripHtml(between(html, '<title>', '</title>')).trim();
      if (!title) {
        throw new Error('详情页里没找到标题（viewkey=' + id
          + '）。可能：片子已删除、或站点改版。');
      }

      var duration = infoSpan(html, 'Runtime');
      var views = infoSpan(html, 'Views');
      var favs = infoSpan(html, 'Favorites');
      var added = addedDate(html);
      var up = uploader(html);

      var extra = {};
      if (views) extra.views = views.trim();
      if (favs) extra.favorites = favs.trim();
      if (added) extra.added = added;
      if (up) extra.uploader = up;

      // 单集站：一个 viewkey 一部片。episodes 仍要给一条 ——
      // 前端拿它决定「点播放走哪条路」。
      var episodes = [{
        no: 1,
        title: title,
        line: 'default',
        ref: 'ep1',
        episodeNumber: 1,
        id: 'ep1',
        duration: duration
      }];

      return {
        id: id,
        title: title,
        cover: attr(html, 'poster'),
        description: description(html),
        duration: duration,
        // 站点的「点数」不是评分，别硬塞给 score —— 空着比给错的值好。
        score: '',
        // 详情页不写 category，所以留空，前端不画那个角标。
        category: '',
        tags: [],
        extra: extra,
        episodes: episodes,
        episodeCount: 1,
        lines: [{ line: 'default', name: '原画' }],
        related: parseRelated(html, id)
      };
    },

    // 播放地址。**每次实时抓详情页，零缓存** —— 签名 `e=` 22 分钟就过期，
    // 缓存等于「过一会儿点开就是 403」。也正因如此这里不读 detail 的东西。
    //
    // ep 在这个单集站上没有「集」的含义（一个 viewkey 只给一条 source），
    // 忽略它，但保留参数位，免得将来站点加画质档还要改路径模板。
    async play(ctx, args) {
      var ref = splitRef(args && args.ref);
      var id = ref.id;
      if (!id) throw new Error('porn91 播放缺少 ref（viewkey）');

      var html = await fetchDetailPage(ctx, BASE + '/view_video.php?viewkey=' + enc(id));
      var source = playSource(html);
      if (!source) {
        throw new Error('这个页面里没有可播的地址（viewkey=' + id + '）。'
          + '可能：片子已下架、签名段被站点改了、或这是会员片。');
      }

      return {
        url: source,
        alts: [{ line: '原画', url: source }],
        episodeNumber: 1
      };
    }
  };
})();
