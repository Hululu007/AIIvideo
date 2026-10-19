/*
 * AIIvideo —— spider 输出 → 前端形状（模型转换层，阶段 2）
 *
 * 这一层只做一件事：把 spider ABI 吐出来的对象，翻成 app.js / adapters.js 现在
 * **已经在读**的那副骨架。它不改语义、不补业务、不猜站点结构 —— 猜是 spider 的
 * 事，它拿得到 HTML。这里只负责「字段叫什么」。
 *
 * 三条硬约束，破坏任何一条都会让下游炸：
 *
 *   1. 纯函数。不碰 DOM、不碰网络、不读 window.HG_SITE。
 *      所以 Node 能直接 vm 加载它跑离线回归（scripts/model-check.js），
 *      不用真机、不用 WebView、不用等网。
 *   2. **缺的字段给空串/0，绝不给 undefined。** undefined 进了字符串模板就是
 *      字面上的「undefined」五个字母 —— 2026-10-18 的选集格子事故就是这么来的：
 *      模板 `${item.episodeNumber}` 渲染出一排 undefined。所以每个出参字段都必须
 *      有确定的初值，哪怕它是空。
 *   3. 出参只增不删。前端今天读 `episodes[].number`，明天读 `.episodeNumber`，
 *      两个都给；删谁由前端那一侧先改完再说。
 *
 * 唯一允许碰到外界的行是文件末尾 `window.HGModel = …`。那不是「依赖 window」，
 * 那只是宿主页的挂载点，跟 assets 里别的脚本一个规矩。
 */
