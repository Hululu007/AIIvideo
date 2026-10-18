// 樱花动漫（yhdm.one）—— 阶段 1 的第一个真源。
//
// 语义照抄 YhdmAdapter.java（parseCards / detailFrom / playFrom / epOf），
// 不重新发明解析：同一个 HTML，这里和 Java 适配器算出来的字段必须一致。
// 但只用字符串 + 正则 —— 这个文件要能在没有 DOM 的沙箱里跑离线回归
// （scripts/spider-check.js 在 vm 里加载它），用了 DOMParser / document /
// window 就等于把这个源从此没有离线回归，只能靠真机点。
//
// 站点形状（2026-10-18 实测，裸请求可拉，无 CF 无 UA 检查）：
//   GET /                          首页，卡片 li 里带 /vod/{id}.html
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
    return abs(base, String(m[1] || '').trim());
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

  // ── 能力 ──────────────────────────────────────────────────────────

  function splitRef(ref) {
    var raw = String(ref == null ? '' : ref).trim();
    var i = raw.indexOf(':');
    if (i < 0) return { id: raw, ep: '' };
    return { id: raw.substring(0, i), ep: raw.substring(i + 1) };
  }

  __spiderSite = {
    id: 'yhdm',
    name: '樱花动漫',
    version: 1,
    requires: '>=1',
    hosts: ['yhdm.one'],          // net 白名单：播放页、_get_plays、封面全在这个域

    // 首页一次 /：卡片全在 <li> 里。Java 那边还按前 8 条切 featured，
    // 这里只给一个平铺的 items —— 分区是 UI 的决定，不是源的决定。
    async home(ctx) {
      var html = await fetchText(ctx, BASE + '/');
      var items = parseCards(html, BASE);
      if (items.length === 0) {
        throw new Error('yhdm 首页没解析到卡片（可能：站点改版、被墙、或拿到的是挑战页）');
      }
      return { items: items };
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
    async play(ctx, args) {
      var ref = splitRef(args && args.ref);
      var id = ref.id;
      var ep = ref.ep || String((args && args.ep) || '') || 'ep1';
      if (!id) throw new Error('yhdm 播放缺少 ref');
      if (!/^\d+$/.test(id)) throw new Error('yhdm 播放 ref 不是视频 id：' + id);

      var body = await fetchText(ctx, BASE + '/_get_plays/' + id + '/' + ep);
      var json;
      try {
        json = JSON.parse(body);
      } catch (e) {
        throw new Error('yhdm _get_plays 返回的不是 JSON（id=' + id + ' ep=' + ep + '）');
      }

      var plays = json && json.video_plays;
      if (!plays || typeof plays.length !== 'number') plays = [];
      var alts = [];
      for (var i = 0; i < plays.length; i++) {
        var one = plays[i] || {};
        var url = String(one.play_data == null ? '' : one.play_data).trim();
        if (!url) continue;
        var label = String(one.src_site == null ? '' : one.src_site).toUpperCase();
        alts.push({ line: label || ('LINE' + (i + 1)), url: url });
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
