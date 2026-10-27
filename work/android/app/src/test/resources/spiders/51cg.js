// 51吃瓜（51cg1.com）—— spider 版。
//
// ── 形态：服务端渲染，无盾，无 JSON 接口 ───────────────────────────────
// 裸 curl 直接 200、首页 260KB 左右（`<!-- time -->` 注释是它自己的构建戳）。
// 试过 WordPress 那套（wp-json / admin-ajax）：一个都没有 —— 它不是 WP，
// 是自研的 Typecho 系主题（`/usr/themes/Mirages`、`/archives/<id>/`、`/category/<slug>/`）。
//
// ── 卡片 ────────────────────────────────────────────────────────────
// 列表（首页 / 分类 / 搜索）用同一套卡：
//   <div class="post-card" id="post-card-279391">
//     <div class="blog-background" z-bg-loader-url="https://pic.gyezbp.cn/…jpeg"></div>
//     <h2 class="post-card-title" itemprop="headline">
//       标题…
//       <div class="wrap"><span class="wraps">热搜 HOT</span></div>     ← 角标，得剥掉
//     </h2>
//     <div class="post-card-info">…<span itemprop="datePublished" content="2026-10-19T10:51:00+00:00">…</span></div>
//   </div>
//
// **封面不是 <img src>**：站点用一套声明式懒加载，把地址写在属性
// `z-bg-loader-url="URL"` 上，由 image.20260416.js 在滚动时套成背景图。
// 2026-10-27 之前这里是 `<script>loadBannerDirect('URL'…)</script>`，站点改版后
// 那个函数被去掉，老正则一次都不命中 —— 封面全空，前端只剩占位块。
//
// 标题里嵌着那个 `<div class="wrap">` 角标，直接 stripTags 会把「热搜 HOT」
// 粘到标题尾巴上。所以先把 wrap 整块删掉再剥标签。
//
// ── 分页：三种页型三套 URL ────────────────────────────────────────────
//   首页      /page/N/                （第 1 页是 /）
//   分类      /category/<slug>/N/     （第 1 页是 /category/<slug>/）
//   搜索      /search/<关键词>/N/     （第 1 页是 /search/<关键词>/）
//
// 三种页都有 `<link rel="next">`，所以 hasMore 统一按它判。
// **别用 /page/N/ 去翻分类页** —— 实测 `/category/whhl/page/2/` 回 403（WAF 拦），
// 而 `/category/whhl/2/` 正常 200。越界页（`/page/50000/`）回 404，net 直接抛错，
// 所以 hasMore 判错一次用户看到的就是错误块。
//
// ── 播放：详情页里 DPlayer 的 data-config ─────────────────────────────
// 详情页有一个 `<div … data-config='{…JSON…}'>`，是个**合法 JSON**（实测 8 条全过），
// 关键两个字段：
//   video.url       https://hls.weagdnz.cn/videos5/<md5>/<md5>.m3u8?auth_key=…
//   video_h265.url  同上，H.265 版（不是每条都有）
//
// 这个 m3u8 **不是 master**，是媒体列表，而且带 AES-128 加密：
//   #EXT-X-KEY:METHOD=AES-128,URI="https://tx.doudou520.online/…/crypt.key?auth_key=…",IV=0x…
//   #EXTINF:5.000,
//   https://tx.doudou520.online/videos5/<md5>/<md5>0.ts?auth_key=…
// 分片主机在 `tx.doudou520.online` / `mts.hhjd.mobi` / `ts.zhixunkeji.xyz` 之间轮换，
// 和 master 的 `hls.weagdnz.cn` 不是一个域 —— 两边都要列进 `http.hosts`。
//
// 播放层那边 `rewriteM3u8` 只改写非 `#` 开头的行，所以：
//   · 分片（ts 行）会被包进中继 → 走 relay，能过白名单；
//   · `#EXT-X-KEY` 的 URI 不会被改写 → VLC 自己去取 crypt.key。
// 两条路都不经过站点本身，所以这个源在播放阶段不再打 51cg1.com。
//
// ── 搜索 ────────────────────────────────────────────────────────────
// `/search/<关键词>/`，关键词要 `encodeURIComponent`。中文必须编码：
// 裸传中文时站点那套路由匹配不到，回的是首页模板。
// `?s=` 那条是老 WP 习惯，这里 301 到 `/`，别用。
(function () {
  var BASE = 'https://51cg1.com';

  /** 详情页里播放器配置的属性名。实测是单引号包裹，留双引号那条兜底。 */
  var RE_CONFIG = /data-config=(?:'([^']+)'|"([^"]+)")/;

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

  function clean(s) {
    return String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
  }

  function enc(s) {
    return encodeURIComponent(String(s == null ? '' : s));
  }

  // ── 取页面 ──────────────────────────────────────────────────────────

  async function fetchHtml(ctx, url) {
    var res;
    try {
      res = await ctx.net({ url: url });
    } catch (e) {
      throw new Error('51吃瓜 请求失败 ' + url + '：' + ((e && e.message) || e));
    }
    if (!res || res.ok !== true) {
      throw new Error('51吃瓜 请求失败 ' + url + '：' + ((res && res.error) || '没有响应'));
    }
    var html = String(res.body == null ? '' : res.body);
    if (html.length === 0) throw new Error('51吃瓜 返回空页面：' + url);
    return html;
  }

  // ── 列表解析 ────────────────────────────────────────────────────────

  var RE_CARD_ID = /<div class="post-card" id="post-card-(\d+)"/;
  // 2026-10-27 站点改版：卡片封面从 `loadBannerDirect('...')` 换成了声明式
  // 背景加载 `<div class="blog-background" z-bg-loader-url="https://...">`
  // （站点源码里那句注释：“封面改用 image.20260416.js 的 z-bg-loader-url，
  // 去掉 loadBannerDirect”）。老正则在新页面上一次都不命中 —— 症状是列表能
  // 出来、封面全是空串，前端只能显示占位块。
  //
  // 广告卡（id="ad-card-0"）夹在两张真卡之间，也落在同一个 blk 里，它的背景图
  // 是 .../uploads/default/other/xxx.gif 这种占位动图。所以逐条扫、跳过广告图；
  // 只剩广告图时返回空串 —— 宁可不给封面，也别把广告占位图当封面。
  var RE_BANNER_URL = /z-bg-loader-url="([^"]+)"/g;

  function coverInBlock(blk) {
    var re = new RegExp(RE_BANNER_URL.source, 'g');
    var m;
    while ((m = re.exec(blk)) !== null) {
      var url = decodeEntities(m[1]);
      if (url.indexOf('/uploads/default/other/') >= 0) continue;
      if (/^https?:\/\//.test(url)) return url;
    }
    return '';
  }
  var RE_TITLE = /<h2 class="post-card-title"[^>]*>([\s\S]*?)<\/h2>/;
  var RE_DATE = /itemprop="datePublished"[^>]*content="([^"]+)"/;
  var RE_WRAP = /<div class="wrap">[\s\S]*?<\/div>/;

  /**
   * 一页 HTML → 卡片数组。
   *
   * 用 `post-card-<id>` 当锚点切；每段到下一个锚点为止就是这一张卡。
   * 卡片里的 `<script>loadBannerDirect('…')</script>` 是封面唯一的来源。
   */
  function parseCards(html) {
    var out = [];
    var re = new RegExp(RE_CARD_ID.source, 'g');
    var marks = [];
    var m;
    while ((m = re.exec(html)) !== null) {
      marks.push({ id: m[1], at: m.index });
    }
    for (var i = 0; i < marks.length; i++) {
      var from = marks[i].at;
      var to = i + 1 < marks.length ? marks[i + 1].at : Math.min(html.length, from + 12000);
      var blk = html.slice(from, to);

      var tm = RE_TITLE.exec(blk);
      if (!tm) continue;
      // 先把角标那块整块删掉，再剥标签 —— 否则「热搜 HOT」会粘到标题尾巴上。
      var title = clean(decodeEntities(stripTags(tm[1].replace(RE_WRAP, ''))));
      if (!title) continue;

      var dm = RE_DATE.exec(blk);

      out.push({
        id: marks[i].id,
        title: title,
        cover: coverInBlock(blk),
        playAt: dm ? String(dm[1]).slice(0, 10) : '',
        tags: []
      });
    }
    return out;
  }

  /** 三种页型都靠这行判「还有下一页」。 */
  function hasNext(html) {
    return /rel="next"/.test(String(html || ''));
  }

  function listResult(html, page) {
    return { items: parseCards(html), page: page, hasMore: hasNext(html) };
  }

  // ── 详情 / 播放 ─────────────────────────────────────────────────────

  function configOf(html) {
    var m = RE_CONFIG.exec(html);
    if (!m) return null;
    var raw = decodeEntities(m[1] != null ? m[1] : m[2]);
    try {
      return JSON.parse(raw);
    } catch (e) {
      return null;
    }
  }

  /** 详情页封面：正文里第一张 `upload_01` 图（og:image 是站点通用占位图，没用）。 */
  function coverOf(html) {
    var m = /https:\/\/pic\.gyezbp\.cn\/upload_01\/[^"'\s)]+/.exec(html);
    return m ? m[0] : '';
  }

  function titleOf(html) {
    var m = /<h1[^>]*>([\s\S]*?)<\/h1>/.exec(html);
    if (m) {
      var t = clean(decodeEntities(stripTags(m[1])));
      if (t) return t;
    }
    var og = /<meta property="og:title" content="([^"]*)"/.exec(html);
    return og ? clean(decodeEntities(og[1])) : '';
  }

  function descOf(html) {
    var m = /<meta name="description" content="([^"]*)"/.exec(html);
    return m ? clean(decodeEntities(m[1])) : '';
  }

  /** 正文底部那行「关键词：#a #b #c」—— 当成标签用。 */
  function tagsOf(html) {
    var m = /关键词：([^<]{0,400})/.exec(html);
    if (!m) return [];
    var out = [];
    var parts = String(m[1]).split('#');
    for (var i = 0; i < parts.length; i++) {
      var t = clean(parts[i]);
      if (t) out.push(t);
    }
    return out;
  }

  /** ref 可能是 `<id>` 或 `<id>:ep1`：按最后一个冒号切。 */
  function idOf(ref) {
    var s = String(ref == null ? '' : ref).trim();
    var cut = s.lastIndexOf(':');
    return cut > 0 ? s.slice(0, cut) : s;
  }

  function pageUrl(kind, key, page) {
    if (kind === 'home') return BASE + (page <= 1 ? '/' : ('/page/' + page + '/'));
    if (kind === 'category') {
      return BASE + '/category/' + enc(key) + '/' + (page <= 1 ? '' : (page + '/'));
    }
    return BASE + '/search/' + enc(key) + '/' + (page <= 1 ? '' : (page + '/'));
  }

  // ── 能力 ────────────────────────────────────────────────────────────

  /**
   * 首页列表。
   *
   * 抽成独立函数而不是在 category 里写 `this.home(ctx)` —— 宿主调能力时是
   * `site[cap](ctx, args)`，函数是**摘下来单独调**的，`this` 绑不到 __spiderSite 上
   * （真机上 `this` 是 undefined，调用直接 TypeError）。这类错离线沙箱也会炸，
   * 但只有 slug 为空那条分支才走到，很容易漏。
   */
  async function homePage(ctx) {
    var html = await fetchHtml(ctx, pageUrl('home', '', 1));
    var out = listResult(html, 1);
    if (!out.items.length) {
      throw new Error('51吃瓜 首页没解析到卡片（' + html.length + ' 字节）'
        + ' —— 多半是主题改版，post-card / post-card-title 结构变了');
    }
    return out;
  }

  __spiderSite = {
    id: '51cg',
    name: '51吃瓜',
    version: 1,
    requires: '>=1',
    // 只有站点本体。播放地址交给播放器，分片/m3u8 不在源里请求。
    hosts: ['51cg1.com'],

    async home(ctx) {
      return homePage(ctx);
    },

    /**
     * 分类。slug 是站点的分类短名（`/category/<slug>/`），配置里 key 就用它。
     */
    async category(ctx, args) {
      args = args || {};
      var slug = String(args.slug || '').trim();
      var page = Math.max(1, Number(args.page) || 1);
      if (!slug) return homePage(ctx);
      var html = await fetchHtml(ctx, pageUrl('category', slug, page));
      var out = listResult(html, page);
      if (!out.items.length) {
        throw new Error('51吃瓜 分类页没解析到卡片（' + slug + ' 第 ' + page + ' 页，'
          + html.length + ' 字节）');
      }
      return out;
    },

    async search(ctx, args) {
      args = args || {};
      var kw = String(args.keyword || '').trim();
      var page = Math.max(1, Number(args.page) || 1);
      if (!kw) return { items: [], page: page, hasMore: false };
      var html = await fetchHtml(ctx, pageUrl('search', kw, page));
      return listResult(html, page);
    },

    async detail(ctx, args) {
      args = args || {};
      var id = idOf(args.ref || args.id);
      if (!id) throw new Error('51吃瓜 详情缺少 id');

      var html = await fetchHtml(ctx, BASE + '/archives/' + enc(id) + '/');
      var title = titleOf(html);
      if (!title) {
        throw new Error('51吃瓜 详情没解析到标题（id=' + id + '，' + html.length
          + ' 字节）—— 文章可能已删除或被限制访问');
      }
      var cfg = configOf(html);
      var hasVideo = !!(cfg && cfg.video && cfg.video.url);

      return {
        id: id,
        title: title,
        cover: coverOf(html),
        description: descOf(html),
        tags: tagsOf(html),
        episodeCount: 1,
        episodes: [{
          no: 1,
          ref: id,
          title: hasVideo ? '正片（HLS）' : '图文（无视频）'
        }],
        lines: [{ line: 'default', name: '默认线路' }]
      };
    },

    /**
     * 播放：只打详情页一次，从 data-config 里取两条 m3u8。
     *
     * 图文帖（没有 data-config 或者没有 video.url）给 reason，不硬塞坏地址 ——
     * 这个站很大一部分是纯图文爆料，报「格式不支持」会让人以为是解析坏了。
     */
    async play(ctx, args) {
      args = args || {};
      var id = idOf(args.ref || args.id);
      if (!id) throw new Error('51吃瓜 播放缺少 id');

      var html = await fetchHtml(ctx, BASE + '/archives/' + enc(id) + '/');
      var cfg = configOf(html);
      if (!cfg) {
        return { reason: 'no-source', note: '这条是图文爆料，没有视频' };
      }

      var alts = [];
      var h264 = (cfg.video && cfg.video.url) ? String(cfg.video.url) : '';
      var h265 = (cfg.video_h265 && cfg.video_h265.url) ? String(cfg.video_h265.url) : '';
      if (h264) alts.push({ line: 'h264', name: 'H.264（通用）', url: h264 });
      if (h265) alts.push({ line: 'h265', name: 'H.265（更省流量）', url: h265 });
      if (!alts.length) {
        return { reason: 'no-source', note: '这条没有解析到可播地址（可能只在图文区）' };
      }

      return {
        url: alts[0].url,
        source: alts[0].url,
        alts: alts,
        episodeNumber: Number(args.ep) || 1,
        note: 'HLS 分片走 AES-128 加密，播放器要能取到 crypt.key（这一跳直连，不经白名单）'
      };
    }
  };
})();
