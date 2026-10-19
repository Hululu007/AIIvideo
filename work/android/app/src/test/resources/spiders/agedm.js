// AGE动漫（api.agedm.io/v2）—— spider 版。
//
// 语义照抄 AgedmAdapter.java：home-list / catalog / search / detail 四套字段名
// 各不相同，卡片一律按「候选键表」取值；剧集从 video.playlists 按线路拆。
// 只用字符串 + 正则 + JSON.parse —— 这个文件要能在没有 DOM 的沙箱里跑离线回归
// （scripts/spider-check.js 在 vm 里加载它），用了 DOMParser / document / window
// 就等于这个源从此没有离线回归。
//
// ── 一个必须说清楚的降级：播放地址（2026-10-19 更正判断）──────────────
//
// 站点 detail 里给的不是 URL，是 `age_xxx` 密文，要过它自己的 Go/WASM 签名门
// （链路见 docs/2026-10-16-agedm-逆向.md 第四节）。
//
// 【更正】下面那句「宿主页没有 wasm 运行时/加载路径」是错的：WebView 内核自带
// WebAssembly，宿主页 CSP 的 script-src 'unsafe-eval' 就放行 wasm —— 主页面用
// 同一族子句跑着同一个 410KB 的 hxm wasm。真正的限制只是：wasm 字节（base64
// ~550KB，源上限 2MB，塞得下）还没内嵌进本文件，`net` 也还没替代 HG_HTTP。
// 把这两步做掉，play 即可直出 m3u8，零发版（路径见
// docs/2026-10-19-代理路由智能化.md §五）。在那之前，本文件的 play 维持降级。
//
// 按「不给看起来能播其实点不动的东西」这条原则，play 的行为是：
//   · alts 带上该集**所有线路**的密文，url 前缀 `agedm-wasm:`（与 Java 侧
//     AgedmAdapter.PLAY_MARKER 一致），前端那个 wasm 解析器认这个前缀；
//   · url 是**空串** —— 不把密文当播放地址交出去。密文喂给播放器只会
//     报「格式不支持」，用户看不出真正原因；
//   · 返回值同时带 `reason` / `note` 两个字段写清楚这件事。
//
// 也就是说：密文拿得到、线路拿得到，直链这一环 spider 版缺失，这是明确降级，
// 不是没实现。
//
// ── 接口形状（2026-10-16 实测，全部裸请求可拉）────────────────────────
//   GET /v2/home-list        → {latest[12], recommend[12], week_list}
//   GET /v2/catalog?…        → {total, videos[]}   没有页码数，得自己算
//   GET /v2/search?query=&page= → {code, message, data:{videos, total, totalPage, …}}
//                              ↑ 只有这一个带 data 信封
//   GET /v2/detail/{id}      → {video{…, playlists}, series, similar,
//                               player_label_arr, player_vip, player_jx}
(function () {
  var BASE = 'https://api.agedm.io';

  /** 每页条数。站点支持到 100（实测），默认 24 够首页和列表用。 */
  var PAGE_SIZE = 24;

  /** Java 侧认这个前缀，拿后面的密文去跑 wasm 解析。别改。 */
  var PLAY_MARKER = 'agedm-wasm:';

  // ── 取数据 ────────────────────────────────────────────────────────

  // 失败必须抛可读错误，不能返回空列表：空列表让用户以为「这站没内容」，
  // 和「接口今天挂了」是两件完全不同的事。
  async function fetchJson(ctx, url) {
    var res;
    try {
      res = await ctx.call('net', { url: url, headers: { Accept: 'application/json' } });
    } catch (e) {
      throw new Error('AGE动漫 请求失败 ' + url + '：' + ((e && e.message) || e));
    }
    if (!res || res.ok !== true) {
      throw new Error('AGE动漫 请求失败 ' + url + '：' + ((res && res.error) || '没有响应'));
    }
    var body = String(res.body == null ? '' : res.body);
    if (body.length === 0) throw new Error('AGE动漫 返回空响应：' + url);
    try {
      return JSON.parse(body);
    } catch (e) {
      throw new Error('AGE动漫 返回的不是 JSON：' + url + '（可能接口改版或拿到的是 SPA 兜底页）');
    }
  }

  function enc(s) {
    return encodeURIComponent(String(s == null ? '' : s));
  }

  // ── 候选键取值（对齐 AgedmAdapter.first / str）────────────────────

  // 空串也算「没有」—— 站点有些字段是 "" 而不是缺省，取到空串就继续看下一个键。
  function first(x, keys) {
    if (!x) return null;
    for (var i = 0; i < keys.length; i++) {
      var v = x[keys[i]];
      if (v === undefined || v === null) continue;
      if (typeof v === 'string' && v.length === 0) continue;
      return v;
    }
    return null;
  }

  function str(x, keys) {
    var v = first(x, keys);
    return v === null ? '' : String(v);
  }

  // ── 映射（对齐 AgedmAdapter.toVideos）─────────────────────────────
  //
  // ⚠️ 首页和目录是**两套字段命名**（实测 2026-10-16）：
  //   首页 /v2/home-list  {AID, Title, PicSmall, NewTitle, Href}
  //   目录 /v2/catalog    {id, name, cover, uptodate, tags_arr, intro, star, …}
  // 搜索又是第三种拼法（tagsArr 而不是 tags_arr）。所以一律按候选键取第一个有值的。

  function toCard(x, base) {
    if (!x || typeof x !== 'object') return null;
    var id = str(x, ['id', 'AID']);
    if (!id) return null;
    var title = str(x, ['name', 'Title']);
    var card = {
      id: id,
      title: title || id,
      cover: str(x, ['cover', 'PicSmall', 'PicUrl'])
    };
    var badge = str(x, ['uptodate', 'NewTitle']);
    if (badge) card.badge = badge;
    var cate = str(x, ['type', 'tid']);
    if (cate) card.category = cate;
    var tags = first(x, ['tags_arr', 'tagsArr', 'plot_arr']);
    if (tags && typeof tags.length === 'number') {
      var out = [];
      for (var i = 0; i < tags.length; i++) out.push(String(tags[i]));
      card.tags = out;
    }
    var intro = str(x, ['intro']);
    if (intro) card.description = intro;
    // 未评分返回 0，界面上会画成一个 0.0 的角标。不给这个键就不画。
    var score = first(x, ['star']);
    if (score !== null) {
      var s = String(score);
      if (s !== '0' && s !== '0.0') card.score = score;
    }
    return card;
  }

  function toCards(list) {
    var out = [];
    if (!list || typeof list.length !== 'number') return out;
    for (var i = 0; i < list.length; i++) {
      var card = toCard(list[i], BASE);
      if (card) out.push(card);
    }
    return out;
  }

  // ── 分页 ──────────────────────────────────────────────────────────

  function pageOf(page) {
    var n = parseInt(page, 10);
    return isNaN(n) || n < 1 ? 1 : n;
  }

  /** 站点只给 total，自己算页数（和 Java 的 pagination() 同一个式子）。 */
  function hasMoreFromTotal(total, page, size) {
    var t = parseInt(total, 10);
    if (isNaN(t) || t <= 0) return false;
    return page * size < t;
  }

  // ── 目录查询串（对齐 AgedmAdapter.catalogQuery）───────────────────
  //
  // **参数名有坑，两个**：
  //   · genre 不是「类型」而是「版本」（TV / 剧场版 / OVA）
  //   · 真正的类型叫 label，值是中文原词（搞笑 / 校园 / 奇幻…），不是代码
  // 所以「分类」在 AGE动漫 这边就是 label=<中文词>。

  function catalogQuery(label, order) {
    var sort = String(order || '');
    if (sort !== 'time' && sort !== 'hits' && sort !== 'name') sort = 'time';
    var q = 'genre=all&letter=all&year=all&season=all&status=all&resource=all&region=all'
      + '&order=' + sort;
    if (label && label !== 'all') q += '&label=' + enc(label);
    else q += '&label=all';
    return q;
  }

  // ── 详情：剧集与线路 ──────────────────────────────────────────────

  /**
   * 线路的尝试顺序：player_vip 里那串优先，剩下的按 playlists 自己的键序接后面。
   * 站点给的 player_vip 是它自己认为的好坏次序，照抄，不自己排。
   */
  function lineOrder(playlists, playerVip) {
    var out = [];
    var seen = {};
    function add(k) {
      if (!k || seen[k]) return;
      // 只收「键下是个数组」的 —— playlists 里还可能混进非线路字段。
      var v = playlists[k];
      if (!v || typeof v.length !== 'number') return;
      seen[k] = 1;
      out.push(k);
    }
    var vip = String(playerVip || '');
    if (vip) {
      var parts = vip.split(',');
      for (var i = 0; i < parts.length; i++) add(parts[i].trim());
    }
    for (var key in playlists) {
      if (!Object.prototype.hasOwnProperty.call(playlists, key)) continue;
      add(key);
    }
    return out;
  }

  function pickSource(playlists, playerVip) {
    var order = lineOrder(playlists, playerVip);
    return order.length ? order[0] : '';
  }

  /**
   * 拆剧集：站点给 `playlists{源: [[集名, 密文], …]}`，我们要平的 [{no,title,line}]。
   * 和 Java 一样只取**一条线路**做主列表，但每集带 `lines` 记下它出现在哪些线路上，
   * 播放时就不用把 playlists 再猜一遍。
   */
  function episodesOf(video, playlists, order) {
    var episodes = [];
    if (!order.length) return episodes;
    var main = playlists[order[0]] || [];
    for (var i = 0; i < main.length; i++) {
      var pair = main[i];
      if (!pair || typeof pair.length !== 'number') continue;
      var no = i + 1;
      var title = String(pair[0] == null ? '' : pair[0]);
      if (!title) title = '第 ' + no + ' 集';
      episodes.push({
        no: no,
        title: title,
        line: order[0],       // 主线路；换线看 play 返回的 alts
        ref: String(no),      // spider 模型：本源的 ref 就是集号
        episodeNumber: no,    // Java 适配器同名字段
        duration: ''
      });
    }
    return episodes;
  }

  /** 某一集在所有线路上的密文，按 lineOrder 排。 */
  function ciphersAt(playlists, order, no) {
    var out = [];
    for (var i = 0; i < order.length; i++) {
      var line = order[i];
      var list = playlists[line];
      if (!list || typeof list.length !== 'number') continue;
      var pair = list[no - 1];
      if (!pair || typeof pair.length !== 'number') continue;
      var token = String(pair[1] == null ? '' : pair[1]);
      if (token.indexOf('age_') !== 0) continue;   // 不是密文就不是我们能交的东西
      out.push({ line: line, token: token });
    }
    return out;
  }

  // ── 能力 ──────────────────────────────────────────────────────────

  __spiderSite = {
    id: 'agedm',
    name: 'AGE动漫',
    version: 1,
    requires: '>=1',
    // net 白名单：数据、封面、播放解析全在 api.agedm.io。封面是百度图床的代理地址，
    // 但 spider 不请求封面（只把 URL 交给前端），所以不用列 —— 列了反而放行一个
    // 我们并不需要的域。
    hosts: ['api.agedm.io'],

    /**
     * 首页。站点自己有 /v2/home-list，推荐 + 最新拼成一份平铺的 items。
     * Java 那边把 recommend 塞 featured、latest 塞 latest；spider 只给一个
     * items —— 分区是 UI 的决定，不是源的决定。
     */
    async home(ctx) {
      var raw = await fetchJson(ctx, BASE + '/v2/home-list');
      var out = [];
      var seen = {};
      var groups = [raw && raw.recommend, raw && raw.latest];
      for (var g = 0; g < groups.length; g++) {
        var cards = toCards(groups[g]);
        for (var i = 0; i < cards.length; i++) {
          if (seen[cards[i].id]) continue;
          seen[cards[i].id] = 1;
          out.push(cards[i]);
        }
      }
      if (out.length === 0) {
        throw new Error('AGE动漫 首页没解析到卡片（可能：接口改版、region 被拦、或拿到的是 SPA 兜底页）');
      }
      return { items: out, page: 1, hasMore: false };
    },

    /**
     * 分类 / 列表。
     * args.cate    → 目录接口的 label（中文类型词，如「搞笑」）
     * args.order   → time / hits / name
     * args.page    → 页码
     */
    async category(ctx, args) {
      args = args || {};
      var label = String(args.cate || args.slug || '');
      var page = pageOf(args.page);
      var url = BASE + '/v2/catalog?' + catalogQuery(label, args.order)
        + '&page=' + page + '&size=' + PAGE_SIZE;
      var raw = await fetchJson(ctx, url);
      var items = toCards(raw && raw.videos);
      if (items.length === 0 && page === 1) {
        throw new Error('AGE动漫 这个分类没有内容（分类词可能是空的：label 要传中文原词，'
          + '不是代码；传了「' + label + '」）');
      }
      return { items: items, page: page, hasMore: hasMoreFromTotal(raw && raw.total, page, PAGE_SIZE) };
    },

    async search(ctx, args) {
      args = args || {};
      var keyword = String(args.q || args.keyword || '');
      var page = pageOf(args.page);
      if (!keyword) return { items: [], page: 1, hasMore: false };
      // 参数名是 query，写 wd / keyword 会拿到 {"code":40050,"message":"参数错误！"}。
      var raw = await fetchJson(ctx, BASE + '/v2/search?query=' + enc(keyword) + '&page=' + page);
      var payload = (raw && raw.data) ? raw.data : raw;   // 万一哪天信封没了也别崩
      var items = toCards(payload && payload.videos);
      var totalPage = parseInt((payload && payload.totalPage) || 0, 10);
      var hasMore = totalPage > 0 ? page < totalPage : items.length >= PAGE_SIZE;
      return { items: items, page: page, hasMore: hasMore };
    },

    async detail(ctx, args) {
      var id = String((args && (args.ref || args.id)) || '').trim();
      if (!id) throw new Error('AGE动漫 详情缺少 id');
      var raw = await fetchJson(ctx, BASE + '/v2/detail/' + enc(id));
      var video = raw && raw.video;
      if (!video) {
        throw new Error('AGE动漫 里找不到这个 id：' + id + '（可能已下架，或接口改版）');
      }

      var playlists = video.playlists || {};
      // player_vip 在**响应顶层**，不在 video 里。Java 侧踩过这个坑：一度
      // 拿 video.player_vip 永远空串，站点给的线路优先级从来没生效过。
      var playerVip = String((raw && raw.player_vip) || video.player_vip || '');
      var labels = (raw && raw.player_label_arr) || {};

      var order = lineOrder(playlists, playerVip);
      var episodes = episodesOf(video, playlists, order);

      var lines = [];
      for (var i = 0; i < order.length; i++) {
        var key = order[i];
        var name = String(labels[key] || key);
        lines.push({ line: key, name: name });
      }

      var tags = first(video, ['plot_arr', 'tags_arr']);
      var tagList = [];
      if (tags && typeof tags.length === 'number') {
        for (var t = 0; t < tags.length; t++) tagList.push(String(tags[t]));
      }

      var out = {
        id: String(video.id == null ? id : video.id),
        title: String(video.name || ''),
        cover: String(video.cover || ''),
        description: String(video.intro || ''),
        category: String(video.type || ''),
        tags: tagList,
        episodes: episodes,
        episodeCount: episodes.length,
        lines: lines,
        // 站点没有「推荐位」这个概念给 spider 用；series / similar 是另一批 id，
        // 形状和 Card 不完全一致，本轮不硬塞。
        related: []
      };
      var score = first(video, ['star']);
      if (score !== null) {
        var s = String(score);
        if (s !== '0' && s !== '0.0') out.score = score;
      }
      var badge = String(video.uptodate || '');
      if (badge) out.badge = badge;
      // 明说：这个源的播放地址要过站点自己的 wasm 门，spider 版给不出直链。
      out.playNote = '这个源的播放地址是 age_ 密文，需要站点自己的签名门（wasm）才能解；'
        + 'spider 版拿不到直链，见 play 返回的 note。';
      return out;
    },

    /**
     * 播放。**这里给不出直链**，见文件头那段降级说明。
     *
     * 返回：
     *   url     = ''（不拿密文冒充播放地址）
     *   alts    = 该集所有线路的密文，url 带 `agedm-wasm:` 前缀
     *   reason  = 'encrypted'
     *   note    = 给人看的一句话
     * 前端看到 reason=encrypted 就该走它自己的 wasm 解析器，而不是把 url 丢给播放器。
     */
    async play(ctx, args) {
      args = args || {};
      var raw0 = String(args.ref || args.id || '').trim();
      if (!raw0) throw new Error('AGE动漫 播放缺少 ref（视频 id 或「id:集号」）');
      var id = raw0;
      var epNo = 0;
      var colon = raw0.indexOf(':');
      if (colon > 0) {
        id = raw0.substring(0, colon);
        epNo = parseInt(raw0.substring(colon + 1), 10);
      }
      if (!epNo) epNo = parseInt(args.ep || args.no || 1, 10);
      if (isNaN(epNo) || epNo < 1) epNo = 1;

      // 播放地址零缓存：每次现取 detail。密文本身每次都会变（实测同一集连拉两次
      // 不同），缓存了也用不上。
      var raw = await fetchJson(ctx, BASE + '/v2/detail/' + enc(id));
      var video = raw && raw.video;
      if (!video) throw new Error('AGE动漫 里找不到这个 id：' + id + '（拿不到详情，没法播放）');

      var playlists = video.playlists || {};
      var playerVip = String((raw && raw.player_vip) || video.player_vip || '');
      var order = lineOrder(playlists, playerVip);
      if (!order.length) {
        throw new Error('AGE动漫 这部没有播放列表（id=' + id + '）。可能片源下架。');
      }

      var main = playlists[order[0]] || [];
      var count = main.length;
      if (epNo > count) {
        throw new Error('AGE动漫 第 ' + epNo + ' 集不存在（共 ' + count + ' 集）');
      }

      var ciphers = ciphersAt(playlists, order, epNo);
      if (!ciphers.length) {
        throw new Error('AGE动漫 第 ' + epNo + ' 集没有可用的密文（线路 ' + order.join(' / ')
          + '）。可能这一集只有第三方解析地址。');
      }

      var labels = (raw && raw.player_label_arr) || {};
      var alts = [];
      for (var i = 0; i < ciphers.length; i++) {
        alts.push({
          line: ciphers[i].line,
          name: String(labels[ciphers[i].line] || ciphers[i].line),
          url: PLAY_MARKER + ciphers[i].token
        });
      }

      var note = '这一集的地址在站点侧是加密的（age_ 密文，要跑站点自己的 Go/WASM 签名门），'
        + 'spider 版跑不了 wasm，所以给不出直链。'
        + 'alts 里是 ' + alts.length + ' 条线路的密文，url 留空是刻意的：'
        + '拿密文当播放地址交给播放器只会报格式不支持，用户看不出真正原因。';

      ctx.call('log', { msg: 'agedm play id=' + id + ' ep=' + epNo
        + ' 密文 ' + alts.length + ' 条（无直链，降级）' });

      return {
        url: '',
        source: '',
        alts: alts,
        episodeNumber: epNo,
        reason: 'encrypted',
        note: note
      };
    }
  };
})();