(function () {
  'use strict';

  // ── 小工具：这三个函数的存在意义就是「不返回 undefined」────────────
  //
  // 为什么不直接用 `x || ''`：`x` 是 0 或 false 时 `||` 会把它吃掉，而
  // score=0 / episodeNumber=0 都是合法值。区分「没有」和「是 0」只能显式判空。
  function str(value) {
    return value === undefined || value === null ? '' : String(value);
  }

  function num(value) {
    var n = Number(value);
    return isFinite(n) ? n : 0;
  }

  function arr(value) {
    return Array.isArray(value) ? value : [];
  }

  /** 数字位补零：1 → '01'。只用于「标题缺失时兜一个」的那条路。 */
  function pad2(n) {
    var s = String(n);
    return s.length >= 2 ? s : '0' + s;
  }

  // 封面：spider 按 ABI 约定给绝对地址（hosts 白名单里的域）。这里只处理
  // 协议相对地址 `//cdn/x.jpg` —— 那是唯一一种不用知道站点就能补全的形态。
  // 相对路径 `/cover/1.jpg` 补不了：base 在 spider 手里，转换层没有配置可读。
  function coverUrl(raw) {
    var s = str(raw).trim();
    if (s.indexOf('//') === 0) return 'https:' + s;
    return s;
  }

  // ── 前端字段：episodeLabel ────────────────────────────────────────
  //
  // adaptVideo 的算法原样搬过来（'共 N 集' → badge → playAt），因为卡片右下角那行小字
  // 就是它，换一种写法会让同一份数据在两张卡上显示不同文案。
  function episodeLabel(episodeCount, raw) {
    var count = num(episodeCount);
    if (count > 0) return '共 ' + count + ' 集';
    var badge = str(raw && raw.badge);
    if (badge) return badge;
    return str(raw && raw.playAt);
  }

  // ── Card：spider 的卡片 → adaptVideo 的输出 ────────────────────────
  //
  // 字段顺序不重要，齐全才重要。`badge` / `episodeCount` 在前端不是「卡片字段」，
  // 它们是 episodeLabel 的原料，但仍然原样留着 —— 下一阶段要拿 badge 做角标。
  function card(raw) {
    var item = raw || {};
    var count = num(item.episodeCount);
    return {
      id: str(item.id),
      title: str(item.title),
      cover: coverUrl(item.cover),
      score: item.score === undefined || item.score === null || item.score === '' ? '' : item.score,
      tags: arr(item.tags).map(str),
      description: str(item.description),
      category: str(item.category),
      badge: str(item.badge),
      episodeCount: count,
      episodeLabel: episodeLabel(count, item),
      duration: str(item.duration)
    };
  }

  // ── Episode：spider 的 {no, ref, line, title} → 前端读的那一套 ──────
  //
  // 这里是整个转换层存在的主要理由：
  //   no  → episodeNumber（选集格子上的数字）
  //   ref → id（站内标识，下一阶段 play 要拿它去解析地址）
  //
  // `ref` **原样保留**。retire 掉 web.paths 之后，前端手里只有这个 ref，
  // 丢了它就得回头再取一次详情页。
  //
  // `number` 是给 app.js 的 renderDetail 用的（它现在就写 `data-episode="${item.number}"`），
  // 和 episodeNumber 同值。等 renderDetail 改读 episodeNumber，这个键可以删。
  function episode(raw) {
    var item = raw || {};
    var no = num(item.episodeNumber) || num(item.no);
    var ref = str(item.ref);
    var title = str(item.title);
    // 站点没给标题时兜一个「第NN集」，不是美化 —— 选集格子是空字符串的话，
    // 用户看到一排空白按钮，和渲染 undefined 一样没法点。
    if (!title && no > 0) title = '第' + pad2(no) + '集';
    return {
      episodeNumber: no,
      number: no,
      title: title,
      ref: ref,
      id: str(item.id) || ref,
      line: str(item.line) || 'default',
      duration: str(item.duration)
    };
  }

  // ── Line：从详情页的 lines 到播放页的 lines，同一个形状 ──────────────
  //
  // 详情页的 line 只有 {line, name}（没有地址 —— 那正是 Stage 5 的「Episode.ref 是
  // 标识不是 URL」）。播放页的 line 有 url。两种都收，缺的键给空串。
  function line(raw) {
    var item = raw || {};
    var key = str(item.line) || 'default';
    return { line: key, name: str(item.name) || key, url: str(item.url) };
  }

  function lines(raw) {
    var out = arr(raw).map(line).filter(function (one) { return one.line !== ''; });
    // 详情页一条线路都没给，不代表「不能播」—— 很多站线路信息只藏在播放页里。
    // 给一条默认线路，前端就不会拿着空数组画出空白的线路选择器。
    if (out.length === 0) out = [{ line: 'default', name: '默认线路', url: '' }];
    return out;
  }

  // ── Paged：{page, hasMore, items} ─────────────────────────────────
  //
  // nextPage 是给 adapters.js 的 nextPageOf 那套老调用点的兼容键：列表页判
  // 「还有没有下一页」读的是 nextPage === null，不是 hasMore。等列表页改读
  // hasMore，这个键一并删（见 docs/2026-10-19-spider模型与转换.md 第 4 节）。
  function paged(payload) {
    var data = payload || {};
    var page = num(data.page) || 1;
    var hasMore = data.hasMore === true;
    return {
      items: arr(data.items).map(card),
      page: page,
      hasMore: hasMore,
      nextPage: hasMore ? page + 1 : null
    };
  }

  // ── Detail ───────────────────────────────────────────────────────
  //
  // 注意 episodeCount 的算法：spider 给了就用它，没给就用 episodes.length。
  // 反过来（一律用 length）会在「只返回一页剧集」的站上把总集数算成 3，
  // 前端那句「共 3 集」就成了谎话。
  function detail(payload) {
    var data = payload || {};
    var out = card(data);
    var episodes = arr(data.episodes).map(episode);
    out.episodeCount = num(data.episodeCount) || episodes.length;
    out.episodeLabel = episodeLabel(out.episodeCount, data);
    out.description = str(data.description);
    out.episodes = episodes;
    out.lines = lines(data.lines);
    return out;
  }

  // ── Play ─────────────────────────────────────────────────────────
  //
  // spider 给的是 {url, alts:[{line,url}], reason, note}：url 是「默认播这一条」，
  // alts 是全部线路。前端要的是 {url, lines}，外加一句「能不能播、为什么」。
  //
  // 三件事容易错：
  //   ① url 必须先落在 lines 里 —— 否则播放器拿到的地址和线路列表对不上，
  //      用户切一下线路再切回来就换了源。
  //   ② lines 空但 url 有值（spider 只给了一条），也要给出一条默认线路。
  //   ③ **降级不是失败**。agedm 的 url:'' 是刻意的：age_ 密文要在站点自己的
  //      wasm 里解，spider 跑不了。它把密文标记放进 alts、把原因放进 reason。
  //      这一层要是把 alts 滤掉、或者把密文扶正成 url，上层就只剩一句
  //      「格式不支持」，和「这个源压根没实现 play」长得一模一样。
  function play(payload) {
    var data = payload || {};
    var reason = str(data.reason);
    var note = str(data.note);
    var url = str(data.url);
    var list = [];
    arr(data.alts).forEach(function (raw, index) {
      var one = line(raw);
      // 只滤掉「url 是空」的条目。**不按协议过滤** —— agedm 降级的线路是
      // `agedm-wasm:xxx` 的密文标记，前端那段 wasm 解析器认的就是这个前缀
      // （app.js 的 hgNativeResolve）。按 http 开头筛等于把它唯一剩下的线索扔掉。
      if (!one.url) return;
      if (!str(raw && raw.line)) one.line = 'LINE' + (index + 1);
      list.push(one);
    });

    // 源明确说了「播不了、原因是 X」时，不把第一条线路扶正成 url。
    // 把密文当播放地址交出去，播放器报的是格式错，用户看不出真正原因。
    //
    // 这条规则等于给源提了个要求：reason 和 url 互斥（给了 reason 就是没有直链）。
    // 万一以后有源既给 reason、alts 里又真有一条 https 直链、url 还留空，
    // 现在的输出是 declined + 一条列在 lines 里但不会被自动播的线路。
    // 那天要改成「url 空时优先从 lines 里挑第一条 http(s) 扶正，reason 降级成附注」，
    // 同时确认 agedm 那种全加密的仍判 declined。见 docs/2026-10-19-spider模型与转换.md。
    if (!url && !reason && list.length > 0) url = list[0].url;
    if (list.length === 0 && !reason) {
      list = [{ line: 'default', name: '默认线路', url: url }];
    } else if (url) {
      var found = list.some(function (one) { return one.url === url; });
      if (!found) list.unshift({ line: 'default', name: '默认线路', url: url });
    }

    // state 是给上层的判断位，别处不要再自己推一遍：
    //   ready    —— 有 http(s) 直链，可以直接播
    //   declined —— 源认得出这一集，但明确说播不了（典型 reason='encrypted'）
    //   empty    —— 源什么都没给：没地址也没解释（「没实现」落在这一档）
    var state = 'empty';
    if (/^https?:\/\//i.test(url)) state = 'ready';
    else if (reason) state = 'declined';

    return {
      url: url,
      lines: list,
      episodeNumber: num(data.episodeNumber),
      state: state,
      reason: reason,
      note: note
    };
  }

  // ── 分派 ─────────────────────────────────────────────────────────
  //
  // 三种「页面级」能力（home / category / search）返回的是同一个 Paged 形状，
  // 所以共用 paged()。动作名收在这里而不是散在调用方：将来加 category 的
  // 筛选参数，只改这一张表。
  var PAGED_CAPS = { home: 1, category: 1, search: 1, list: 1, tagList: 1 };
  var DETAIL_CAPS = { detail: 1, episode: 1 };
  var PLAY_CAPS = { play: 1 };
  // 标签清单不是 Paged：它没有卡片、没有翻页，只有「名字 + slug + 组」。
  // 混进 PAGED_CAPS 的后果是每个标签都被 card() 洗一遍，slug 丢了，
  // 点下去变成按 id 搜，页面显示 0 条 —— 而标签页本身看起来是对的。
  var TAG_CAPS = { tags: 1 };

  // ── Tags ─────────────────────────────────────────────────────────
  //
  // 渲染层（app.js renderTags）要的是 `{name, slug, group}`：
  //   name  → 按钮文字
  //   slug  → 点下去传给 tag-list 的标识
  //   group → 分组标题（91porn 是 'category'，hanime1 那一族有七八个组）
  //
  // 两条兜底都是被真机逼出来的：slug 缺失时用 name 顶上（有的源列表项上
  // 只有中文名，slug 是同一个东西）；group 缺失时归 'other'，否则
  // renderTags 那句 `tags.filter(tag => tag.group === id)` 一条都匹配不上，
  // 页面是空的而标题写着「N 个标签」。
  function tags(payload) {
    var list = [];
    arr((payload || {}).items).forEach(function (raw) {
      var one = raw || {};
      var slug = str(one.slug) || str(one.name) || str(one.id);
      var name = str(one.name) || slug;
      if (!slug || !name) return;
      list.push({ name: name, slug: slug, group: str(one.group) || 'other' });
    });
    return { items: list };
  }

  // ── Home ─────────────────────────────────────────────────────────
  //
  // home 是个例外：它不是 Paged，而是**首页信封**。app.js 的 renderHome /
  // homeItems 读的是 adaptHome 产出的那五个键（featured / homepageRecommend /
  // homepageNewest / recommend.items / newest.items），少一个就是一块空白。
  // 2026-10-19 真机第一次跑 spider 源时首页只有横幅没有网格，就是这里按 Paged
  // 返回了 {items}。
  //
  // featured 取前 8 条当横幅轮播（adaptHome 里是 4 条 + 分类推荐拼出来的）；
  // 没有单独的「推荐」「最新」数据时三行指向同一批 items —— 首页是入口，
  // 多给几处重复比空着强。
  function home(payload) {
    var items = arr((payload || {}).items).map(card);
    var featured = items.slice(0, 8);
    return {
      featured: featured,
      homepageRecommend: items,
      homepageNewest: items,
      recommend: { items: items },
      newest: { items: items }
    };
  }

  function fromBridge(payload, cap) {
    var name = str(cap);
    if (name === 'home') return home(payload);
    if (PAGED_CAPS[name]) return paged(payload);
    if (TAG_CAPS[name]) return tags(payload);
    if (DETAIL_CAPS[name]) return detail(payload);
    if (PLAY_CAPS[name]) return play(payload);
    // 抛而不是返回空：未知能力名意味着前端和 spider 的 ABI 对不上，
    // 静默返回 {items:[]} 会让它变成「这个源没内容」，排查方向直接跑偏。
    throw new Error('HGModel.fromBridge：未知的能力名 ' + (name || '(空)'));
  }

  var HGModel = {
    card: card,
    home: home,
    paged: paged,
    tags: tags,
    detail: detail,
    play: play,
    fromBridge: fromBridge
  };

  // 全文件唯一一行碰宿主的代码。Node 侧在 vm 里塞一个 `window` 壳就能拿到它，
  // 前端侧和 adapters.js 一样挂在 window 上，两边加载的是同一个文件、同一份逻辑。
  if (typeof window !== 'undefined') window.HGModel = HGModel;
})();
