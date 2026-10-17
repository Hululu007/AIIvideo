/*
 * AIIvideo —— 站点接口的形状
 *
 * 从 app.js 抽出来的两层纯逻辑，都不碰 DOM、不碰网络：
 *
 *   1. apiPathFor(action, args)  把内部动作名翻成站点 REST 路径
 *   2. adapt*()                  把站点返回的 JSON 翻成 App 内部结构
 *
 * 为什么值得单独一层：这套 REST 是**替代抓 HTML** 的（原版靠 site.json 的
 * CSS 选择器解析页面，站点一改版就崩）。接口的形状变了要改的是这里，
 * 传输层的超时/重试/日志一行都不用动。
 *
 * 用法：
 *   const shape = window.HGShape.create({ resolveCoverUrl });
 *   const path = shape.apiPathFor('home', {});
 *   const data = shape.adaptHome(raw);
 */
(() => {
  'use strict';

  // ---- 动作名 → REST 路径 --------------------------------------------------
  // 路径模板、分类 id、分页大小全部来自 site-web.js。以前这些硬编码在这里，
  // 换个站点就得重新打包；现在改配置就行。
  //
  // 返回值同时兼作「这个动作走不走 REST」的判据 —— 返回 null 就会落到 Java 桥
  // （账号、收藏、主题那些）。所以下面那些前置检查不是可有可无的校验，
  // 它们决定一个动作往哪条路走。
  const SITE = (typeof window !== 'undefined' && window.HG_SITE) || {};

  // 站点字段别名：角色名 → 候选键列表，按顺序取第一个有值的。
  //
  // 原代码里这层是硬编码的 —— `item.desc || item.description`、`raw.title || raw.page_h1`
  // 都是同一个概念在站点侧有两个名，作者不确定用哪个就都写上了。搬到配置之后，
  // 换一个字段名略有差异的站点只改 site-default.json 的 web.fields。
  //
  // 值判定用「undefined / null / 空串」三选一，不是单纯的 falsy —— 否则 score=0
  // 这种合法值会被跳过，跑到下一个别名上去。
  function fields() { return SITE.fields || {}; }

  /**
   * 按路径取值，路径可以带点：`video.name` 会往里走一层。
   *
   * 为什么要这个：站点的模型未必是平的。AGE动漫 的详情把主体包在 video 里
   * （{video:{name,…}, player_jx:{…}}），而我们是平的。没有带点的路径，
   * 「换一层壳」这种事就得写代码 —— 可它明明只是「值在哪儿」的问题。
   */
  function atPath(object, path) {
    if (!object || !path) return undefined;
    if (String(path).indexOf('.') < 0) return object[path];
    const parts = String(path).split('.');
    let node = object;
    for (let i = 0; i < parts.length; i++) {
      if (node === null || typeof node !== 'object') return undefined;
      node = node[parts[i]];
    }
    return node;
  }

  function pick(object, role) {
    if (!object) return undefined;
    const names = fields()[role];
    if (!names) return undefined;
    for (let i = 0; i < names.length; i++) {
      const value = atPath(object, names[i]);
      if (value !== undefined && value !== null && value !== '') return value;
    }
    return undefined;
  }

  // ── ④ 响应形状映射 ──────────────────────────────────────────────────
  //
  // fields 管的是「条目里那个值叫什么」，这里管的是「那个值挂在响应树的哪一根枝上」。
  //
  // 我们自己的形状是固定的（首页要 featured/latest/cate_rec，列表要 items+pagination，
  // 详情要 episodes）。站点的形状各不相同：AGE动漫 的首页是 {latest, recommend,
  // week_list}，列表是 {total, videos}，详情把主体包在 video 里。
  //
  // 这些差异**没有一样需要计算或重排**，只是「我们的 A 在它那边叫 D」——
  // 所以能声明。声明和计算的分界线：声明可以枚举、可以校验（build.sh 就在查），
  // 计算不行 —— 一旦配置里能算东西，它就不再是配置了。
  //
  // 缺省 = 用我们自己的键名，所以老配置一个字不用改。
  function shapes() { return SITE.shapes || {}; }
  function shapeOf(action) { return shapes()[action] || {}; }

  /** div 的操作数：路径，或者我们自己的两个分页参数（站点不返回它们）。 */
  function shapeOperand(raw, token) {
    if (typeof token === 'number') return token;
    if (token === '$pageSize') return pageSize();
    if (token === '$firstPageSize') return firstPageSize();
    return Number(atPath(raw, token));
  }

  /**
   * 取一个「形状位」。
   *   没配          → 用 fallbackKey（我们自己的键名，可以是带点的路径）
   *   "some.path"   → 按路径取
   *   {"div":[a,b]} → 相除向上取整。分页数基本都是这个形态：
   *                   `ceil(total / size)` —— 就一个除法，可枚举、可校验，
   *                   不是「表达式求值器」那种滑坡的开头。
   */
  function shapeValue(raw, action, key, fallbackKey) {
    const spec = shapeOf(action)[key];
    if (spec === undefined) return atPath(raw, fallbackKey === undefined ? key : fallbackKey);
    if (typeof spec === 'string') return atPath(raw, spec);
    if (spec && Array.isArray(spec.div)) {
      const a = shapeOperand(raw, spec.div[0]);
      const b = shapeOperand(raw, spec.div[1]);
      if (!isFinite(a) || !isFinite(b) || b <= 0) return undefined;
      return Math.ceil(a / b);
    }
    return undefined;
  }

  /**
   * 响应最外层那层信封叫什么。
   *
   * 我们自己的约定是 `{"data": …}`（fetchApi 里查的就是它）。但有的站的接口是裸的
   * —— AGE动漫 的 /v2/home-list 直接返回 {latest, recommend, week_list}，没有信封。
   * 配 `"shapes": {"envelope": ""}` 就表示「整份响应就是我要的那个对象」。
   *
   * 缺省 'data'，所以老接口一个字不用改。
   */
  function envelopeKey() {
    const spec = shapes().envelope;
    return spec === undefined ? 'data' : String(spec);
  }

  /** 详情那种「主体裹在一层壳里」的情况：拿到壳里面的那个对象。 */
  function shapeRoot(raw, action) {
    const root = shapeOf(action).root;
    if (typeof root !== 'string' || !root) return raw;
    return atPath(raw, root) || raw;
  }

  function sitePaths() { return SITE.paths || {}; }
  function firstPageSize() { return Number(SITE.firstPageSize) || 8; }
  function pageSize() { return Number(SITE.pageSize) || 20; }
  function cateIdOf(slug) { return (SITE.categories || {})[slug]; }

  function fillTemplate(template, values) {
    return template.replace(/\{(\w+)\}/g, (whole, key) => {
      const value = values[key];
      return value == null ? '' : encodeURIComponent(String(value));
    });
  }

  function apiPathFor(action, args) {
    const template = sitePaths()[action];
    if (!template) return null;

    const id = args.id;
    const episode = args.episode;
    const keyword = args.query || args.keyword || args.label || args.slug || '';
    let cateId = null;

    // 接口按数字 cate_id 过滤，前端用的是 slug。查不到就别走 REST ——
    // /api/videos 不带 cate_id 会返回全站，静默给错数据比失败更糟。
    if (action === 'category') {
      cateId = cateIdOf(id);
      if (!cateId) return null;
    }
    if ((action === 'detail' || action === 'episode') && !id) return null;
    if (action === 'episode' && !episode) return null;
    // 标签页和服务端搜索共用 /api/search，没有关键词就没意义
    if ((action === 'search' || action === 'tag-list') && !keyword) return null;

    const page = Number(args.page) || 1;
    return fillTemplate(template, {
      id,
      episode,
      keyword,
      // slug 单独给一个占位符：标签在苹果CMS 那边就是 type_id，tag-list 要按
      // cate_id 取列表（它的搜索是标题匹配，拿「动作片」搜一个都搜不到）。
      // keyword 会被 label 顶掉，救不了这个场景。
      slug: args.slug,
      cateId,
      page,
      size: page === 1 ? firstPageSize() : pageSize(),
    });
  }

  // renderTags 按 group 分区渲染（privateMode 下是 popular/story/setting/relationship/
  // style/plot/other 七组），而接口是按话题分类给的，这里按分类 code 折过去。
  //
  // 认不出来的 code 会折进 other。苹果CMS 那条线是自己带一组（CmsAdapter 给的
  // code 就是 "cms"），所以这里得显式列出来 —— 不列的话它会被折进 other，而
  // CMS 的配置里只声明了 "cms" 一组，于是「全部类型」页整页空白、首页那排胶囊
  // 也一个不剩（实测踩过）。
  const TAG_GROUP_BY_CODE = {
    appearance: 'style',        // 成人外貌
    play: 'plot',               // 成人玩法
    changjing: 'setting',       // 成人场景
    djtc: 'story',              // 短剧题材
    jiakong: 'story',           // 短剧架空
    nanpin: 'popular',          // 短剧男频
    nvpin: 'popular',           // 短剧女频
    djll: 'relationship',       // 短剧伦理
    gyk: 'other',               // 短剧共用库
    'chigua-heiliao': 'other',  // 吃瓜黑料
    cms: 'cms'                  // 苹果CMS 的 type 分类表
  };

  function create(deps) {
    const resolveCoverUrl = deps.resolveCoverUrl || (raw => String(raw == null ? '' : raw));
    const asArray = value => Array.isArray(value) ? value : [];

    function adaptVideo(item) {
      return {
        id: pick(item, 'id'),
        title: pick(item, 'title'),
        cover: resolveCoverUrl(pick(item, 'cover')),
        score: pick(item, 'score'),
        tags: asArray(pick(item, 'tags')),
        description: pick(item, 'description') || '',
        category: pick(item, 'category') != null ? String(pick(item, 'category')) : '',
        episodeLabel: pick(item, 'episodeCount')
          ? ('共 ' + pick(item, 'episodeCount') + ' 集')
          : (pick(item, 'badge') || pick(item, 'playAt') || '')
      };
    }

    function adaptHome(raw) {
      const featured = asArray(shapeValue(raw, 'home', 'featured')).map(adaptVideo);
      const latest = asArray(shapeValue(raw, 'home', 'latest')).map(adaptVideo);
      // featured 就 4 条（正好是「AI成人短剧」分类的前四条），首页「精选推荐」那一行
      // 只填得满一半。把 cate_rec 里各分类的视频按 id 去重接上，凑满一行。
      const recommend = featured.slice();
      const seen = new Set(recommend.map(item => String(pick(item, 'id'))));
      asArray(shapeValue(raw, 'home', 'cate_rec')).forEach(group => {
        asArray(group.videos).forEach(item => {
          const video = adaptVideo(item);
          if (seen.has(String(video.id))) return;
          seen.add(String(video.id));
          recommend.push(video);
        });
      });
      return {
        featured,
        homepageRecommend: recommend,
        homepageNewest: latest,
        recommend: { items: recommend },
        newest: { items: latest }
      };
    }

    function adaptDetail(source) {
      // 有的站把主体裹在 video 里（AGE动漫），shapeValue 的 root 负责剥掉那层壳。
      const raw = shapeRoot(source, 'detail');
      return {
        id: String(pick(raw, 'id')),
        title: pick(raw, 'title') || '',
        cover: resolveCoverUrl(pick(raw, 'cover')),
        description: pick(raw, 'description') || '',
        score: pick(raw, 'score') != null ? pick(raw, 'score') + '分' : '',
        tags: asArray(pick(raw, 'tags')),
        episodes: asArray(shapeValue(raw, 'detail', 'episodes')).map(entry => ({
          number: pick(entry, 'episodeNumber'),
          title: pick(entry, 'title') || '',
          duration: pick(entry, 'duration'),
        }))
      };
    }

    function adaptEpisode(raw) {
      // pick() 按 web.fields 的别名表找。适配器（比如 AGE动漫）直接按原名返回时，
      // 配置里的别名表可能没有那个字面量，于是取到空 —— 播放地址就这么没了。
      // 所以每个键都补一层同名字面量兜底。
      const r = raw || {};
      return {
        id: String(pick(r, 'id') || r.id || ''),
        episode: pick(r, 'episodeNumber') || r.episodeNumber || r.ep_num,
        title: pick(r, 'title') || r.title || '',
        source: pick(r, 'source') || r.source || '',
        cover: '',
        nextUrl: ''
      };
    }

    // 接口用 pagination:{page,pages,size,total}；渲染层要的是 nextPage，
    // 有下一页就给页码，没有就给 null（null 会让列表显示「已经到底了」）。
    function nextPageOf(raw) {
      // 默认还是老的 pagination.{page,pages}；配了 shapes.list 就按配置取。
      const page = Number(shapeValue(raw, 'list', 'page', 'pagination.page')) || 1;
      const pages = Number(shapeValue(raw, 'list', 'pages', 'pagination.pages')) || 0;
      return page < pages ? page + 1 : null;
    }

    function adaptSearch(raw) {
      return { items: asArray(shapeValue(raw, 'list', 'items')).map(adaptVideo), nextPage: nextPageOf(raw) };
    }

    function adaptCategory(raw) {
      return { items: asArray(shapeValue(raw, 'list', 'items')).map(adaptVideo), nextPage: nextPageOf(raw) };
    }

    function adaptTags(raw) {
      const items = [];
      asArray(shapeValue(raw, 'tags', 'categories')).forEach(group => {
        const code = pick(group, 'code') || '';
        asArray(group.tags).forEach(tag => {
          if (!pick(tag, 'slug')) return;
          // ── 2026-10-17 修：标签自己带的 group 优先 ────────────────────
          //
          // 原代码只认 code → TAG_GROUP_BY_CODE 这一条路，**标签上带的 group
          // 字段被完全忽略**。于是走适配器的源（它们已经在 Java 侧算好了最终
          // 组名）只要 code 不在那张表里，所有标签就一起折进 other ——
          // 而 renderTags 是按组渲染的，落在没有配置的组里就等于不显示。
          //
          // hanime1 就是这么踩中的：适配器给了 hanime-attr / hanime-role /
          // hanime-act 这些它自己的分组，映射表里一个都没有，240 个标签
          // 全部折进 other，页面一片空白。
          //
          // 现在的顺序：标签自己的 group → 组的 code 查表 → other。
          // 老源（只有 code、标签上没有 group）行为不变。
          //
          // ⚠️ 直接读 `tag.group`，**不用 pick()**。pick 是走 web.fields 别名表的，
          //    而 group 是适配器在 Java 侧算好的**最终值**，不是「可能叫什么」的
          //    候选名之一 —— 走别名反而要往 fields 里塞一个语义不对的角色，
          //    而 build.sh 的自检会当场把这种塞法拦下来（它比对的是「代码用到的
          //    角色」和「fields 里声明的角色」两张表，多一个少一个都报）。
          const own = tag && typeof tag.group === 'string' ? tag.group : '';
          items.push({
            name: pick(tag, 'tagName') || pick(tag, 'slug'),
            slug: pick(tag, 'slug'),
            group: own || TAG_GROUP_BY_CODE[code] || 'other'
          });
        });
      });
      return { items };
    }

    return {
      apiPathFor,
      envelopeKey,
      adaptVideo,
      adaptHome,
      adaptDetail,
      adaptEpisode,
      adaptSearch,
      adaptCategory,
      adaptTagList: adaptCategory,
      adaptTags,
      nextPageOf,
      cateIdOf,
      firstPageSize,
      pageSize,
    };
  }

  window.HGShape = { create, apiPathFor };
})();
