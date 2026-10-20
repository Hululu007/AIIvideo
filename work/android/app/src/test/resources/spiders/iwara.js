// Iwara（iwara.tv）—— spider 版。
//
// ── 这个站的三件事，先说清楚 ──────────────────────────────────────────
//
// ① **整站（含 api）在 Cloudflare 盾后**。裸 curl 打 www.iwara.tv 和 api.iwara.tv
//    一律 403 + 5.4KB 的 `Just a moment...`，带完整安卓 Chrome 头也一样 —— 差的是
//    TLS 指纹，不是 UA/cookie。所以配置里必须声明 `web.shield`，请求交给壳去过盾，
//    源里**不写**任何挑战检测（契约 §7.2）。唯一例外是封面：`i.iwara.tv` 没盾，
//    裸 curl 直接 200，封面走普通 okhttp 就行，别把它塞进 shield.hosts 拖慢首屏。
//
// ② **盾后回填给我们的是 WebView 的 outerHTML，不是响应体**。`ctx.net` 打到盾后域
//    时，主进程用真浏览器导航过去、把 `document.documentElement.outerHTML` 交回来。
//    对 HTML 页那正好是页面；**对 JSON 接口，Chrome 会把它渲染进 `<pre>`**，于是
//    我们拿到的是
//        <html><body><pre>{"count":33,...}</pre></body></html>
//    而不是裸 JSON。直接 JSON.parse 必炸，所以下面有 unwrap()：先认裸 JSON，再认
//    `<pre>` 里那份（要解 HTML 实体），最后才按首个 `{`/`[` 到末个 `}`/`]` 硬切。
//    三种形态都兜住，是因为真机上这三条路都可能出现（盾回填 / headless 直取 /
//    将来壳改成直接回字节）。
//
// ③ **播放地址是两跳，而且第二跳的主机名每次都不一样**。
//    `GET /video/{id}` 回一个 `fileUrl`（filesq.iwara.tv，带 expires+hash 签名），
//    `GET fileUrl` 回来的**不是视频**，是一份清晰度清单：
//        [{"name":"360","src":{"view":"//bailu.iwara.tv/view?hash=...&expires=..."}},
//         {"name":"preview","src":{"view":"//blade.iwara.tv/view?..."}}]
//    真正能播的是 `src.view`，主机名（bailu/blade/kafka/…）是站点从一份固定池里
//    随机挑的，同一条视频连着请求两次都会换名。所以配置的 `http.hosts` 里得把
//    观察到的池子列全 —— 漏一个的表现是「能出详情、点播放报主机不在白名单」。
//
// ── 接口面（全部 api.iwara.tv，都是 JSON）──────────────────────────────
//   列表   GET /videos?sort=<date|trending|popularity|views|likes|recent>
//                      &rating=all&page=N&limit=M
//          → {count, limit, page, results:[video]}
//          ⚠ 这里的 count **不是总数**，实测恒等于 page*limit + n + 1（假的）。
//            所以「还有下一页」只能看 results.length === limit。
//   搜索   GET /search?type=videos&page=N&query=Q[&limit=M]
//          → {page, count, limit, results:[...], type:"videos"}
//          这里的 count 是真的（hatsune miku 实测 1566）。
//          ⚠ /videos?search= 是**无效**的（参数被忽略，回默认列表），别用。
//   详情   GET /video/{id} → video + fileUrl
//   清单   GET <fileUrl>   → [{name, src:{view,download}, type}]
//   标签   GET /videos?tags=<tagId>  → 单标签过滤，可用（tags=mmd 服务端 500 是站点
//          自己的数据问题，不是参数写错）
//
// ── 一条视频 = 一集 ────────────────────────────────────────────────────
// 这个站没有「剧集」概念，每条投稿就是一个视频文件。所以 detail 里只给一集
// （episodes 长度 1），清晰度档位放在 play 的 alts 里当「线路」。
(function () {
  var API = 'https://api.iwara.tv';
  var IMG = 'https://i.iwara.tv';

  /** 接口默认页大小。站点自己用 32；实测 limit 上限 50（给 100 也只回 50）。 */
  var PAGE_SIZE = 32;

  /**
   * 类型页认得的排序键。**必须和 iwara.json 的 web.categories 对得上** ——
   * 那边一个 key 就是这里一个 slug。别的 slug 一律当标签 id 处理。
   */
  var SORTS = ['date', 'trending', 'popularity', 'views', 'likes', 'recent'];

  /**
   * 清晰度排序。站点给的名字是 `360` / `preview` 这种，会员/大文件还会有
   * `1080` / `source`。数值越大越靠前（index 越小）。
   */
  var QUALITY = ['source', '2160', '1440', '1080', '720', '540', '480', '360', 'preview'];

  // ── 响应解包 ────────────────────────────────────────────────────────

  /** HTML 实体回解。顺序有讲究：`&amp;` 必须最后做，否则 `&amp;lt;` 会被解两次。 */
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

  /**
   * 把 `ctx.net` 的 body 还原成 JSON 文本。
   *
   * 三种形态，按出现概率排：
   *   1. 裸 JSON（壳直接回字节时）—— 首字符就是 `{` 或 `[`；
   *   2. `<pre>` 包了一层（盾后回填，Chrome 的 JSON 观感页）—— 现在最常走这条；
   *   3. 其它（站点改版、错误页）—— 退到「首个 { 到末个 }」硬切，切不出来才报错。
   *
   * 不给 fetch/XHR/DOM 的沙箱里，这三条都得靠字符串，所以没有 DOMParser 可用。
   */
  function unwrap(raw) {
    var s = String(raw == null ? '' : raw);
    // 顺手去掉 BOM 和前导空白：实测有的代理会给 UTF-8 BOM。
    var t = s.replace(/^\uFEFF/, '').replace(/^\s+/, '');
    if (t.charAt(0) === '{' || t.charAt(0) === '[') return t;

    var m = /<pre[^>]*>([\s\S]*?)<\/pre>/i.exec(s);
    if (m) {
      t = decodeEntities(m[1]).replace(/^\s+/, '');
      if (t.charAt(0) === '{' || t.charAt(0) === '[') return t;
    }

    var a = s.indexOf('{');
    var b = s.lastIndexOf('}');
    if (a >= 0 && b > a) {
      var cut = s.slice(a, b + 1);
      // 硬切出来的东西也要能解析，否则下面 JSON.parse 会抛得很含糊。
      try {
        JSON.parse(cut);
        return cut;
      } catch (e) { /* 落到下面的报错 */ }
    }
    throw new Error('响应里找不到 JSON（' + s.length + ' 字节，开头：'
      + s.slice(0, 120).replace(/\s+/g, ' ') + '）');
  }

  /**
   * 打一次接口并解析。
   *
   * 错误信息按契约 §9 写：带地址、带体量、说人话。这个站最容易出的两种失败是
   * 「盾没过」（回填的是挑战页 / 空页）和「站点改版」（JSON 形状变了），
   * 两种都靠体量 + 开头能一眼分开。
   */
  async function fetchJson(ctx, url) {
    var res;
    try {
      res = await ctx.net({ url: url });
    } catch (e) {
      throw new Error('iwara 请求失败 ' + url + '：' + ((e && e.message) || e)
        + '（这个站在 Cloudflare 后面，出口被拦时就是这个样子）');
    }
    if (!res || res.ok !== true) {
      throw new Error('iwara 请求失败 ' + url + '：' + ((res && res.error) || '没有响应'));
    }
    var body = String(res.body == null ? '' : res.body);
    if (body.length === 0) throw new Error('iwara 返回空响应：' + url + '（多半是盾把响应吞了）');
    var text = unwrap(body);
    try {
      return JSON.parse(text);
    } catch (e) {
      throw new Error('iwara 的响应不是合法 JSON：' + url + '（' + text.length
        + ' 字节，开头：' + text.slice(0, 100).replace(/\s+/g, ' ') + '）');
    }
  }

  function enc(s) {
    return encodeURIComponent(String(s == null ? '' : s));
  }

  // ── 卡片 ────────────────────────────────────────────────────────────

  /**
   * 封面地址。
   *
   * 站点自己两种都渲：
   *   - 作者传了自定义封面 → `i.iwara.tv/image/original/{assetId}/{assetName}`
   *   - 没传 → 从视频里抽的第 N 帧 → `i.iwara.tv/image/thumbnail/{fileId}/thumbnail-NN.jpg`
   *     NN 是**两位零填充**（thumbnail-00 … thumbnail-15），N 就是响应里的
   *     `thumbnail` 字段，范围 0..numThumbnails-1。
   *
   * 越界就退回 0：实测有老视频 `thumbnail` 写 15 而 `numThumbnails` 是 12，
   * 照抄会拿到 404，封面变灰块。
   */
  function coverOf(v) {
    var c = v && v.customThumbnail;
    if (c && c.id && c.name) return IMG + '/image/original/' + c.id + '/' + c.name;
    var f = v && v.file;
    if (!f || !f.id) return '';
    var n = Number(v.thumbnail);
    if (!isFinite(n) || n < 0) n = 0;
    var max = Number(f.numThumbnails);
    if (isFinite(max) && max > 0 && n > max - 1) n = 0;
    return IMG + '/image/thumbnail/' + f.id + '/thumbnail-' + (n < 10 ? '0' + n : String(n)) + '.jpg';
  }

  /** 秒 → `12:34` / `1:02:03`。站点的 duration 是秒数。 */
  function durationOf(sec) {
    var s = Number(sec);
    if (!isFinite(s) || s <= 0) return '';
    s = Math.round(s);
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    var ss = s % 60;
    var pad = function (x) { return x < 10 ? '0' + x : String(x); };
    return h > 0 ? (h + ':' + pad(m) + ':' + pad(ss)) : (m + ':' + pad(ss));
  }

  /** 标签：列表接口里带 type，`category` 是站点的主分类，其余是普通 tag。 */
  function tagsOf(v) {
    var out = [];
    var src = (v && v.tags) || [];
    for (var i = 0; i < src.length; i++) {
      if (src[i] && src[i].id) out.push(String(src[i].id));
    }
    return out;
  }

  function categoryOf(v) {
    var src = (v && v.tags) || [];
    for (var i = 0; i < src.length; i++) {
      if (src[i] && src[i].type === 'category' && src[i].id) return String(src[i].id);
    }
    return '';
  }

  /**
   * 一条 video → 卡片。
   *
   * 字段取舍：这个站没有「集数」，所以 episodeCount 一律不给（给了会显示
   * 「共 1 集」，对单视频投稿是废话）；时长走 `duration`，简介走 `description`。
   */
  function cardOf(v) {
    if (!v || !v.id) return null;
    var dur = durationOf(v.file && v.file.duration);
    return {
      id: String(v.id),
      title: String(v.title || ''),
      cover: coverOf(v),
      description: String(v.body || ''),
      tags: tagsOf(v),
      category: categoryOf(v),
      duration: dur,
      badge: dur,
      score: v.numLikes ? String(v.numLikes) : '',
      playAt: String(v.createdAt || '').slice(0, 10)
    };
  }

  function cardsOf(list) {
    var out = [];
    for (var i = 0; i < (list || []).length; i++) {
      var card = cardOf(list[i]);
      if (card) out.push(card);
    }
    return out;
  }

  /**
   * 列表结果 + 「有没有下一页」。
   *
   * 两个接口的 count 语义不同（见文件头 ⓘ），所以分开判：
   *   - `/search` 带 `type:"videos"` 且 count 是真总数 → 用 count 算；
   *   - `/videos` 的 count 是 page*limit+n+1 的假数 → 只能按「这一页是不是满的」判。
   *
   * 宁可多给一页空的，也不要谎报有下一页（契约 §5.1）。
   */
  function listResult(data, page, limit) {
    var items = cardsOf(data && data.results);
    var n = (data && data.results ? data.results.length : 0);
    var more;
    if (data && data.type === 'videos' && typeof data.count === 'number') {
      more = (page + 1) * limit < data.count;
    } else {
      more = n >= limit;
    }
    return { items: items, page: page, hasMore: more };
  }

  // ── 播放清单 ────────────────────────────────────────────────────────

  /** 协议相对地址（`//host/x`）补成 https。播放层只认 http(s) 绝对地址。 */
  function absUrl(u) {
    var s = String(u == null ? '' : u).trim();
    if (!s) return '';
    if (s.indexOf('//') === 0) return 'https:' + s;
    if (s.indexOf('https://') === 0) return s;
    if (s.indexOf('http://') === 0) return 'https://' + s.slice(7);
    return '';
  }

  function qualityRank(name) {
    var n = String(name == null ? '' : name).toLowerCase();
    for (var i = 0; i < QUALITY.length; i++) {
      if (n.indexOf(QUALITY[i]) >= 0) return i;
    }
    return QUALITY.length;
  }

  function qualityLabel(name) {
    var n = String(name == null ? '' : name).toLowerCase();
    if (n.indexOf('preview') >= 0) return '预览';
    if (n.indexOf('source') >= 0) return '原画';
    if (n.indexOf('2160') >= 0) return '4K';
    if (n.indexOf('1440') >= 0) return '2K';
    if (n.indexOf('1080') >= 0) return '1080P';
    if (n.indexOf('720') >= 0) return '720P';
    if (n.indexOf('540') >= 0) return '540P';
    if (n.indexOf('480') >= 0) return '480P';
    if (n.indexOf('360') >= 0) return '360P';
    return String(name || '默认');
  }

  /**
   * 清单 → alts。按清晰度从高到低排，第一条就是默认播放的。
   *
   * 同一档位去重：实测清单里偶尔会出现两条 `360`（重新转码留下的），
   * 重复的 line 会让前端「切一次线路再切回来」时挑错。
   */
  function altsOf(man) {
    if (!man || !man.length) return [];
    var rows = [];
    for (var i = 0; i < man.length; i++) {
      var one = man[i] || {};
      var url = absUrl(one.src && one.src.view);
      if (!url) continue;
      rows.push({
        name: String(one.name || ''),
        url: url,
        rank: qualityRank(one.name)
      });
    }
    rows.sort(function (a, b) { return a.rank - b.rank; });

    var seen = {};
    var out = [];
    for (var j = 0; j < rows.length; j++) {
      var line = rows[j].name || ('source' + (j + 1));
      if (seen[line]) continue;
      seen[line] = true;
      out.push({ line: line, name: qualityLabel(rows[j].name), url: rows[j].url });
    }
    return out;
  }

  // ── 能力 ────────────────────────────────────────────────────────────

  __spiderSite = {
    id: 'iwara',
    name: 'Iwara',
    version: 1,
    requires: '>=1',
    // net 白名单（精确匹配、小写、不带协议）。只列源里真正会请求的两个：
    //   api.iwara.tv    —— 列表/搜索/详情/清单，全走它
    //   filesq.iwara.tv —— /video/{id} 回的那个 fileUrl 就在这里
    // 封面（i.iwara.tv）不经 net，由 App 的封面通路取，不用列。
    hosts: ['api.iwara.tv', 'filesq.iwara.tv'],

    /** 首页 = 最新投稿。App 取前 8 条当轮播，其余铺网格，所以平铺返回就行。 */
    async home(ctx) {
      var data = await fetchJson(ctx,
        API + '/videos?sort=date&rating=all&page=0&limit=' + PAGE_SIZE);
      var out = listResult(data, 1, PAGE_SIZE);
      if (!out.items.length) {
        throw new Error('iwara 首页没解析到卡片（响应 ' + (data && data.count) + ' 条计数）');
      }
      return out;
    },

    /**
     * 类型 / 分类。
     *
     * `slug` 是配置里 `web.categories` 的 key：
     *   - 命中 SORTS（date/trending/popularity/views/likes/recent）→ 排序列表
     *   - 其它一律当**标签 id** → `/videos?tags=<slug>`
     * 这样配一个标签页不用改源码，加一行 categories 就行。
     */
    async category(ctx, args) {
      args = args || {};
      var slug = String(args.slug || '').trim();
      var page = Math.max(0, (Number(args.page) || 1) - 1);

      var url;
      if (!slug) {
        url = API + '/videos?sort=date&rating=all&page=' + page + '&limit=' + PAGE_SIZE;
      } else if (SORTS.indexOf(slug) >= 0) {
        url = API + '/videos?sort=' + enc(slug) + '&rating=all&page=' + page + '&limit=' + PAGE_SIZE;
      } else {
        url = API + '/videos?tags=' + enc(slug) + '&rating=all&page=' + page + '&limit=' + PAGE_SIZE;
      }

      var data = await fetchJson(ctx, url);
      var out = listResult(data, page + 1, PAGE_SIZE);
      if (!out.items.length && page === 0) {
        throw new Error('iwara 这个分类没解析到卡片（slug=' + slug + '）'
          + '。标签不存在时站点也回 200 空列表；tags=mmd 这种站方数据坏掉的会直接 500。');
      }
      return out;
    },

    /**
     * 搜索。走 `/search?type=videos` —— **不是** `/videos?search=`，
     * 后者参数被忽略（回默认列表），是个很容易踩的坑。
     */
    async search(ctx, args) {
      args = args || {};
      var kw = String(args.keyword || '').trim();
      var page = Math.max(0, (Number(args.page) || 1) - 1);
      if (!kw) return { items: [], page: page + 1, hasMore: false };

      var data = await fetchJson(ctx,
        API + '/search?type=videos&page=' + page + '&query=' + enc(kw) + '&limit=' + PAGE_SIZE);
      return listResult(data, page + 1, PAGE_SIZE);
    },

    /**
     * 详情。一条投稿就是一个视频，所以只有一集。
     *
     * `ref` 可能是 `<id>`，也可能是 `<id>:ep1`（详情没给 ref 时前端的兜底），
     * 按**最后一个**冒号切最稳 —— 视频 id 本身是纯字母数字，不会含冒号。
     */
    async detail(ctx, args) {
      args = args || {};
      var ref = String(args.ref || args.id || '').trim();
      var cut = ref.lastIndexOf(':');
      var id = cut > 0 ? ref.slice(0, cut) : ref;
      if (!id) throw new Error('iwara 详情缺少 id');

      var v = await fetchJson(ctx, API + '/video/' + enc(id));
      if (!v || !v.id) throw new Error('iwara 详情没拿到：' + id);

      var duration = durationOf(v.file && v.file.duration);
      var notes = [];
      if (duration) notes.push('时长 ' + duration);
      if (v.numViews) notes.push('播放 ' + v.numViews);
      if (v.numLikes) notes.push('喜欢 ' + v.numLikes);
      if (v.user && (v.user.name || v.user.username)) {
        notes.push('作者 ' + (v.user.name || v.user.username));
      }

      return {
        id: String(v.id),
        title: String(v.title || ''),
        cover: coverOf(v),
        description: String(v.body || ''),
        tags: tagsOf(v),
        episodeCount: 1,
        episodes: [{ no: 1, ref: String(v.id), title: notes.join(' · ') || '正片' }],
        lines: [{ line: 'default', name: '默认线路' }]
      };
    },

    /**
     * 播放：两跳。
     *
     *   1. `/video/{id}` → `fileUrl`（带 expires+hash 的签名地址）
     *   2. `fileUrl` → 清晰度清单 → 每档的 `src.view` 才是能播的直链
     *
     * 注意第 2 跳**不是**视频本体。把 fileUrl 直接当播放地址交给播放器，
     * 表现是「播放器一闪就报格式不支持」—— 它拿到的是 JSON。
     *
     * 降级：站点还在转码时清单是空的（实测刚投稿几十秒内就是 `[]`），
     * 这时给 reason 而不是硬塞一个坏地址（契约 §5.3）。
     */
    async play(ctx, args) {
      args = args || {};
      var ref = String(args.ref || args.id || '').trim();
      var cut = ref.lastIndexOf(':');
      var id = cut > 0 ? ref.slice(0, cut) : ref;
      if (!id) throw new Error('iwara 播放缺少 id');

      var v = await fetchJson(ctx, API + '/video/' + enc(id));
      var fileUrl = absUrl(v && v.fileUrl);
      if (!fileUrl) {
        return {
          reason: 'no-source',
          note: '这一条站点没有给出播放地址（私有投稿、仅会员，或者已经下架）'
        };
      }

      var man = await fetchJson(ctx, fileUrl);
      var alts = altsOf(man);
      if (!alts.length) {
        return {
          reason: 'transcoding',
          note: '站点的清晰度清单还是空的 —— 刚投稿的视频要转码一会儿，过几分钟再来'
        };
      }

      var main = alts[0];
      return {
        url: main.url,
        // 前端 resolveUrl 读 source || url，两个都给，别名怎么配都不会漏。
        source: main.url,
        alts: alts,
        episodeNumber: Number(args.ep) || 1,
        title: String(v.title || '')
      };
    }
  };
})();
