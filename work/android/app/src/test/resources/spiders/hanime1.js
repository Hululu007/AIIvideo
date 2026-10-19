// hanime1.me —— 单站 HTML 型 spider 源。
//
// 语义照抄 Hanime1Adapter.java（parseCards / parseSources / meta / parseTags /
// parseRelated / qualityOf / pagination），不重新发明解析：同一个 HTML，
// 这里和 Java 适配器算出来的字段必须一致。
// 只用字符串 + 正则，**不许用 DOM** —— 这个文件要能在没有 DOM 的沙箱里跑离线回归。
//
// ── 站点形状（2026-10-16 实测，全部裸请求可拉，无 Cookie 无鉴权）──────
//   GET /                                144 张卡（10 个横排区）
//   GET /search?genre=裏番&page=1          41 张（40 真 + 1 广告）
//   GET /search?query=<词>&page=1          搜索
//   GET /search?sort=最新上傳&page=1       最新
//   GET /watch?v=<id>                     详情 + 三档直链
//
// ── 三道必须知道的坑 ─────────────────────────────────────────────────
//
// ① **站点在 Cloudflare 后面，闸门卡在 TLS 指纹上。** 2026-10-17 真机实测
//    （设备 7f8f5254）**过了** —— 安卓 OkHttp 走 Conscrypt（BoringSSL，和
//    Chrome 同族），首页/分类/详情/播放四层全通，logcat 里没有一次
//    `Attention Required`。但这是被验证过的推断不是定理：CF 改策略、或
//    Android 换掉 TLS 实现，这条都会失效。所以收到 403 时要明确说出
//    「可能是挖井/CF」，不能让用户以为片子没了。
//
// ② **ep 参数是画质，不是集数。** 这是单集站（一个 id 就是一部完整的片），
//    480/720/1080 对应页面三档 <source>，缺省 720（站点 Plyr 的
//    quality.default）。画质从文件名读（`408706-720p.mp4`），不从 DOM
//    顺序推 —— 实测顺序是 720/480/1080，既不是升序也不是降序。
//
// ③ **签名不能算，只能现取。** 页面给的 `?secure=` 约 12 小时过期，
//    改一位就 403（实测四种改法全是 403）。所以 play 每次实时抓详情页，
//    **零缓存** —— 也正因如此它不是读 detail 的缓存。
//
// ── 降级说明（与 Java 的差异，只此一处）──────────────────────────────
//
// Java 的 play 会按画质挑一条当 `source`，`alts` 里三档全交出去。这里一致。
// 但 Java 那边 `ep` 是数字入参（`/api/videos/{id}/play?ep=720`），spider 的
// 约定是 `ref` 里带集号 —— 所以这里同时接受三种写法：
//
//   ref = '408706'            画质默认 720
//   ref = '408706:ep720'      app.js 的 episode 分支就这么拼（ep + 数字）
//   ref = '408706:720'        裸数字也认
//
// `ep480` 这种写法里的 `ep` 前缀是前端加的，不是画质的表示，必须剥掉，
// 否则 qualityOf 会把它当成「名字里没有 720p」直接掉到 0。
(function () {
  var HOST = 'https://hanime1.me';
  // 媒体和封面同一个域，列表页/详情页/视频全在这一个域上。
  // 但白名单只写真正会被请求的域：页面请求只有 hanime1.me，
  // vdownload 是**播放器**去拉的，spider 自己不请求它 —— 所以 hosts 只放站点域。
  // （封面 URL 原样交给前端，前端有 cover.allowedHosts 那道闸，那是另一套。）
  var GENRES = [
    '裏番', '泡麵番', 'Motion Anime', '3DCG', '2.5D',
    '2D動畫', 'AI生成', 'MMD', 'Cosplay', '新番預告'
  ];
  // 首页取前 48 张。首页有 10 个横排区、144 张卡，全塞进去首屏太重。
  var HOME_LIMIT = 48;
  // 相关推荐条数。站点 allIds 给了 96 个，前 12 个才是真相关的。
  var RELATED_LIMIT = 12;
  // 少于此数就认为列表到底了（Hanime1Adapter.END_THRESHOLD）。
  var END_THRESHOLD = 30;

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

  // HtmlKit.stripTags：只删标签、**不插空格**，收尾 trim。
  // 和 text() 的区别就是 `""` vs `" "`：`<b>a</b><b>b</b>` 要得到 ab。
  function stripTags(s) {
    return String(s == null ? '' : s).replace(/<[^>]+>/g, '').trim();
  }

  function text(s) {
    return unescape(String(s == null ? '' : s).replace(/<[^>]*>/g, ' '))
      .replace(/\s+/g, ' ').trim();
  }

  // HtmlKit.normalizeUrl：`https://a.com//x/y.jpg` → `https://a.com/x/y.jpg`。
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

  // HtmlKit.enc：RFC 3986 unreserved 原样保留，其余按 UTF-8 转 %XX。
  // 中文标签和搜索词全走这条路，编错了标签页就全废。
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

  // ── 请求 ─────────────────────────────────────────────────────────

  // 浏览器头。hanime1 不需要伪装（它查的是 TLS 指纹，不是 header），
  // 但带上更接近真实浏览器，也不会让中间设备把它当爬虫。
  var HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36'
      + ' (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8'
  };

  // CF 挑战页的判据。**必须显式报出来** —— 静默返回空列表会被当成
  // 「这个站没内容」，用户会去别处找原因。
  var CF_PATTERNS = [
    /Attention Required/i,
    /cf-browser-verification/i,
    /Just a moment/i,
    /Checking your browser/i,
    /cf-chl-/i,
    /<title>\s*403\s*<\/title>/i
  ];

  function looksLikeCloudflare(html) {
    for (var i = 0; i < CF_PATTERNS.length; i++) {
      if (CF_PATTERNS[i].test(html)) return true;
    }
    return false;
  }

  async function fetchHtml(ctx, url) {
    var res;
    try {
      res = await ctx.call('net', { url: url, headers: HEADERS });
    } catch (e) {
      throw new Error('hanime1 请求失败 ' + url + '：'
        + ((e && e.message) || e) + '（可能：网络不通或被墙）');
    }
    if (!res || res.ok !== true) {
      var why = (res && res.error) || '没有响应';
      // 403 在这个站上大概率是 CF 的 TLS 指纹闸（见文件头 ①）。
      if (/403/.test(String(why))) {
        throw new Error('hanime1 被 Cloudflare 拦了（403）：这个站查 TLS 指纹，'
          + '安卓通常能过，当前这次没过。可能：网络出口变了、或站点收紧了判定。');
      }
      throw new Error('hanime1 请求失败 ' + url + '：' + why
        + '（可能：站点挂了、或该地区被墙）');
    }
    var body = String(res.body == null ? '' : res.body);
    // 站点对「不认识的客户端」会给 200 + 挑战页。所以拿不到内容时先看这个。
    if (looksLikeCloudflare(body)) {
      throw new Error('hanime1 返回的是 Cloudflare 挑战页，不是内容页。'
        + '这个站按 TLS 指纹判定，当前客户端没过；换网络或等几分钟再试。');
    }
    return body;
  }

  // ── meta（对齐 Hanime1Adapter.meta）────────────────────────────────
  //
  // 两种属性顺序都要认：content 在前 / 属性在前。站点换了模板顺序也不至于全空。
  function meta(html, attr, key) {
    var p1 = new RegExp('<meta[^>]*' + attr + '=["\']' + escapeRe(key)
      + '["\'][^>]*content=["\']([^"\']*)["\']', 'i');
    var m = p1.exec(html);
    if (m) return unescape(m[1]);
    var p2 = new RegExp('<meta[^>]*content=["\']([^"\']*)["\'][^>]*' + attr
      + '=["\']' + escapeRe(key) + '["\']', 'i');
    m = p2.exec(html);
    return m ? unescape(m[1]) : '';
  }

  function escapeRe(s) {
    return String(s == null ? '' : s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  // 取 class 含 xxx 的元素里的文本。够用就行，不做完整 DOM。
  function textOfClass(chunk, cls) {
    var p = new RegExp('class=["\'][^"\']*\\b' + escapeRe(cls)
      + '\\b[^"\']*["\'][^>]*>([^<]{0,300})', 'i');
    var m = p.exec(chunk);
    return m ? unescape(m[1]).trim() : '';
  }

  function attrOfImg(chunk, name) {
    var m = new RegExp('<img[^>]*\\b' + escapeRe(name) + '=["\']([^"\']*)["\']', 'i').exec(chunk);
    return m ? unescape(m[1]) : '';
  }

  // 卡片里第一个 <a 的 title 属性（`<[a-z]+[^>]*` 而不是只认 <a ——
  // Java 那边是通用标签，保持一致）。
  function attr(chunk, name) {
    var m = new RegExp('<[a-z]+[^>]*\\b' + escapeRe(name) + '=["\']([^"\']*)["\']', 'i').exec(chunk);
    return m ? unescape(m[1]) : '';
  }

  function imgSrc(chunk) {
    var re = /<img[^>]*\bsrc=["']([^"']+)["']/gi;
    var m;
    while ((m = re.exec(chunk)) !== null) {
      var src = m[1];
      if (!src) continue;
      if (src.indexOf('/image/icon/') >= 0) continue;   // 广告位图集，不是封面
      if (src.indexOf('//') === 0) src = 'https:' + src;
      return src;
    }
    return '';
  }

  // 列表页那句 `thumb_up 98%`。取出来当 score 用。
  function statText(chunk) {
    var m = /thumb_up<\/i>\s*([0-9]{1,3}%)/.exec(chunk);
    if (m) return m[1];
    m = /([0-9]{1,3})%/.exec(chunk);
    return m ? m[1] + '%' : '';
  }

  function uploaderOf(chunk) {
    var m = /class=["']subtitle["'][^>]*>\s*<a[^>]*>([^<]{1,80})<\/a>/i.exec(chunk);
    return m ? unescape(m[1]).trim() : '';
  }

  function between(html, open, close) {
    var a = html.indexOf(open);
    if (a < 0) return '';
    var b = html.indexOf(close, a + open.length);
    return b < 0 ? '' : html.substring(a + open.length, b);
  }

  function stripSuffix(s, suffix) {
    var v = String(s == null ? '' : s).trim();
    if (suffix && v.length >= suffix.length
        && v.substring(v.length - suffix.length) === suffix) {
      return v.substring(0, v.length - suffix.length).trim();
    }
    return v;
  }

  function stripPrefix(s, prefix) {
    var v = String(s == null ? '' : s).trim();
    if (prefix && v.length >= prefix.length && v.substring(0, prefix.length) === prefix) {
      return v.substring(prefix.length).trim();
    }
    return v;
  }

  // ── 卡片解析（对齐 Hanime1Adapter.parseCards）──────────────────────
  //
  // **首页和列表页是两套 DOM 形状**，所以不按 class 找容器，改成
  // 「找所有 a[href*=/watch?v=] → 校验 id → 在附近找 img / .title / .duration」。
  // 广告卡靠 href 过滤：列表页里混着 l.erodalabs.com 的推广位，它跟真卡
  // 长得一模一样，只有 href 不同。
  //
  // ★ 两套形状给的信息量不一样，别指望字段齐：首页有 duration/score，
  //   列表页只有封面 + 标题。空串是站点的形状，不是解析失败。
  var CARD_RE = /<a[^>]+href=["'](?:https?:\/\/hanime1\.me)?\/watch\?v=(\d+)["']/gi;

  function parseCards(html) {
    var out = [];
    var seen = {};
    CARD_RE.lastIndex = 0;
    var m;
    while ((m = CARD_RE.exec(html)) !== null) {
      var id = m[1];
      if (!id) continue;
      if (seen[id]) continue;
      seen[id] = 1;

      // 从这个 <a 往后截一段当这条卡的局部 HTML。截到下一个 <a 为止，
      // 避免把邻卡的标题吃进来。
      var start = m.index;
      var next = html.indexOf('<a ', m.index + m[0].length);
      var end = next > 0 ? Math.min(next, start + 4000) : Math.min(html.length, start + 4000);
      var chunk = html.substring(start, end);

      var card = { id: id };
      var title = attr(chunk, 'title');
      if (!title) title = textOfClass(chunk, 'title');
      if (!title) title = textOfClass(chunk, 'home-rows-videos-title');
      if (!title) title = attrOfImg(chunk, 'alt');
      card.title = title.trim();
      card.cover = imgSrc(chunk);

      var dur = textOfClass(chunk, 'duration');
      if (dur) card.badge = dur.trim();
      var stat = statText(chunk);
      if (stat) card.score = stat;
      var uploader = uploaderOf(chunk);
      if (uploader) card.uploader = uploader;
      out.push(card);
    }
    return out;
  }

  // ── 标签（对齐 Hanime1Adapter.parseTags）───────────────────────────
  //
  // twitter:description = 「[RD] 标题 - tag1, tag2, …, 1080p - Hanime1」
  // 剥头尾，中间按逗号切。**画质词混在里面**（1080p），过滤掉 —— 它不是标签。
  var QUALITY_WORD = /^\d{3,4}[pP]$/;

  function parseTags(raw, title) {
    var out = [];
    if (!raw) return out;
    var s = stripSuffix(String(raw).trim(), ' - Hanime1');
    if (title) s = stripPrefix(s, title + ' - ');
    var parts = s.split(',');
    for (var i = 0; i < parts.length; i++) {
      var tag = parts[i].trim();
      if (!tag) continue;
      if (QUALITY_WORD.test(tag)) continue;    // 1080p / 720p
      if (tag === '中文字幕') continue;          // 规格不是标签
      out.push(tag);
    }
    return out;
  }

  // ── 相关推荐（对齐 Hanime1Adapter.parseRelated）────────────────────
  //
  // `let allIds = [408706,408614,…]` 这行内联脚本直接给了 id 数组。
  // ★ 必须截断：实测这个数组有 96 个 id（站点把「全部影片」塞进了一个
  //   allIds），全交出去前端会画一条 96 张卡、每张都没有标题封面的长龙。
  //   前 12 个才是真正的相关推荐。
  var ALLIDS_RE = /allIds\s*=\s*\[([0-9,\s]{0,2000})\]/;

  function parseRelated(html, selfId) {
    var out = [];
    var m = ALLIDS_RE.exec(html);
    if (m) {
      var parts = m[1].split(',');
      for (var i = 0; i < parts.length && out.length < RELATED_LIMIT; i++) {
        var id = parts[i].trim();
        if (!id || id === selfId) continue;
        out.push({ id: id });
      }
      if (out.length > 0) return out;
    }
    var cards = parseCards(html);
    for (var k = 0; k < cards.length && out.length < RELATED_LIMIT; k++) {
      if (cards[k].id === selfId) continue;
      out.push(cards[k]);
    }
    return out;
  }

  // ── 播放（对齐 Hanime1Adapter.parseSources / qualityOf / closest）──

  // `<source src="…" type="video/mp4">`，跨行、属性顺序不固定。
  var SOURCE_RE = /<source[^>]*\bsrc=["']([^"']+)["']/gi;
  var QUALITY_RE = /-(\d{3,4})p\b/;

  function qualityOf(url) {
    var m = QUALITY_RE.exec(String(url || ''));
    if (m) {
      var n = parseInt(m[1], 10);
      return isFinite(n) ? n : 0;
    }
    return 0;
  }

  function stripHash(url) {
    var at = String(url).indexOf('#');
    return at < 0 ? url : url.substring(0, at);
  }

  function parseSources(html) {
    var out = [];
    var seen = {};
    SOURCE_RE.lastIndex = 0;
    var m;
    while ((m = SOURCE_RE.exec(html)) !== null) {
      var url = m[1];
      if (!url) continue;
      if (url.indexOf('//') === 0) url = 'https:' + url;
      if (url.indexOf('http') !== 0) continue;
      url = stripHash(normalizeUrl(url));
      if (seen[url]) continue;      // 同一地址出现两次（页面里 hls + mp4 混排时会）
      seen[url] = 1;
      out.push({ url: url, quality: qualityOf(url) });
    }
    return out;
  }

  // 精确档位没有时退到最接近的：优先 ≥ want 的最小档，没有就取最大。
  function closest(sources, want) {
    var best = '', fallback = '', bestGap = 99999, maxQ = -1;
    for (var i = 0; i < sources.length; i++) {
      var s = sources[i];
      if (!s.url) continue;
      if (s.quality >= want && s.quality - want < bestGap) {
        bestGap = s.quality - want;
        best = s.url;
      }
      if (s.quality > maxQ) {
        maxQ = s.quality;
        fallback = s.url;
      }
    }
    return best || fallback;
  }

  // ── 分页（对齐 Hanime1Adapter.pagination）──────────────────────────
  //
  // 页数拿不到（没有总数、没有 JSON 接口）。判据反过来用：**这一页条目数
  // 明显少于常规就当最后一页**。分类页通常 40 张、搜索页 59 张，
  // 阈值取保守的 30。越界页返回 200 + 空列表，所以「空 = 结束」本身可靠，
  // 这里多给一页容差让前端自然停下。
  function hasMore(page, got) {
    return got >= END_THRESHOLD;
  }

  // ── 能力 ──────────────────────────────────────────────────────────

  function splitRef(ref) {
    var raw = String(ref == null ? '' : ref).trim();
    var i = raw.indexOf(':');
    if (i < 0) return { id: raw, ep: '' };
    return { id: raw.substring(0, i), ep: raw.substring(i + 1) };
  }

  // ── 标签面板（240 个，7 个分区）────────────────────────────────────
  //
  // 站点把它塞在任意分类页的模态框里：`<h4>內容標籤</h4>` 之后是 7 个 `<h5>分区名</h5>`，
  // 每个分区下面一串
  //
  //   <label class="hentai-tags-wrapper">
  //     <input name="tags[]" type="checkbox" value="無碼"/>
  //
  // **它不是「标签 → URL」表，是多选表单**。所以点一个标签不是访问某个地址，
  // 而是走 `tagList` 拼 `?genre=…&tags%5B%5D=…`（两个坑见下面 tagList 的注释）。
  //
  // 分区名 → 配置里 ui.tagGroups 的 id。站点是繁体，两份都收 —— 站点切语言时
  // 不该整页标签掉进 other。
  var SECTION_GROUP = {
    '影片屬性': 'hanime-attr', '影片属性': 'hanime-attr',
    '人物關係': 'relationship', '人物关系': 'relationship',
    '角色設定': 'hanime-role', '角色设定': 'hanime-role',
    '外貌身材': 'style',
    '情境場所': 'setting', '情境场所': 'setting',
    '故事劇情': 'plot', '故事剧情': 'plot',
    '性交體位': 'hanime-act', '性交体位': 'hanime-act'
  };

  /** 从分类页 HTML 里抠出 [{title, tags:[名字…]}]。抠不到返回空数组。 */
  function tagSections(html) {
    var src = String(html || '');
    // 先截到「內容標籤」到 </form> 之间：页面里还有别的 h5，不截会收进无关的东西。
    var start = src.indexOf('內容標籤');
    if (start < 0) start = src.indexOf('内容标签');
    if (start < 0) return [];
    var stop = src.indexOf('</form>', start);
    var area = src.substring(start, stop > start ? stop : src.length);

    var out = [];
    var sections = /<h5[^>]*>([\s\S]{1,40}?)<\/h5>([\s\S]*?)(?=<h5|<\/form>|$)/gi;
    var m;
    while ((m = sections.exec(area)) !== null) {
      var title = stripTags(m[1]).trim();
      if (!title) continue;
      var tags = [];
      var seen = {};
      var labels = /<label[^>]*hentai-tags-wrapper[\s\S]{0,400}?<\/label>/gi;
      var lm;
      while ((lm = labels.exec(m[2])) !== null) {
        var block = lm[0];
        var value = /value=["']([^"']+)["']/i.exec(block);
        var name = value ? unescape(value[1]).trim()
          : stripTags((/<span[^>]*checkmark[^>]*>([\s\S]*?)<\/span>/i.exec(block) || [])[1] || '').trim();
        if (!name || seen[name]) continue;
        seen[name] = 1;
        tags.push(name);
      }
      if (tags.length) out.push({ title: title, tags: tags });
    }
    return out;
  }

  function pageOf(args) {
    var n = parseInt((args && args.page) || 1, 10);
    return isFinite(n) && n > 0 ? n : 1;
  }

  // 'ep720' / '720' / 'ep1080' / '' → 720 / 720 / 1080 / 0（0 = 用默认）。
  // `ep` 前缀是前端为了统一 episode 形状加的，不是画质的一部分。
  function qualityFrom(ep) {
    var s = String(ep == null ? '' : ep).trim().toLowerCase();
    s = s.replace(/^ep/, '');
    if (!s) return 0;
    var n = parseInt(s, 10);
    return isFinite(n) && n > 0 ? n : 0;
  }

  __spiderSite = {
    id: 'hanime1',
    name: 'Hanime1',
    version: 1,
    requires: '>=1',
    // 只有站点域会被 spider 请求。播放地址在 vdownload.hembed.com，
    // 但那是播放器去拉的，spider 不碰 —— 白名单不写它，写了等于多开一个口子。
    hosts: ['hanime1.me'],

    // 首页一次 GET /：整个页面是 SSR 的，144 张卡一次拿到。
    // 分区标题是布局 div 不是语义 section，不好稳定抠，所以不按区拆 ——
    // 平铺给 items，切 featured 是 model.js 的事。
    // 去重靠 id：同一个作品会在多个区里重复出现，不去重首页会看到重影。
    async home(ctx) {
      var html = await fetchHtml(ctx, HOST + '/');
      var cards = parseCards(html);
      var items = [];
      var seen = {};
      for (var i = 0; i < cards.length && i < HOME_LIMIT; i++) {
        var c = cards[i];
        if (!c.id || seen[c.id]) continue;
        seen[c.id] = 1;
        items.push(c);
      }
      if (items.length === 0) {
        throw new Error('hanime1 首页没解析到卡片（可能：站点改版、或该地区被墙）');
      }
      return { items: items, page: 1, hasMore: false };
    },

    // 分类列表。slug 就是站点的 genre 中文词（裏番 / 泡麵番 / 3DCG …）。
    // ⚠️ 它**只管那 10 个 genre**；240 个细标签走 tags[] 参数，是另一条路
    //    （站点那边 genre= 和 tags[]= 是两回事，别混）。
    async category(ctx, args) {
      // 入参兼容两套名字：app.js 给 slug/cateId，离线回归（spider-check）给 ref。
      var genre = String((args && (args.slug || args.cateId || args.ref)) || '').trim();
      var page = pageOf(args);
      if (!genre) genre = GENRES[0];     // 兜底用最大的分类，不是筛选意图
      var html = await fetchHtml(ctx, HOST + '/search?genre=' + enc(genre) + '&page=' + page);
      var items = parseCards(html);
      return { items: items, page: page, hasMore: hasMore(page, items.length) };
    },

    /**
     * 全部类型：站点那 240 个细标签，7 个分区。
     *
     * 这是这个站最值钱的一层 —— 10 个 genre 只是导航，真正决定「找不找得到」
     * 的是这些标签（无码 / 巨乳 / NTR / 手交…）。
     *
     * 面板在任意分类页里，所以这里就是打一次分类页把它抠出来。**不缓存**：
     * 标签集是站点自己维护的，加一个少一个都不该等发版。一次分类页 111KB，
     * 用户点进「全部类型」才发生一次。
     */
    async tags(ctx) {
      var html = await fetchHtml(ctx, HOST + '/search?genre=' + enc(GENRES[0]) + '&page=1');
      var sections = tagSections(html);
      if (sections.length === 0) {
        throw new Error('hanime1 标签面板没抠到（可能：模态框结构改了、或这一页没带面板）');
      }
      var out = [];
      for (var i = 0; i < sections.length; i++) {
        var group = SECTION_GROUP[sections[i].title] || 'other';
        for (var j = 0; j < sections[i].tags.length; j++) {
          out.push({ name: sections[i].tags[j], slug: sections[i].tags[j], group: group });
        }
      }
      return { items: out };
    },

    /**
     * 按标签列内容。
     *
     * 两个坑（2026-10-17 实测，今天原样还在）：
     *
     * ① **方括号要编码成 %5B%5D**。直接写 `tags[]=` 有时被中间层吃掉，
     *    表现是「点了标签出来的还是默认列表」，而且不报错。
     * ② **必须带 genre**。只给 `tags[]` 时站点回默认全站列表，标签像没生效。
     *    所以这里挂一个 GENRES[0] 当 seed —— 那是兜底不是筛选意图，
     *    站点要有一个 genre 才肯认真过滤标签。
     */
    async tagList(ctx, args) {
      var tag = String((args && (args.slug || args.label || args.ref)) || '').trim();
      var page = pageOf(args);
      if (!tag) throw new Error('hanime1 标签为空，没有发请求');
      var url = HOST + '/search?genre=' + enc(GENRES[0])
        + '&tags%5B%5D=' + enc(tag) + '&page=' + page;
      var html = await fetchHtml(ctx, url);
      var items = parseCards(html);
      return { items: items, page: page, hasMore: hasMore(page, items.length) };
    },

    // 搜索。空关键词**不发请求**（不是「返回首页那份」—— 那看起来像搜到了）。
    async search(ctx, args) {
      var keyword = String((args && (args.keyword || args.ref)) || '').trim();
      var page = pageOf(args);
      if (!keyword) {
        throw new Error('hanime1 搜索词为空，没有发请求（检查一下输入框是不是空的）');
      }
      var html = await fetchHtml(ctx, HOST + '/search?query=' + enc(keyword) + '&page=' + page);
      var items = parseCards(html);
      return { items: items, page: page, hasMore: hasMore(page, items.length) };
    },

    // 详情。元数据基本全在 OG meta 里，比抠 DOM 稳。
    // og:title 带 " - Hanime1.me" 后缀，og:description 是「标题 - 简介」，
    // twitter:description 是「标题 - 标签串 - Hanime1」，三条都要剥。
    async detail(ctx, args) {
      var ref = splitRef(args && args.ref);
      var id = ref.id;
      if (!id) throw new Error('hanime1 详情缺少 ref（影片 id）');
      if (!/^\d+$/.test(id)) throw new Error('hanime1 详情 ref 不是数字 id：' + id);

      var html = await fetchHtml(ctx, HOST + '/watch?v=' + enc(id));

      var ogTitle = meta(html, 'property', 'og:title');
      var title = stripSuffix(ogTitle, ' - Hanime1.me');
      if (!title) title = stripTags(text(between(html, '<title>', '</title>')));
      if (!title) {
        throw new Error('详情页里没找到标题（id=' + id + '）。可能：片子已下架、或站点改版。');
      }

      var duration = meta(html, 'property', 'og:video:duration');
      var tagsRaw = meta(html, 'name', 'twitter:description');

      // 单集站：一集，标题就是片名。界面上不会出现分集列表，但 episodes
      // 不能缺 —— 前端拿它决定「点播放走哪条路」。
      var episodes = [{
        no: 1,
        title: title,
        line: 'default',
        ref: 'ep1',
        episodeNumber: 1,
        id: 'ep1',
        duration: duration
      }];

      var out = {
        id: id,
        title: title,
        cover: meta(html, 'property', 'og:image'),
        description: stripPrefix(meta(html, 'property', 'og:description'), title + ' - '),
        duration: duration,
        score: '',
        category: String((args && args.slug) || ''),
        tags: parseTags(tagsRaw, title),
        episodes: episodes,
        episodeCount: 1,
        lines: [{ line: 'default', name: '默认线路' }],
        related: parseRelated(html, id)
      };
      return out;
    },

    // 播放地址。**每次实时抓详情页，零缓存** —— 签名约 12 小时过期，
    // 改一位就 403，缓存等于「今天能看的片，明天点开是 403」。
    // 三档全进 alts 让前端能切画质，url 按 ref 里的画质挑。
    async play(ctx, args) {
      var ref = splitRef(args && args.ref);
      var id = ref.id;
      if (!id) throw new Error('hanime1 播放缺少 ref（影片 id）');
      if (!/^\d+$/.test(id)) throw new Error('hanime1 播放 ref 不是数字 id：' + id);

      var html = await fetchHtml(ctx, HOST + '/watch?v=' + enc(id));
      var sources = parseSources(html);
      if (sources.length === 0) {
        throw new Error('这个页面里没有可播的 <source>（id=' + id + '）。'
          + '可能：片子已下架、或者站点改了播放器结构。');
      }

      // 画质优先级：ref 里的 > args.ep > 站点默认 720。
      var want = qualityFrom(ref.ep) || qualityFrom(args && args.ep) || 720;
      var alts = [];
      var picked = '';
      for (var i = 0; i < sources.length; i++) {
        var s = sources[i];
        if (!s.url) continue;
        alts.push({ line: String(s.quality) + 'p', url: s.url });
        if (s.quality === want) picked = s.url;
      }
      // 精确档位没有（比如站点给了 1080+720+576）退到最接近的
      if (!picked) picked = closest(sources, want);

      return {
        url: picked,
        alts: alts,
        episodeNumber: 1
      };
    }
  };
})();
