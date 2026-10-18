// 每日大赛（mrds66.com）—— 单站 HTML 型 spider 源。
//
// 语义照抄 Mrds66Adapter.java（parseCards / cardTitle / description / playerConfig /
// videoUrl / pagination / totalPages / pageUrl），不重新发明解析。
// 只用字符串 + 正则，**不许用 DOM**。
//
// ── 站点形状（2026-10-18 实测，全部裸请求可拉，无 Cookie 无鉴权）──────
//   GET /                              首页，45 个 <article>（其中 15 个是广告）
//   GET /page/N/                       首页第 N 页，30 条
//   GET /category/{slug}/              分类第 1 页，30 条
//   GET /category/{slug}/N/            分类第 N 页
//   GET /search/{词}/                  搜索第 1 页
//   GET /search/{词}/N/                搜索第 N 页
//   GET /tag/{词}/                     标签第 1 页
//   GET /archives/{id}/                详情 + 播放地址
//
// ── 六个坑，每条都对应一次实测事故 ───────────────────────────────────
//
// ① **第 1 页不带数字。** `/category/mrds/1/` 回 301（跳到 `/category/mrds/`），
//    `/page/1/` 也是 301。所以拼 URL 时 page==1 要走短形式，别硬拼数字。
//    301 本身 OkHttp 会跟，但多一跳、而且某些中间层会把 301 变成 200 + 空页。
//
// ② **越界页回 404，不是 200 + 空列表。** 所以「翻到底」的判断不能靠状态码兜，
//    要读页面里的 `<span class="page-info">1/1889</span>`。
//
// ③ **搜索无结果回 302 到 `/search-empty/`。** 跟过去是一张卡都没有的提示页。
//    拿到的是空数组，前端画「没搜到」，这是对的，别当异常抛。
//
// ④ **广告位和会员空卡必须丢。** 首页 45 个 article 里 15 个是
//    `<article class="ad-item">`；另有几张只有封面、h2 整段不存在
//    （那是纯图文帖，点进去没有播放器）。两类都丢，否则首页出现一堆
//    点进去是死页的空白格子。
//
// ⑤ **封面在 `loadBannerDirect('…')` 里，而且有双斜杠。**
//    这是个懒加载主题，`<div class="blog-background"></div>` 是空的 ——
//    按 `<img src>` 找会一张都找不到。地址实测见过
//    `https://pic.jtzatly.cn//upload_01/…`，要归一化（封面缓存按 URL 做 key，
//    双斜杠会让同一张图存两份）。
//
// ⑥ **UA 必须伪装。** CloudFront 加了条规则，`okhttp/*` 一律 403，而且
//    **只挡部分路径**：`/` 是 200（探针因此 NeverError），但
//    `/category/…`、`/archives/…`、`/tag/…` 全 403。首页 200 是最坏的那种
//    情况 —— 探针过、App 看起来是好的，点进任何一个分类才炸。
//
// ── 播放：签名在页面里，不用算，但不能缓存 ────────────────────────────
//
// 详情页的播放器配置长这样（**单引号属性包着一段 JSON**）：
//
//   <div class="dplayer" data-video_id="196475001" data-config='{"live":false,…,
//     "video":{"url":"https://hls.tpbbwcc.cn/…/x.m3u8?auth_key=…","type":"hls"},
//     "video_h265":{"url":"…"}}'>
//
// auth_key 是 `{过期时间戳}-{随机段}-0-{md5}`，实测四种改法全 400。
// ★ 所以 play 每次实时抓详情页，**零缓存**；也正因如此它不读 detail 的缓存。
//
// 两档都进 alts，默认用 H.264 —— 拿 H.265 当默认在老设备上表现是
// 「有画面没声音」或者直接黑屏，那比多耗点流量糟得多。
(function () {
  // 站点根。Java 那边从 profile.origin 取（用户填的地址），spider 里是常量。
  // 换镜像时改这一行 + hosts。
  var BASE = 'https://www.mrds66.com';

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

  function text(s) {
    return unescape(stripHtml(s)).replace(/\s+/g, ' ').trim();
  }

  // HtmlKit.normalizeUrl：`https://pic.jtzatly.cn//upload_01/…` → `…/upload_01/…`。
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

  // HtmlKit.enc。搜索词和标签词都是中文，编错了就查不到。
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

  // HtmlKit.percentDecode：只解 %XX，**不动 `+`**（标签词里「C++」不是没有）。
  // 中文必须按字节攒再一次性 UTF-8 解，否则解出「å]ç」。
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

  // 手写 UTF-8 解码（TextDecoder 在离线沙箱里没有）。
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

  // ── 请求 ─────────────────────────────────────────────────────────
  //
  // 见文件头 ⑥：`okhttp/*` 被 CloudFront 403（只挡部分路径），所以 UA 必须伪装。
  var HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36'
      + ' (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8'
  };

  async function fetchHtml(ctx, url) {
    var res;
    try {
      res = await ctx.call('net', { url: url, headers: HEADERS });
    } catch (e) {
      throw new Error('mrds66 请求失败 ' + url + '：' + ((e && e.message) || e)
        + '（可能：被墙、或域名换了）');
    }
    if (!res || res.ok !== true) {
      var why = String((res && res.error) || '没有响应');
      if (/403/.test(why)) {
        throw new Error('mrds66 返回 403（' + url + '）：这个站用 CloudFront 的规则'
          + '挡非浏览器 UA，部分路径只对 okhttp 那一类返回 403。'
          + '可能：net 的 User-Agent 被改掉了。');
      }
      if (/404/.test(why)) {
        // 这个站对三类 404 要分开说：越界页、不存在的分类/词、以及删掉的文章。
        // 混成一句会把「文章被删了」说成「页码超范围」，排查方向直接跑偏。
        if (url.indexOf('/archives/') >= 0) {
          throw new Error('mrds66 文章页 404（' + url + '）。'
            + '可能：这篇文章已被删除、或 id 不对。');
        }
        throw new Error('mrds66 返回 404（' + url + '）：这个站越界页回的是 404 而不是空列表。'
          + '可能：页码超出范围、或这个词/分类不存在。');
      }
      throw new Error('mrds66 请求失败 ' + url + '：' + why);
    }
    var body = String(res.body == null ? '' : res.body);
    if (body.length === 0) {
      throw new Error('mrds66 拿到空响应（' + url + '）。可能：站点被墙、或这一页已经没有内容。');
    }
    return body;
  }

  // ── 卡片解析（对齐 Mrds66Adapter.parseCards）──────────────────────

  var ARTICLE_RE = /<article\b[\s\S]*?<\/article>/gi;
  var ARCHIVE_ID_RE = /\/archives\/(\d+)\//;
  var CARD_TITLE_RE = /<h2[^>]*class=["'][^"']*post-card-title[^"']*["'][^>]*>([\s\S]*?)<\/h2>/i;
  // 标题里那段角标：`<div class="wrap"><span class="wraps">热搜 HOT</span></div>`
  var WRAP_BADGE_RE = /<div[^>]*class=["'][^"']*\bwrap\b[^"']*["'][^>]*>[\s\S]*?<\/div>/gi;
  // `loadBannerDirect('https://pic.jtzatly.cn/…'`
  var BANNER_RE = /loadBannerDirect\(\s*["']([^"']+)["']/i;
  var DATE_RE = /itemprop=["']datePublished["'][^>]*content=["']([^"']+)["']/i;
  var CARD_CATE_RE = /<div[^>]*class=["'][^"']*post-card-info[^"']*["'][^>]*>([\s\S]*?)<\/div>/i;

  // 标题文本和角标在同一个 h2 里，所以取完文本要把角标那段切掉 ——
  // 不切的话首页每张卡的标题后面都跟着「热搜 HOT」。
  function cardTitle(chunk) {
    var m = CARD_TITLE_RE.exec(chunk);
    if (!m) return '';
    var inner = m[1].replace(WRAP_BADGE_RE, ' ');
    return unescape(stripHtml(inner)).replace(/\s+/g, ' ').trim();
  }

  function cardCover(chunk) {
    var m = BANNER_RE.exec(chunk);
    return m ? normalizeUrl(m[1]) : '';
  }

  function dateOf(chunk) {
    var m = DATE_RE.exec(chunk);
    if (!m) return '';
    var s = m[1];
    // 只留日期部分：卡上那个角标位置放得下一行日期，放不下 ISO 时间戳
    var t = s.indexOf('T');
    return t > 0 ? s.substring(0, t) : s;
  }

  function cardCategory(chunk) {
    var m = CARD_CATE_RE.exec(chunk);
    if (!m) return '';
    return unescape(stripHtml(m[1])).replace(/\s+/g, ' ').trim();
  }

  function parseCards(html) {
    var out = [];
    var seen = {};
    ARTICLE_RE.lastIndex = 0;
    var m;
    while ((m = ARTICLE_RE.exec(html)) !== null) {
      var chunk = m[0];

      // 广告位：`<article class="ad-item">`。判据用**开头 80 字符**，
      // 别在整块里找 "ad-item" —— 正文里也可能出现这个词。
      var head = chunk.length > 80 ? chunk.substring(0, 80) : chunk;
      if (head.indexOf('ad-item') >= 0) continue;

      var idm = ARCHIVE_ID_RE.exec(chunk);
      if (!idm) continue;
      var id = idm[1];
      if (!id) continue;

      // 标题为空 = 会员卡片（只有封面，h2 整段不存在），没有播放器。见坑 ④。
      var title = cardTitle(chunk);
      if (!title) continue;

      if (seen[id]) continue;
      seen[id] = 1;

      var card = { id: id, title: title, cover: cardCover(chunk) };
      var date = dateOf(chunk);
      if (date) card.badge = date;
      var cate = cardCategory(chunk);
      if (cate) card.category = cate;
      out.push(card);
    }
    return out;
  }

  // ── 详情解析（对齐 Mrds66Adapter.detail 里那几段）────────────────────

  var H1_RE = /<h1[^>]*class=["'][^"']*post-title[^"']*["'][^>]*>([\s\S]*?)<\/h1>/i;
  var CATEGORY_LINK_RE = /href=["']\/category\/([a-z0-9_-]+)\//i;
  var TAG_LINK_RE = /href=["']\/tag\/([^\/"']+)\//gi;
  // 上一篇/下一篇：`<a href="/archives/196424/" title="…">…</a>`
  var NEAR_RE = /<a\s+href=["']\/archives\/(\d+)\/["']\s+title=["']([^"']*)["']/gi;

  // `<meta itemprop="headline" content="…">`，属性顺序不固定，所以先抓整个
  // meta 标签、再在里面找 content。
  function metaContent(html, prop) {
    var tagRe = new RegExp('<meta[^>]*itemprop=["\'][^"\']*' + prop + '[^"\']*["\'][^>]*>', 'i');
    var tag = tagRe.exec(html);
    if (!tag) return '';
    var c = /content=["']([^"']*)["']/i.exec(tag[0]);
    return c ? unescape(c[1]).trim() : '';
  }

  function h1Title(html) {
    var m = H1_RE.exec(html);
    if (!m) return '';
    return unescape(stripHtml(m[1])).replace(/\s+/g, ' ').trim();
  }

  // 正文摘要。`.post-content` 里播放器之前那几段。
  //
  // 为什么要切到播放器为止：播放器后面还有「关键词：#…」和一张分类导航表格，
  // 那些不是简介，塞进详情页会让简介区变成一堆链接。
  //
  // 还要剥 `<a>`：正文开头挂着一长串「在线约炮 / 春药高潮 / 棋牌电子 …」
  // 的广告友链。只剥标签不剥链接的话，简介第一句就是广告。
  //
  // ★ 起点必须从标签的 `<` 开始切，不是从 `class=` 开始 —— 否则开头那半个
  //   标签（`class="post-content" itemprop="articleBody">`）会留在正文里，
  //   详情页第一行就是这串属性。Java 那边为这个单独留了一条用例。
  var SCRIPT_RE = /<script\b[\s\S]*?<\/script>/gi;
  var STYLE_RE = /<style\b[\s\S]*?<\/style>/gi;
  var COMMENT_RE = /<!--[\s\S]*?-->/g;
  var LINK_RE = /<a\b[\s\S]*?<\/a>/gi;

  function description(html) {
    var at = html.indexOf('class="post-content');
    if (at < 0) return '';
    var lt = html.lastIndexOf('<', at);
    var start = lt >= 0 ? lt : at;
    var player = html.indexOf('<div class="dplayer"', start);
    var end = player > start ? player : Math.min(html.length, start + 20000);
    var seg = html.substring(start, end);

    seg = seg.replace(SCRIPT_RE, ' ');
    seg = seg.replace(STYLE_RE, ' ');
    seg = seg.replace(COMMENT_RE, ' ');
    seg = seg.replace(LINK_RE, ' ');

    var t = unescape(stripHtml(seg)).replace(/\s+/g, ' ').trim();
    // 长度上限 300 字：详情页简介区只显示几行，给全文是浪费 —— 而且正文里
    // 有大量重复的关键词堆砌，读起来像乱码。
    if (t.length > 300) t = t.substring(0, 300) + '…';
    return t;
  }

  function categoryOf(html) {
    var m = CATEGORY_LINK_RE.exec(html);
    return m ? m[1] : '';
  }

  // 详情页的标签名：`/tag/{词}/` 里的词，URL 解码后返回。
  function tagNames(html) {
    var out = [];
    var seen = {};
    TAG_LINK_RE.lastIndex = 0;
    var m;
    while ((m = TAG_LINK_RE.exec(html)) !== null && out.length < 12) {
      var name = percentDecode(m[1]);
      if (!name || seen[name]) continue;
      seen[name] = 1;
      out.push(name);
    }
    return out;
  }

  // 相关推荐：详情页尾部的「上一篇 / 下一篇」。站点没有「相关文章」区块。
  function related(html, selfId) {
    var out = [];
    var seen = {};
    NEAR_RE.lastIndex = 0;
    var m;
    while ((m = NEAR_RE.exec(html)) !== null && out.length < 2) {
      var id = m[1];
      if (!id || id === selfId) continue;
      if (seen[id]) continue;
      seen[id] = 1;
      out.push({
        id: id,
        title: unescape(stripHtml(m[2])).replace(/\s+/g, ' ').trim()
      });
    }
    return out;
  }

  // ── 播放器（对齐 Mrds66Adapter.playerConfig / videoUrl）────────────
  //
  // `data-config` 是**单引号属性包着一段 JSON**，所以不能按双引号截 ——
  // JSON 里全是双引号，按 `"` 切会当场断在第一个键上。用单引号配对切。
  var DPLAYER_RE = /<div[^>]*class=["'][^"']*\bdplayer\b[^"']*["'][^>]*data-config='([^']*)'/i;

  function playerConfig(html) {
    var m = DPLAYER_RE.exec(html);
    if (!m) return null;
    var raw = m[1];
    if (!raw) return null;
    try {
      return JSON.parse(unescape(raw));
    } catch (e) {
      return null;
    }
  }

  function videoUrl(config, key) {
    if (!config) return '';
    var v = config[key];
    if (!v || typeof v !== 'object') return '';
    var url = String(v.url == null ? '' : v.url);
    if (!url) return '';
    // 站点给的是绝对地址。万一哪天变成相对路径，补上协议头 —— 不补的话
    // 播放器会当成文件路径，表现是「点了没反应」。
    if (url.indexOf('//') === 0) return 'https:' + url;
    return url;
  }

  // ── 分页（对齐 Mrds66Adapter.pagination / totalPages / pageUrl）────
  //
  // 站点把「当前页/总页数」直接写在页面里：`<span class="page-info"> 1/1889 </span>`
  // 这是**唯一可靠的总页数来源** —— 越界页回 404（不是 200 + 空），所以不能靠
  // 「这页有没有内容」判断到底了。
  var PAGE_INFO_RE = /class=["']page-info["'][^>]*>\s*\d+\s*\/\s*(\d+)/i;

  function totalPages(html) {
    var m = PAGE_INFO_RE.exec(html);
    if (!m) return 0;
    var n = parseInt(m[1].trim(), 10);
    return isFinite(n) ? n : 0;
  }

  function pageHasMore(page, got, html) {
    if (got <= 0) return false;
    var pages = totalPages(html);
    if (pages > 0) return page < pages;
    // 读不到时给 page 这一页本身（让前端停在原地），**不猜** ——
    // 给一个假的总页数会让用户翻到 404 页。
    return false;
  }

  // 分页 URL。**第 1 页不带数字**（坑 ①）。
  function pageUrl(first, page) {
    if (page <= 1) return first;
    return first + page + '/';
  }

  // ── 分类表（照抄 Mrds66Adapter.CATEGORIES）────────────────────────
  var CATEGORIES = [
    ['mrds', '每日大赛'], ['ztds', '主题大赛'], ['rstt', '热搜吃瓜'],
    ['xazd', '校园学生'], ['blyp', '必撸大赛'], ['fctg', '反差泄密'],
    ['mhds', '网红黑料'], ['lqdp', '猎奇重口'], ['jdsj', 'AV看片'],
    ['mxwh', '明星大赛'], ['smdh', '动漫之家'], ['dypd', '影视国漫'],
    ['mtds', 'cos写真'], ['ysds', '声控ASMR'], ['czds', '寸止挑战'],
    ['hjds', '混剪PMV'], ['tgds', '原创投稿'], ['omjp', '欧美精品'],
    ['qwcs', '全网参赛'], ['aijc', 'AI剧场']
  ];

  function isKnownCategory(slug) {
    for (var i = 0; i < CATEGORIES.length; i++) {
      if (CATEGORIES[i][0] === slug) return true;
    }
    return false;
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
    id: 'mrds66',
    name: '每日大赛',
    version: 1,
    requires: '>=1',
    // 站点域 + 封面图床。封面 URL 是 spider 算出来交给前端的，但白名单里
    // 写上它，是为了将来需要在 spider 里回抓封面时不用再改 hosts。
    hosts: ['www.mrds66.com', 'mrds66.com', 'pic.jtzatly.cn'],

    // 首页。打 `/`，一次请求。
    //
    // 分区怎么来：站点首页只有一列卡片流（没有独立的「最新/热门/推荐」区块），
    // 所以平铺给 items，切 featured 是 model.js 的事。
    // **不额外请求一次分类来做精选**：这个站首页本来就有 30 条，再打一次分类
    // 只会让首屏慢一倍，换来的还是同一批内容。
    async home(ctx) {
      var html = await fetchHtml(ctx, BASE + '/');
      var items = parseCards(html);
      if (items.length === 0) {
        throw new Error('mrds66 首页没解析到卡片（可能：站点改版、被墙、'
          + '或者主题从 Mirages 换掉了）');
      }
      return { items: items, page: 1, hasMore: false };
    },

    // 分类列表。slug 是站点分类（mrds / ztds / …）。
    // 不认识的 slug 站点回 **404**（不是默认列表），所以先在本地表里挡一道 ——
    // 报清楚「这个分类不存在」比让用户看见一个 404 好。
    async category(ctx, args) {
      // 入参兼容两套名字：app.js 给 slug/cateId，离线回归（spider-check）给 ref。
      var slug = String((args && (args.slug || args.cateId || args.ref)) || '').trim();
      var page = pageOf(args);
      if (!slug) slug = 'mrds';
      if (!isKnownCategory(slug)) {
        throw new Error('mrds66 没有「' + slug + '」这个分类。'
          + '站点对它回 404，可用的是 mrds / ztds / rstt / …（共 20 个）');
      }
      var html = await fetchHtml(ctx, pageUrl(BASE + '/category/' + enc(slug) + '/', page));
      var items = parseCards(html);
      return { items: items, page: page, hasMore: pageHasMore(page, items.length, html) };
    },

    // 搜索。路由 `/search/{词}/`，第 N 页 `/search/{词}/N/`。
    // 空关键词**不发请求**：`/search//` 会跳到首页那份列表，看起来像
    // 「搜索成功了但结果全是热门」，比失败更糟。
    async search(ctx, args) {
      var keyword = String((args && (args.keyword || args.ref)) || '').trim();
      var page = pageOf(args);
      if (!keyword) {
        throw new Error('mrds66 搜索词为空，没有发请求（检查一下输入框是不是空的）');
      }
      var html = await fetchHtml(ctx, pageUrl(BASE + '/search/' + enc(keyword) + '/', page));
      // 无结果时站点 302 到 /search-empty/，跟过去是 0 张卡 —— 返回空数组，
      // 让前端画「没搜到」，不抛异常。
      var items = parseCards(html);
      return { items: items, page: page, hasMore: pageHasMore(page, items.length, html) };
    },

    // 详情。元数据在 article 的 itemprop 与 DOM 里。
    // 单集站：一个 id 就是一部完整的片，episodes 恒 1 条 —— 前端拿它决定
    // 「点播放走哪条路」。
    async detail(ctx, args) {
      var ref = splitRef(args && args.ref);
      var id = ref.id;
      if (!id) throw new Error('mrds66 详情缺少 ref（文章 id）');
      if (!/^\d+$/.test(id)) throw new Error('mrds66 详情 ref 不是数字 id：' + id);

      var html = await fetchHtml(ctx, BASE + '/archives/' + enc(id) + '/');

      var title = metaContent(html, 'headline');
      if (!title) title = h1Title(html);
      if (!title) {
        throw new Error('详情页里没找到标题（id=' + id
          + '）。可能：文章已删除、或站点改版。');
      }

      var published = metaContent(html, 'datePublished');
      var extra = {};
      if (published) extra.published = published;

      var episodes = [{
        no: 1,
        title: title,
        line: 'default',
        ref: 'ep1',
        episodeNumber: 1,
        id: 'ep1',
        duration: ''
      }];

      return {
        id: id,
        title: title,
        cover: normalizeUrl(metaContent(html, 'image')),
        description: description(html),
        duration: '',
        // 站点没有评分也没有播放量，别硬塞 —— 空着比给错的值好。
        score: '',
        category: categoryOf(html),
        tags: tagNames(html),
        extra: extra,
        episodes: episodes,
        episodeCount: 1,
        lines: [{ line: 'default', name: '原画' }],
        related: related(html, id)
      };
    },

    // 播放地址。**每次实时抓详情页，零缓存** —— auth_key 绑时间戳，
    // 缓存等于「过一会儿点开就是 400」。
    //
    // 页面给两档：video（H.264）和 video_h265。两档都进 alts，
    // 默认用 H.264 —— 拿 H.265 当默认在老设备上表现是「有画面没声音」或黑屏。
    async play(ctx, args) {
      var ref = splitRef(args && args.ref);
      var id = ref.id;
      if (!id) throw new Error('mrds66 播放缺少 ref（文章 id）');
      if (!/^\d+$/.test(id)) throw new Error('mrds66 播放 ref 不是数字 id：' + id);

      var html = await fetchHtml(ctx, BASE + '/archives/' + enc(id) + '/');
      var config = playerConfig(html);
      if (!config) {
        throw new Error('这个页面里没有播放器配置（id=' + id + '）。'
          + '可能：文章是纯图文、已下架、或站点换了播放器插件。');
      }

      var h264 = videoUrl(config, 'video');
      var h265 = videoUrl(config, 'video_h265');
      if (!h264 && !h265) {
        throw new Error('播放器配置里没有可用地址（id=' + id + '）。'
          + '可能：片子还没转码完、或站点改了配置结构。');
      }

      var alts = [];
      if (h264) alts.push({ line: '原画', url: h264 });
      if (h265) alts.push({ line: 'H.265', url: h265 });

      return {
        // 只有 H.265 时也得能播 —— 总比报错强。
        url: h264 || h265,
        alts: alts,
        episodeNumber: 1
      };
    }
  };
})();
