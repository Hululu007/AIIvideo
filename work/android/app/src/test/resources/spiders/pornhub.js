// Pornhub（cn.pornhub.com）—— spider 版。
//
// ── 形态：不是盾后站，服务端渲染 ──────────────────────────────────────
// 裸 curl（带安卓 Chrome 头）直接 200，首页约 1.0MB。所以这个源**不声明 shield**，
// 也不需要真浏览器内核 —— 纯字符串 + 正则解析，跟 yhdm 那一类走同一条路。
// 顺手试过它的 /api/v1/* 系列：全部 404（回的是「没发现页面」那张 HTML），
// 说明公开面没有 JSON 接口，只能解析 HTML。
//
// ── 页面结构（2026-10-19 实测）───────────────────────────────────────
// 列表（/video、/video?c=<分类>、/video/search?search=<词>）三处卡片结构基本一致，
// 但**搜索页多套了一层**：时长那个 span 的 class 是 `bgEffect time` 而不是 `time`，
// 标题的 <a> 多一个 `thumbnailTitle`。所以下面所有正则都写成「class 里含这个词」，
// 不写死整串 —— 按整串匹配的话搜索页会一张卡都解析不出来，而且看起来像「搜索没结果」。
//
// 一张卡：
//   <li ... data-video-id="492669475" data-video-vkey="6acb28161c796">
//     <a href="/view_video.php?viewkey=6acb28161c796" data-poster="https://pix-fl.phncdn.com/...">
//       <img class="videoThumb" src="https://pix-fl.phncdn.com/...">
//       <div class="duration ..."><span class="time">15:10</span></div>
//     </a>
//     <div class="title"><a href="...">标题</a></div>
//     <div class="videoViews">1.9M</div>     ← 只有搜索页有
//
// **卡片 id 取 data-video-vkey**，不是 data-video-id：详情页和播放都要
// `/view_video.php?viewkey=<vkey>`，用数字 id 还得再查一次映射。
//
// ── 封面 ────────────────────────────────────────────────────────────
// 封面地址是**带签名的临时地址**（`?hdnea=st=…~exp=…~hmac=…`，约 24 小时），
// 且主机在 `pix-fl` / `pix-cdn77` / `ei` 等几个图床之间轮换。
// `cover.allowedHosts` 是**精确匹配**（SiteProfile.coverUrl 逐条 equals），
// 所以配置里把观察到的图床全列了；漏一个的表现是那张卡灰块。
//
// ── 播放：详情页里的 flashvars ────────────────────────────────────────
// 详情页有一段 `var flashvars_<数字> = {…}`，是个**合法 JSON**（实测 16 条视频全过），
// 里面有：
//   video_title / video_duration（秒）/ image_url /
//   mediaDefinitions: [{quality:"1080", format:"hls", videoUrl:"…master.m3u8?…"}, …]
// 四档 HLS：1080 / 720 / 480 / 240，地址在 `em-h` / `hm-h` / `im-h` / `km-h`.phncdn.com
// 之间轮换（`XX-h.phncdn.com` 这个形状）。分片和子列表都是**相对路径**，
// 跟 master 同目录同主机，所以 VlcRelay 那套「相对路径改写」正好用得上。
//
// mediaDefinitions 里还有一条 `format:"mp4"`、`quality:[]` 的项，地址是
// `https://cn.pornhub.com/video/get_media?s=<base64>`。**那条别用**：实测直接 GET
// 回来是 `[]`（它要配合会话/一次性 token 才给东西）。HLS 那四档是现成可播的。
//
// ── 分页 ────────────────────────────────────────────────────────────
// `?page=N`，从 1 开始。判「还有下一页」只看页面里有没有 `rel="next"` ——
// 比数卡片可靠：首页 31 张、翻页 43 张、搜索页 29 张，张数不是固定的。
// 而越界页（page=9999）回 **HTTP 404**，net 那边直接抛错，所以 hasMore 必须判准。
(function () {
  var BASE = 'https://cn.pornhub.com';

  /** 卡片切片窗口的上限。正常一张卡 3~4KB，留 8KB 足够且不会串到下一张。 */
  var CARD_LIMIT = 8000;

  // ── 文本工具 ────────────────────────────────────────────────────────

  function decodeEntities(s) {
    return String(s == null ? '' : s)
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
      .replace(/&#0*39;/g, "'")
      .replace(/&#x27;/gi, "'")
      .replace(/&apos;/g, "'")
      .replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, '&');
  }

  function stripTags(s) {
    return String(s == null ? '' : s).replace(/<[^>]*>/g, '');
  }

  /** 标题里常带换行和连续空格，压成一个空格。 */
  function clean(s) {
    return String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
  }

  function enc(s) {
    return encodeURIComponent(String(s == null ? '' : s));
  }

  // ── 取页面 ──────────────────────────────────────────────────────────

  /**
   * 打一页 HTML。
   *
   * 这里不做「拿到挑战页」的判断：这个站没盾，真出问题多半是 404（越界翻页）或
   * 出口被掐，两种都由 AiiHttp 抛出来，带 URL 一起报上去就够定位了。
   */
  async function fetchHtml(ctx, url) {
    var res;
    try {
      res = await ctx.net({ url: url });
    } catch (e) {
      throw new Error('pornhub 请求失败 ' + url + '：' + ((e && e.message) || e));
    }
    if (!res || res.ok !== true) {
      throw new Error('pornhub 请求失败 ' + url + '：' + ((res && res.error) || '没有响应'));
    }
    var html = String(res.body == null ? '' : res.body);
    if (html.length === 0) throw new Error('pornhub 返回空页面：' + url);
    return html;
  }

  // ── 列表解析 ────────────────────────────────────────────────────────

  var RE_TITLE = /<div class="title">\s*<a[^>]*>([\s\S]*?)<\/a>/;
  // 搜索页是 `bgEffect time`，列表页是 `time`：只认 class 里含 time 这个词。
  var RE_TIME = /<span class="[^"]*\btime\b[^"]*">([^<]+)<\/span>/;
  var RE_POSTER = /data-poster="([^"]+)"/;
  var RE_IMG = /<img[^>]*class="[^"]*videoThumb[^"]*"[^>]*src="([^"]+)"/;
  var RE_VIEWS = /<div class="videoViews">(?:<i[^>]*><\/i>)?\s*([^<]+)<\/div>/;

  /**
   * 把一页 HTML 切成卡片。
   *
   * 做法是 `split('data-video-vkey="')`：每段开头就是本卡的 vkey，之后是本卡的
   * 剩余标记。切片右边界取本卡自己的 `</li>` —— 不取的话，某张卡缺标题时正则
   * 会一路吃到下一张卡的标题，卡片标题看起来「串位」而字节数一切正常。
   */
  function parseCards(html) {
    var out = [];
    var parts = String(html || '').split('data-video-vkey="');
    for (var i = 1; i < parts.length; i++) {
      var seg = parts[i];
      var q = seg.indexOf('"');
      if (q <= 0) continue;
      var vkey = seg.slice(0, q);
      if (!vkey) continue;

      var rest = seg.slice(q + 1);
      var close = rest.indexOf('</li>');
      var blk = close > 0 ? rest.slice(0, close) : rest.slice(0, CARD_LIMIT);

      var tm = RE_TITLE.exec(blk);
      var tt = RE_TIME.exec(blk);
      var po = RE_POSTER.exec(blk);
      if (!tm || !tt || !po) continue;

      var cover = decodeEntities(po[1]);
      if (!cover) {
        var im = RE_IMG.exec(blk);
        cover = im ? decodeEntities(im[1]) : '';
      }
      var vw = RE_VIEWS.exec(blk);

      out.push({
        id: vkey,
        title: clean(decodeEntities(stripTags(tm[1]))),
        cover: cover,
        duration: clean(tt[1]),
        badge: clean(tt[1]),
        score: vw ? clean(vw[1]) : '',
        tags: []
      });
    }
    return out;
  }

  /**
   * 有没有下一页：只认 `rel="next"`。
   *
   * 数卡片不行 —— 首页 31 张、翻页 43 张、搜索 29 张、越界 404 页里还有 25 张
   * 推荐卡。`rel="next"` 是站点自己渲的分页标记，比任何启发式都准。
   */
  function hasNext(html) {
    return /rel="next"/.test(String(html || ''));
  }

  function listResult(html, page) {
    var items = parseCards(html);
    return { items: items, page: page, hasMore: hasNext(html) };
  }

  // ── 详情 / 播放 ─────────────────────────────────────────────────────

  /**
   * 从详情页里抠出 `flashvars_<数字>` 那个对象并解析。
   *
   * 括号配平是在字符串里做，没法跳过引号内的花括号 —— 实测这个对象是纯 JSON、
   * 标题/描述里的花括号会被转义成 `\u007b`，所以不会把配平带偏。真遇到解析失败，
   * 下面还有一条「按字段正则硬抠」的兜底，不会整条视频打不开。
   */
  function flashvarsOf(html) {
    var m = /var\s+flashvars_\d+\s*=\s*\{/.exec(html);
    if (!m) return null;
    var start = m.index + m[0].length - 1;
    var depth = 0;
    var end = -1;
    for (var i = start; i < html.length; i++) {
      var c = html.charAt(i);
      if (c === '{') depth++;
      else if (c === '}') {
        depth--;
        if (depth === 0) { end = i + 1; break; }
      }
    }
    if (end < 0) return null;
    var raw = html.slice(start, end);
    try {
      return JSON.parse(raw);
    } catch (e) {
      return { __raw: raw };
    }
  }

  /** 兜底：flashvars 不是合法 JSON 时按字段抠。 */
  function fieldOf(html, key) {
    var re = new RegExp('"' + key + '"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"');
    var m = re.exec(html);
    if (!m) return '';
    try {
      return JSON.parse('"' + m[1] + '"');
    } catch (e) {
      return m[1];
    }
  }

  function secondsToClock(sec) {
    var s = Number(sec);
    if (!isFinite(s) || s <= 0) return '';
    s = Math.round(s);
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    var ss = s % 60;
    var pad = function (x) { return x < 10 ? '0' + x : String(x); };
    return h > 0 ? (h + ':' + pad(m) + ':' + pad(ss)) : (m + ':' + pad(ss));
  }

  /**
   * 清单 → 线路。只要 HLS 那四档。
   *
   * `format:"mp4"` 那条 quality 是空数组、地址是站点自己的 /video/get_media，
   * 实测回来是 `[]`（要一次性 token），塞给播放器只会报格式不支持。
   */
  function altsOf(defs) {
    var rows = [];
    for (var i = 0; i < (defs || []).length; i++) {
      var one = defs[i] || {};
      if (String(one.format || '').toLowerCase() !== 'hls') continue;
      var url = String(one.videoUrl || '').trim();
      if (!/^https:\/\//.test(url)) continue;
      var q = String(one.quality == null ? '' : one.quality);
      if (!q) q = String(one.height || '');
      var n = parseInt(q, 10);
      if (!isFinite(n)) n = 0;
      rows.push({ q: q, n: n, url: url });
    }
    rows.sort(function (a, b) { return b.n - a.n; });

    var seen = {};
    var out = [];
    for (var j = 0; j < rows.length; j++) {
      if (seen[rows[j].q]) continue;
      seen[rows[j].q] = true;
      out.push({
        line: rows[j].q || ('source' + (j + 1)),
        name: rows[j].q ? (rows[j].q + 'P') : ('线路 ' + (j + 1)),
        url: rows[j].url
      });
    }
    return out;
  }

  /** ref 可能是 `<vkey>` 或 `<vkey>:ep1`：按最后一个冒号切。 */
  function idOf(ref) {
    var s = String(ref == null ? '' : ref).trim();
    var cut = s.lastIndexOf(':');
    return cut > 0 ? s.slice(0, cut) : s;
  }

  // ── 能力 ────────────────────────────────────────────────────────────

  __spiderSite = {
    id: 'pornhub',
    name: 'Pornhub',
    version: 1,
    requires: '>=1',
    // 只有站点本体：封面走 App 的封面通路（不走 net），播放地址是**交出去**的
    // （不由源自己拉），所以 em-h/hm-h 这些 CDN 不用列在这里。
    hosts: ['cn.pornhub.com'],

    /** 首页 = 最新视频。 */
    async home(ctx) {
      var html = await fetchHtml(ctx, BASE + '/video');
      var out = listResult(html, 1);
      if (!out.items.length) {
        throw new Error('pornhub 首页没解析到卡片（' + html.length + ' 字节）'
          + ' —— 多半是站点改版，卡片的 data-video-vkey / div.title 结构变了');
      }
      return out;
    },

    /**
     * 分类。slug 就是站点自己的分类 id（`/video?c=<id>`）。
     * 配置里 `web.categories` 的 key 用数字 id，这样加一个分类只改配置。
     */
    async category(ctx, args) {
      args = args || {};
      var slug = String(args.slug || '').trim();
      var page = Math.max(1, Number(args.page) || 1);
      // `new` 是个约定 slug：意为「不带分类的全站最新」，配置里给它一条名字就能
      // 当成类型页的一个胶囊，不用另开能力。
      var all = !slug || slug === 'new' || slug === 'top';
      var url = BASE + '/video' + (all ? ('?page=' + page) : ('?c=' + enc(slug) + '&page=' + page));
      var html = await fetchHtml(ctx, url);
      var out = listResult(html, page);
      if (!out.items.length) {
        throw new Error('pornhub 分类页没解析到卡片（分类 ' + slug + '，第 ' + page
          + ' 页，' + html.length + ' 字节）');
      }
      return out;
    },

    /**
     * 搜索。`/video/search?search=<词>`。
     *
     * 关键词**必须 encodeURIComponent**：实测中文裸传会被站点回 HTTP 400。
     */
    async search(ctx, args) {
      args = args || {};
      var kw = String(args.keyword || '').trim();
      var page = Math.max(1, Number(args.page) || 1);
      if (!kw) return { items: [], page: page, hasMore: false };
      var html = await fetchHtml(ctx,
        BASE + '/video/search?search=' + enc(kw) + '&page=' + page);
      return listResult(html, page);
    },

    /** 详情。一条视频就是一集；时长放在选集格子的说明里。 */
    async detail(ctx, args) {
      args = args || {};
      var vkey = idOf(args.ref || args.id);
      if (!vkey) throw new Error('pornhub 详情缺少 viewkey');

      var html = await fetchHtml(ctx, BASE + '/view_video.php?viewkey=' + enc(vkey));
      var fv = flashvarsOf(html) || {};

      var title = String(fv.video_title || fieldOf(html, 'video_title') || '');
      if (!title) {
        // 下架/私有视频仍然回 200，但页面里没有 flashvars —— 这条得说清楚，
        // 否则用户看到的是「详情页空白」。
        throw new Error('pornhub 详情没解析到标题（viewkey=' + vkey + '，'
          + html.length + ' 字节）—— 视频可能已下架、设为私有，或者需要登录');
      }
      var dur = secondsToClock(fv.video_duration || fieldOf(html, 'video_duration'));
      var cover = String(fv.image_url || fieldOf(html, 'image_url') || '');

      var notes = [];
      if (dur) notes.push('时长 ' + dur);

      return {
        id: vkey,
        title: title,
        cover: cover,
        description: '',
        tags: [],
        episodeCount: 1,
        episodes: [{ no: 1, ref: vkey, title: notes.join(' · ') || '正片' }],
        lines: [{ line: 'default', name: '默认线路' }]
      };
    },

    /**
     * 播放：从详情页的 flashvars.mediaDefinitions 里取 HLS 四档。
     *
     * 只打一次请求（详情页本来就带播放清单），不用第二跳。
     */
    async play(ctx, args) {
      args = args || {};
      var vkey = idOf(args.ref || args.id);
      if (!vkey) throw new Error('pornhub 播放缺少 viewkey');

      var html = await fetchHtml(ctx, BASE + '/view_video.php?viewkey=' + enc(vkey));
      var fv = flashvarsOf(html) || {};
      var alts = altsOf(fv.mediaDefinitions);
      if (!alts.length) {
        return {
          reason: 'no-source',
          note: '这一条的播放清单是空的（视频可能刚上传还在转码，或者只在会员区提供）'
        };
      }

      return {
        url: alts[0].url,
        source: alts[0].url,
        alts: alts,
        episodeNumber: Number(args.ep) || 1,
        title: String(fv.video_title || '')
      };
    }
  };
})();
