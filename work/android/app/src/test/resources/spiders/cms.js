// 苹果CMS 族（一个 spider 覆盖所有跑苹果CMS 的采集站）—— spider 版。
//
// 语义照抄 CmsAdapter.java + CmsXml.java：JSON 接口（?ac=detail）和 XML 接口
// （/at/xml/）两代写法，字段一一对应，XML 那套在这里用正则重写一遍翻成 JSON 形状，
// 后面全部复用同一套映射。
// 只用字符串 + 正则 + JSON.parse —— 要能在没有 DOM 的沙箱里跑离线回归。
//
// ── 站点地址从哪来 ────────────────────────────────────────────────────
// 苹果CMS 的 api 地址是**每个站自己的**（bfzyapi.com / cj.lziapi.com / …），
// 不像 agedm/missav 有固定域。所以：
//   1) 优先读 args.api（源配置里 web.api 传下来的）；
//   2) 没给就用 DEFAULT_API —— 一个实测通的主站，够离线回归和默认可用。
// hosts 里把候选域全列出来（net 白名单是「源自己声明的」），否则换站就取不到。
//
// ── spider 没有 probe 这一步 ──────────────────────────────────────────
// Java 那边的 probe 干三件事：两次探测确认是苹果CMS、从 class[] 生成分类树、
// 提封面域名写进配置。spider 里这些全部挪进 home/category 现做：
//   · 分类树直接从响应里的 class[] 读，不落配置；
//   · 封面域名不需要声明（spider 不请求封面，只把 URL 交给前端）。
//
// ── 分类要摊成叶子 ────────────────────────────────────────────────────
// class[] 是**平表 + type_pid 指父类**。父类拿不到内容（实测），点进去是空的。
// 所以 category() 给内容时优先叶子；home() 顺带把叶子清单放进 categories，
// 前端直接拿这份排胶囊就行，不用自己算树。
//
// ── 播放地址 ──────────────────────────────────────────────────────────
//   vod_play_from: "dbm3u8$$$bfzym3u8"          线路名，多条用 $$$ 分隔
//   vod_play_url:  "第1集$https://…/index.m3u8#第2集$…"  # 分集，$ 分（集名/地址）
// 里面多是 m3u8 直链。但**第一条线路不一定是直链**（实测量子那边第一条是
// /share/ 分享页，要第三方解析）。所以和 Java 一样：全部线路交进 alts，
// url 取第一条 .m3u8 > .mp4 > 第一条；一条直链都没有时明确报错，不静默失败。
(function () {
  /** 没传 api 时的兜底。换站就靠源配置里的 web.api。 */
  var DEFAULT_API = 'https://api.apibdzy.com/api.php/provide/vod';

  // ── 取数据（对齐 CmsAdapter.call）──────────────────────────────────

  function apiOf(args) {
    var api = String((args && args.api) || '').trim();
    if (!api) api = DEFAULT_API;
    // 去掉已有的查询串：后面要用 ? 拼 ac=…&pg=…
    var q = api.indexOf('?');
    if (q >= 0) api = api.substring(0, q);
    return api;
  }

  function looksLikeXml(body) {
    var head = String(body || '').replace(/^\s+/, '');
    head = head.substring(0, 200);
    return head.indexOf('<?xml') === 0 || head.indexOf('<rss') === 0 || head.indexOf('<list') === 0;
  }

  async function call(ctx, api, query) {
    var url = api + (api.indexOf('?') >= 0 ? '&' : '?') + query;
    var res;
    try {
      res = await ctx.call('net', { url: url, headers: { Accept: 'application/json, text/xml, */*' } });
    } catch (e) {
      throw new Error('CMS 请求失败 ' + url + '：' + ((e && e.message) || e));
    }
    if (!res || res.ok !== true) {
      throw new Error('CMS 请求失败 ' + url + '：' + ((res && res.error) || '没有响应'));
    }
    var body = String(res.body == null ? '' : res.body);
    if (body.length === 0) throw new Error('CMS 返回空响应：' + url);

    // XML 接口（type 0 那种 /at/xml/）先翻译成 JSON 那套形状，后面全部复用。
    if (looksLikeXml(body)) {
      var parsed = parseXml(body);
      if (parsed.code !== 1) throw new Error('CMS 返回错误：code=' + parsed.code);
      return parsed;
    }

    var json;
    try {
      json = JSON.parse(body);
    } catch (e) {
      throw new Error('CMS 返回的不是 JSON（可能是 XML 接口或错误页）：'
        + head(body, 80));
    }
    if (json.hasOwnProperty('code')) {
      var code = parseInt(json.code, 10);
      if (!isNaN(code) && code !== 1) {
        throw new Error('CMS 返回错误：' + (json.msg || json.message || ('code=' + code)));
      }
    }
    return json;
  }

  // ── XML 接口（对齐 CmsXml.parse）──────────────────────────────────
  //
  // 字段对应：
  //   list[].vod_id      <video><id>
  //   list[].vod_name    <video><name><![CDATA[…]]>
  //   vod_pic            <video><pic>          ← **只在详情里有**
  //   vod_play_from      <dl><dd flag="…">（多条）
  //   vod_play_url       <dl><dd> 的内容（多条）
  //   class[]            <class><ty id="…">…</ty>
  //
  // 列表里没有封面是这个接口的限制，不是我们的 bug —— 所以 XML 源的首页卡片
  // cover 会是空串，前端退回标题首字占位图。

  function xmlText(raw) {
    if (raw == null) return '';
    var s = String(raw).trim();
    if (s.indexOf('<![CDATA[') === 0) {
      var end = s.lastIndexOf(']]>');
      s = end > 9 ? s.substring(9, end) : s.substring(9);
    }
    s = s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'").replace(/&amp;/g, '&');
    // 全角空格和连续空白：简介里常有一大段缩进用的
    s = s.replace(/\u3000/g, ' ').replace(/[ \t\x0B\f\r]+/g, ' ').replace(/\n{2,}/g, '\n');
    return s.trim();
  }

  function xmlTag(body, name) {
    var re = new RegExp('<' + name + '>([\\s\\S]*?)</' + name + '>', 'i');
    var m = re.exec(body);
    return m ? xmlText(m[1]) : '';
  }

  function xmlInt(v) {
    var n = parseInt(String(v || '').replace(/[^\d-]/g, ''), 10);
    return isNaN(n) ? 0 : n;
  }

  function allMatches(re, src) {
    var out = [];
    re.lastIndex = 0;
    var m;
    while ((m = re.exec(src)) !== null) {
      out.push(m);
      if (m[0].length === 0) re.lastIndex++;   // 防零长匹配死循环
    }
    return out;
  }

  function parseXml(xml) {
    if (xml.indexOf('<list') < 0) {
      throw new Error('XML 响应里没有 <list>，这个接口可能不是苹果CMS 的 XML 格式');
    }
    var out = { code: 1 };

    // <list page="1" pagecount="7960" pagesize="20" recordcount="159193">
    var listStart = xml.indexOf('<list');
    var listEnd = xml.indexOf('>', listStart);
    if (listStart >= 0 && listEnd > listStart) {
      var attrs = allMatches(/(\w+)="([^"]*)"/g, xml.substring(listStart, listEnd));
      for (var a = 0; a < attrs.length; a++) {
        out[attrs[a][1]] = xmlInt(attrs[a][2]);
      }
    }
    if (out.page === undefined) out.page = 1;
    if (out.pagecount === undefined) out.pagecount = 1;

    var videos = [];
    var blocks = allMatches(/<video>([\s\S]*?)<\/video>/gi, xml);
    for (var i = 0; i < blocks.length; i++) {
      var b = blocks[i][1];
      // 多条线路按 $$$ 拼，跟 JSON 那套的格式一致，后面 splitPlayUrl 直接复用。
      var flags = [];
      var ddFlag = allMatches(/<dd[^>]*flag="([^"]*)"[^>]*>([\s\S]*?)<\/dd>/gi, b);
      for (var f = 0; f < ddFlag.length; f++) flags.push(ddFlag[f][1]);
      var urls = [];
      var dd = allMatches(/<dd[^>]*>([\s\S]*?)<\/dd>/gi, b);
      for (var u = 0; u < dd.length; u++) urls.push(xmlText(dd[u][1]));

      videos.push({
        vod_id: xmlInt(xmlTag(b, 'id')),
        vod_name: xmlTag(b, 'name'),
        type_id: xmlInt(xmlTag(b, 'tid')),
        type_name: xmlTag(b, 'type'),
        vod_pic: xmlTag(b, 'pic'),
        vod_remarks: xmlTag(b, 'note'),
        vod_content: xmlTag(b, 'des'),
        vod_score: '',                       // XML 接口没有评分
        vod_play_from: flags.join('$$$'),
        vod_play_url: urls.join('$$$')
      });
    }
    out.list = videos;

    // <class><ty id="1">电影片</ty>…</class>
    var classes = [];
    var ty = allMatches(/<ty[^>]*id="([^"]*)"[^>]*>([\s\S]*?)<\/ty>/gi, xml);
    for (var t = 0; t < ty.length; t++) {
      classes.push({ type_id: xmlInt(ty[t][1]), type_name: xmlText(ty[t][2]) });
    }
    if (classes.length > 0) out['class'] = classes;
    return out;
  }

  // ── 分类树 ────────────────────────────────────────────────────────

  /**
   * class[] → 平表的 {id, name, pid}。
   * type_pid 为 0 或缺省表示顶层。
   */
  function classTree(classes) {
    var out = [];
    if (!classes || typeof classes.length !== 'number') return out;
    for (var i = 0; i < classes.length; i++) {
      var c = classes[i];
      if (!c) continue;
      var id = parseInt(c.type_id, 10);
      if (isNaN(id)) continue;
      var pid = parseInt(c.type_pid, 10);
      if (isNaN(pid)) pid = 0;
      out.push({
        id: id,
        name: String(c.type_name || ''),
        pid: pid
      });
    }
    return out;
  }

  /** 有子类的 id 集合。 */
  function parentsOf(tree) {
    var set = {};
    for (var i = 0; i < tree.length; i++) {
      if (tree[i].pid && tree[i].pid !== tree[i].id) set[tree[i].pid] = 1;
    }
    return set;
  }

  /** 叶子 = 没有子类的那些。父类查不出内容（实测），所以给用户的是这份。 */
  function leavesOf(tree) {
    var parents = parentsOf(tree);
    var out = [];
    for (var i = 0; i < tree.length; i++) {
      if (parents[tree[i].id]) continue;
      if (!tree[i].name) continue;
      out.push(tree[i]);
    }
    return out;
  }

  function categoriesPayload(tree) {
    var leaves = leavesOf(tree);
    var out = [];
    for (var i = 0; i < leaves.length; i++) {
      var slug = String(leaves[i].id);
      out.push({ slug: slug, name: leaves[i].name, cate: slug, group: 'cms' });
    }
    return out;
  }

  /**
   * 父类拿不到内容的实测证据怎么处理：不再往父类发请求赌一次，直接按叶子发。
   * 但**用户明确传了父类 id** 时也没法假装它是叶子 —— 那就直接用父类 id 查，
   * 查空了也不报错，因为「这个父类底下本来就没内容」是站点的既定行为。
   */
  function resolveCate(tree, want) {
    if (!want) return '';
    var id = parseInt(want, 10);
    if (isNaN(id)) return String(want);       // 不是数字就当站点原样的 t 值
    return String(id);
  }

  // ── 映射 ──────────────────────────────────────────────────────────

  /**
   * CMS 的一条 list 条目 → Card。
   *
   * Java 那边靠配置里的 web.fields 别名表投影（同族里字段名不完全一样）。
   * spider 没有配置表，所以这里放一张**默认别名表**，仍然按「取第一个有值的」
   * 语义 —— 换站只要字段名在表里就能用，不在的话改这一张表。
   */
  var ALIAS = {
    title: ['vod_name', 'name', 'title'],
    cover: ['vod_pic', 'vod_pic_thumb', 'pic', 'cover'],
    badge: ['vod_remarks', 'note', 'remarks'],
    category: ['type_name', 'type'],
    description: ['vod_blurb', 'vod_content', 'des'],
    score: ['vod_score'],
    tags: ['vod_class', 'vod_tag']
  };

  function pick(x, role) {
    var keys = ALIAS[role] || [];
    for (var i = 0; i < keys.length; i++) {
      var v = x[keys[i]];
      if (v === undefined || v === null) continue;
      if (typeof v === 'string' && v.length === 0) continue;
      return v;
    }
    return null;
  }

  function toCard(x) {
    if (!x || typeof x !== 'object') return null;
    var id = x.vod_id;
    if (id === undefined || id === null || String(id).length === 0) return null;
    var card = {
      id: String(id),
      title: String(pick(x, 'title') || id),
      cover: String(pick(x, 'cover') || '')
    };
    var badge = pick(x, 'badge');
    if (badge) card.badge = String(badge);
    var cate = pick(x, 'category');
    if (cate) card.category = String(cate);
    var desc = pick(x, 'description');
    if (desc) card.description = stripHtml(String(desc));
    // 未评分的 CMS 返回 "0.0"，界面上会显示成一个 0.0 的角标。不给这个键就不画。
    var score = pick(x, 'score');
    if (score !== null) {
      var s = String(score).trim();
      if (s.length > 0 && s.indexOf('0.0') !== 0 && s.indexOf('0.00') !== 0) card.score = s;
    }
    var tag = pick(x, 'tags');
    if (tag) {
      var parts = String(tag).split(/[,，\s]+/);
      var tags = [];
      for (var i = 0; i < parts.length; i++) if (parts[i]) tags.push(parts[i]);
      if (tags.length) card.tags = tags;
    }
    return card;
  }

  function toCards(list) {
    var out = [];
    if (!list || typeof list.length !== 'number') return out;
    for (var i = 0; i < list.length; i++) {
      var c = toCard(list[i]);
      if (c) out.push(c);
    }
    return out;
  }

  /** 去掉 CMS 简介里的 HTML 标签 —— 实测 vod_content 是 `<p>…</p>`。 */
  function stripHtml(html) {
    return String(html == null ? '' : html)
      .replace(/<[^>]+>/g, '')
      .replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, '&')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function head(text, n) {
    var s = String(text == null ? '' : text);
    return s.length <= n ? s : s.substring(0, n) + '…';
  }

  // ── 播放地址拆解（对齐 CmsAdapter.splitPlayUrl / allLines / pickLine）──

  function allLines(raw) {
    var out = [];
    if (!raw) return out;
    var parts = String(raw).split('$$$');
    for (var i = 0; i < parts.length; i++) {
      var line = parts[i].trim();
      if (line.length > 0) out.push(line);
    }
    return out;
  }

  /** 拆**单条线路**：`集名1$地址1#集名2$地址2`。集名可能是空的，用「第 N 集」兜底。 */
  function splitLine(line) {
    var out = [];
    if (!line) return out;
    var parts = String(line).split('#');
    for (var i = 0; i < parts.length; i++) {
      var seg = parts[i];
      if (seg.length === 0) continue;
      var dollar = seg.indexOf('$');
      var name;
      var url;
      if (dollar < 0) {
        name = '';
        url = seg;
      } else {
        name = seg.substring(0, dollar);
        url = seg.substring(dollar + 1);
      }
      if (name.length === 0) name = '第 ' + (i + 1) + ' 集';
      out.push([name, url.trim()]);
    }
    return out;
  }

  /** 从一次详情响应里拆出 {lines:[{line,name,eps}], longest}。 */
  function playLines(item) {
    var urls = allLines(item && item.vod_play_url);
    var fromRaw = String((item && item.vod_play_from) || '');
    var froms = fromRaw.length ? fromRaw.split('$$$') : [];
    var out = [];
    var longest = 0;
    var mainIdx = -1;
    for (var i = 0; i < urls.length; i++) {
      var eps = splitLine(urls[i]);
      var label = String(froms[i] || ('LINE' + (i + 1))).trim();
      out.push({ line: label, name: label, eps: eps });
      if (eps.length > longest) {
        longest = eps.length;
        mainIdx = i;
      }
    }
    return { lines: out, episodes: mainIdx < 0 ? [] : out[mainIdx].eps };
  }

  function isDirect(url) {
    var u = String(url || '');
    return u.indexOf('.m3u8') >= 0 || u.indexOf('.mp4') >= 0;
  }

  function pageOf(page) {
    var n = parseInt(page, 10);
    return isNaN(n) || n < 1 ? 1 : n;
  }

  // ── 能力 ──────────────────────────────────────────────────────────

  __spiderSite = {
    id: 'cms',
    name: '苹果CMS',
    version: 1,
    requires: '>=1',
    // 候选采集站。net 白名单是「源自己声明的」，换站时至少要有一个在表里 ——
    // 所以把实测过的几家都列上，api 参数也用这些域。
    hosts: [
      'api.apibdzy.com',      // 暴风
      'bfzyapi.com',
      'cj.lziapi.com',        // 量子
      'ffzyapi.com',          // 非凡
      'wujinapi.com'          // 无尽
    ],

    /** 首页 = 最新一页。CMS 没有「首页」概念，这里只给一个平铺 items。 */
    async home(ctx, args) {
      var api = apiOf(args);
      var raw = await call(ctx, api, 'ac=detail&pg=1');
      var items = toCards(raw.list);
      if (items.length === 0) {
        throw new Error('CMS 首页没解析到内容（' + api + '）。'
          + '可能：这个地址不是苹果CMS 的采集接口、接口改了字段名、或被对方限流。');
      }
      var tree = classTree(raw['class']);
      var out = { items: items, page: 1, hasMore: false };
      // 分类树现读，不落配置 —— spider 没有 probe 那一步。
      if (tree.length) out.categories = categoriesPayload(tree);
      return out;
    },

    /**
     * 分类 / 列表。
     * args.cate / args.slug → type_id（number 或字符串都收）
     * args.page             → 页码
     * args.all              → 传 '1' 表示不带分类过滤（首页「更多」走这条）
     */
    async category(ctx, args) {
      args = args || {};
      var api = apiOf(args);
      var page = pageOf(args.page);
      var want = String(args.cate != null ? args.cate : (args.slug != null ? args.slug : ''));

      var query = 'ac=detail';
      var cate = resolveCate(null, want);
      if (cate && String(args.all || '') !== '1') query += '&t=' + encodeURIComponent(cate);
      query += '&pg=' + page;

      var raw = await call(ctx, api, query);
      var items = toCards(raw.list);
      var pages = parseInt(raw.pagecount, 10);
      if (isNaN(pages) || pages < 1) pages = 1;
      if (items.length === 0 && page === 1) {
        throw new Error('CMS 这个分类没有内容（' + api + '，t=' + (cate || '全部') + '）。'
          + '可能：传的是父类 id（父类查不出内容，要传叶子）、或者分类被对方清空了。');
      }
      return { items: items, page: page, hasMore: page < pages };
    },

    async search(ctx, args) {
      args = args || {};
      var api = apiOf(args);
      var keyword = String(args.q || args.keyword || '');
      var page = pageOf(args.page);
      if (!keyword) return { items: [], page: 1, hasMore: false };
      var raw = await call(ctx, api, 'ac=detail&wd=' + encodeURIComponent(keyword) + '&pg=' + page);
      var items = toCards(raw.list);
      var pages = parseInt(raw.pagecount, 10);
      if (isNaN(pages) || pages < 1) pages = 1;
      return { items: items, page: page, hasMore: page < pages };
    },

    async detail(ctx, args) {
      args = args || {};
      var api = apiOf(args);
      var id = String((args.ref || args.id) || '').trim();
      if (!id) throw new Error('CMS 详情缺少 ref（vod_id）');
      var raw = await call(ctx, api, 'ac=detail&ids=' + encodeURIComponent(id));
      var list = raw.list;
      var item = (list && list.length > 0) ? list[0] : null;
      if (!item) {
        throw new Error('CMS 里找不到这个 id：' + id + '（' + api + '）。可能片源已下架。');
      }

      var split = playLines(item);
      var episodes = [];
      for (var i = 0; i < split.episodes.length; i++) {
        var no = i + 1;
        episodes.push({
          no: no,
          title: split.episodes[i][0],
          line: split.lines.length ? split.lines[0].line : 'default',
          ref: String(no),
          episodeNumber: no,
          duration: ''
        });
      }

      var lines = [];
      for (var k = 0; k < split.lines.length; k++) {
        lines.push({ line: split.lines[k].line, name: split.lines[k].name });
      }

      var card = toCard(item) || { id: id, title: id, cover: '' };
      var out = {
        id: String(item.vod_id == null ? id : item.vod_id),
        title: card.title,
        cover: card.cover,
        description: stripHtml(item.vod_content || item.vod_blurb || ''),
        category: String(item.type_name || ''),
        tags: card.tags || [],
        episodes: episodes,
        episodeCount: episodes.length,
        lines: lines.length ? lines : [{ line: 'default', name: '默认线路' }],
        related: []
      };
      if (card.badge) out.badge = card.badge;
      if (card.score) out.score = card.score;
      return out;
    },

    /**
     * 播放。地址**每次现取**（线路会换，采集站天天变）。
     *
     * alts = 该集在所有线路上的地址；url 按 `.m3u8` > `.mp4` > 第一条 选 ——
     * 第一条线路不一定是直链（实测量子那边是 /share/ 分享页）。
     * 全部线路都不是直链时明确报错，因为那要第三方解析器，spider 版没有。
     */
    async play(ctx, args) {
      args = args || {};
      var api = apiOf(args);
      var ref = String((args.ref || args.id) || '').trim();
      if (!ref) throw new Error('CMS 播放缺少 ref（vod_id）');
      var id = ref;
      var epNo = 0;
      var colon = ref.indexOf(':');
      if (colon > 0) {
        id = ref.substring(0, colon);
        epNo = parseInt(ref.substring(colon + 1), 10);
      }
      if (!epNo) epNo = parseInt(args.ep || args.no || 1, 10);
      if (isNaN(epNo) || epNo < 1) epNo = 1;

      var raw = await call(ctx, api, 'ac=detail&ids=' + encodeURIComponent(id));
      var list = raw.list;
      var item = (list && list.length > 0) ? list[0] : null;
      if (!item) throw new Error('CMS 里找不到这个 id：' + id + '（拿不到这一集的播放地址）');

      var split = playLines(item);
      if (!split.episodes.length) {
        throw new Error('CMS 这部没有分集地址（id=' + id + '）。可能对方只给了分享页或空 vod_play_url。');
      }
      if (epNo > split.episodes.length) {
        throw new Error('第 ' + epNo + ' 集不存在（共 ' + split.episodes.length + ' 集）');
      }

      var alts = [];
      var firstAny = '';
      for (var i = 0; i < split.lines.length; i++) {
        var row = split.lines[i];
        var ep = row.eps[epNo - 1];
        if (!ep) continue;
        var url = String(ep[1] || '').trim();
        if (!url) continue;
        alts.push({ line: row.line, name: row.name, url: url });
        if (!firstAny) firstAny = url;
      }
      if (!alts.length) throw new Error('第 ' + epNo + ' 集没有播放地址');

      var picked = '';
      var i2;
      for (i2 = 0; i2 < alts.length; i2++) {
        if (alts[i2].url.indexOf('.m3u8') >= 0) { picked = alts[i2].url; break; }
      }
      if (!picked) {
        for (i2 = 0; i2 < alts.length; i2++) {
          if (alts[i2].url.indexOf('.mp4') >= 0) { picked = alts[i2].url; break; }
        }
      }
      if (!picked) picked = firstAny;

      // 分享页而不是直链时明确报错：那种要 parses[] 才能解，本轮不支持，
      // 但绝不能静默失败 —— 用户看到的是「点了没反应」。
      if (!isDirect(picked)) {
        throw new Error('这一集不是直链（要第三方解析），spider 版暂不支持：' + head(picked, 60)
          + '。共 ' + alts.length + ' 条线路，都不是 .m3u8/.mp4。');
      }

      return {
        url: picked,
        source: picked,
        alts: alts,
        episodeNumber: epNo,
        title: split.lines.length ? (split.lines[0].eps[epNo - 1] || [''])[0] : ''
      };
    }
  };
})();
