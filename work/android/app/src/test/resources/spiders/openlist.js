// OpenList / AList 文件站 —— spider 版。
//
// 语义照抄 OpenListAdapter.java。它和另外三个源的本质区别：接的是**文件站**，
// 不是内容站 —— 没有海报墙、没有分类表、没有详情页。
//
//   卡片（items） = **作品目录**，比如 /asmr2/野上菜月/[RJ111491]和み隠れ家
//   分类         = 站点根下那几个大目录 asmr / asmr2 / … / asmr6
//   详情         = 作品目录里递归捞出来的音频/视频文件
//   播放         = fs/get 换 raw_url（直链在另一个域名上）
//
// ── 为什么卡片从 fs/search 来，不是 fs/list ──────────────────────────
// 目录是四层：/asmr2/{CV}/{作品}/{mp3版}/*.mp3。拿 fs/list 一层层点，用户点进
// CV 目录就卡住了 —— 那一屏是「作品」，但 App 认为那是详情页，会把作品当成分集。
// fs/search 带 scope=1 一次就能把**所有目录**捞回来（实测 2659 条），CV 那层
// 自然消失。分页、分类过滤（parent 传大目录）都还是同一个接口。
//
// ── 没有封面这件事 ────────────────────────────────────────────────────
// 文件站没有海报。thumb 字段实测恒为空，目录里倒是有 イラスト/*.jpg，但为了一个
// 封面在列表阶段多打 N 次请求不值得。所以卡片封面一律空串，前端退回「标题首字」
// 占位图 —— 这是这个源本来的样子，不是坏了。
//
// ── 全部 JSON POST ────────────────────────────────────────────────────
// fs/list / fs/get / fs/search 只收 application/json，所以每个请求都要
// {method:'POST', format:'json', body: JSON.stringify(...)}。表单会被对方
// 当成「参数缺失」收下，返回 code=400。
//
// ── id 是 URL-safe base64 裹的路径 ────────────────────────────────────
// 路径里有斜杠和中文，直接塞参数会把 URL 弄坏。**中文不能直接 btoa**：
// btoa 只吃 Latin-1，遇到中文抛 InvalidCharacterError。所以先 encodeURIComponent
// 再 btoa，回来时 atob → decodeURIComponent。
(function () {
  /** 没配 origin 时的兜底（离线回归和本地调试用）。 */
  var DEFAULT_HOST = 'https://www.asmrgay.com';

  /** 一页给多少条。站点默认 30，跟它走。 */
  var PER_PAGE = 30;

  /** 作品永远在第 3 段路径：/asmr2/{CV}/{作品}。 */
  var WORK_DEPTH = 3;

  /** 详情页最多捞多少个文件 / 进多少个子目录 —— 防一个畸形目录把请求打成雪崩。 */
  var MAX_FILES = 200;
  var MAX_DIRS = 16;

  /** 索引一次捞多少。实测 2000 个目录 8~9 秒，够十几页卡片了。 */
  var SEARCH_PER_PAGE = 2000;

  /** 索引够多少张卡就不再翻第二次。 */
  var INDEX_ENOUGH = 120;

  var AUDIO_EXT = ['.mp3', '.m4a', '.flac', '.wav', '.ogg', '.opus', '.aac', '.wma'];
  var VIDEO_EXT = ['.mp4', '.mkv', '.webm', '.mov', '.avi', '.ts', '.m4v', '.flv', '.wmv'];

  /**
   * **伪装扩展名**。这个站把视频改名成 .jpg / .woff2 再挂出来 —— 实测 000.jpg
   * 和 000.woff2 都是 1080p 的 MPEG-TS（ffprobe 认成 mpegts，12.5 秒、1.2 Mbps），
   * 文件头就是 TS 的同步字节 0x47，一点没加密。目的通常是骗过 CDN 的缓存规则/
   * 扫描。对我们来说只是「按体积放行，不按后缀」。
   */
  var DISGUISE_EXT = ['.woff2', '.woff', '.ttf', '.otf', '.eot',
    '.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp'];

  /** 伪装文件的大小门槛：真图片一般几百 KB 以内，这里挂的视频都是 1.8~22MB。 */
  var DISGUISE_MIN_SIZE = 512 * 1024;

  var HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Linux; Android 14; 23113RKC6C) AppleWebKit/537.36 '
      + '(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36',
    'Accept': 'application/json, text/plain, */*',
    'Content-Type': 'application/json'
  };

  // ── id 编解码（对齐 OpenListAdapter.encodeId / decodeId）────────────
  //
  // ★ 中文不能直接 btoa：btoa 只接受 Latin-1，`btoa('/asmr2/野上菜月')` 会抛
  //   InvalidCharacterError。所以先 encodeURIComponent（把每个非 ASCII 字符变成
  //   %XX 的 ASCII 序列）再 btoa；解码时反过来先 atob 再 decodeURIComponent。
  //
  // Java 那边是直接拿 UTF-8 字节做 base64url。两条路的结果**字节内容不同**
  // （这里是百分号编码后的 ASCII，那边是原始 UTF-8），但都满足「同一份实现内
  // 往返一致、id 里没有斜杠和中文」这两条要求。跨实现交换 id 才需要对齐，
  // 而这个 id 只有这个源自己用。

  function encodeId(path) {
    var s = String(path == null ? '' : path);
    var b64;
    try {
      b64 = btoa(unescape(encodeURIComponent(s)));   // 等价于「UTF-8 字节 → base64」
    } catch (e) {
      // 极老的 WebView 没有 unescape 时退回「百分号编码再 btoa」，
      // 结果集同样是 ASCII，照样能往返。
      b64 = btoa(encodeURIComponent(s));
    }
    // URL-safe：+/ → -_，去掉尾部 =（Java 那边也是 withoutPadding）。
    return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  function decodeId(id) {
    var raw = String(id == null ? '' : id);
    if (!raw) return '';
    var b64 = raw.replace(/-/g, '+').replace(/_/g, '/');
    while (b64.length % 4 !== 0) b64 += '=';
    try {
      var bin = atob(b64);
      try {
        return decodeURIComponent(escape(bin));       // 还原 UTF-8（对应上面的 encodeURIComponent）
      } catch (e) {
        return bin;
      }
    } catch (e) {
      return raw;      // 不是 base64 就当成裸路径（调试时手敲的）
    }
  }

  // ── HTTP ──────────────────────────────────────────────────────────

  function originOf(args) {
    var base = String((args && (args.origin || args.api)) || '').trim();
    if (!base) base = DEFAULT_HOST;
    while (base.length > 0 && base.charAt(base.length - 1) === '/') {
      base = base.substring(0, base.length - 1);
    }
    return base;
  }

  /**
   * 一次 JSON POST + 校验 AList 的 {code,message,data} 信封。
   *
   * code != 200 一律当失败：AList 用 200 表示成功，400 是参数错、401 是要密码、
   * 404 是路径不存在。这些全都要抛出来，因为它们的修法完全不同
   * （改参数 / 填密码 / 换目录）—— 静默当成空目录是最难查的一种。
   */
  async function postData(ctx, base, path, body) {
    var url = base + path;
    var res;
    try {
      res = await ctx.call('net', {
        url: url,
        method: 'POST',
        format: 'json',
        headers: HEADERS,
        body: JSON.stringify(body || {})
      });
    } catch (e) {
      throw new Error('OpenList 请求失败 ' + path + '：' + ((e && e.message) || e)
        + '（这个源的接口只收 POST + application/json）');
    }
    // ── 挑战页要在「ok !== true」之前判 ──────────────────────────────
    //
    // AList 站被套在 Cloudflare 后面时，接口回的是 **403 + 一张 HTML 挑战页**
    // （实测 www.asmrgay.com/api/fs/search → 403 "Just a moment..."，768ms）。
    // 而 net 对非 2xx 直接抛 IOException、不给 Response —— 于是源这边看到的是
    // 「GET/POST 失败」或干脆 timeout，报出来像站点挂了。
    //
    // 真机上手会看到「OpenList 请求失败：timeout」，而真正原因是「这个站在盾后面，
    // 而 spider 没有浏览器的过盾能力」。这两件事的处置完全不同（换源 vs 换网），
    // 必须分开说。所以先看响应正文像不像挑战页。
    if (res && res.ok !== true && looksLikeChallenge(String(res.body == null ? '' : res.body))) {
      throw new Error('这个站要过 Cloudflare 验证才能访问（' + path + ' 拿到的是挑战页）。'
        + 'spider 版没有浏览器过盾能力：换一个没套盾的 OpenList 站，'
        + '或者先用浏览器打开这个站通过验证再试。');
    }
    if (!res || res.ok !== true) {
      throw new Error('OpenList 请求失败 ' + path + '：' + ((res && res.error) || '没有响应'));
    }
    var text = String(res.body == null ? '' : res.body);
    // 2xx 也可能拿到挑战页（有的 WAF 用 200 回挑战），所以这里再判一次。
    if (looksLikeChallenge(text)) {
      throw new Error('这个站要过 Cloudflare 验证才能访问（' + path
        + ' 回的是挑战页而不是 JSON）。spider 版没有过盾能力，换一个源。');
    }
    var json;
    try {
      json = JSON.parse(text);
    } catch (e) {
      throw new Error('OpenList 返回的不是 JSON（' + path + '）：' + head(text, 120)
        + '（可能填的不是 AList/OpenList 站，或者被 WAF 拦成了 HTML）');
    }
    var code = parseInt(json.code, 10);
    if (isNaN(code)) code = 0;
    if (code !== 200) {
      var msg = String(json.message || '');
      throw new Error('OpenList 返回 code=' + code + (msg ? (' ' + msg) : '')
        + '（' + path + '）。可能：这个目录要密码、路径不存在、或被站点限流。');
    }
    var data = json.data;
    if (!data || typeof data !== 'object') {
      throw new Error('OpenList 响应里没有 data（' + path + '）：' + head(text, 160));
    }
    return data;
  }

  // ── 路径工具（对齐 OpenListAdapter.join / depth / leaf / parentName）──

  function join(dir, name) {
    var base = String(dir == null ? '' : dir).trim();
    var n = String(name == null ? '' : name);
    if (base.length === 0 || base === '/') return '/' + n;
    if (base.charAt(base.length - 1) === '/') return base + n;
    return base + '/' + n;
  }

  /** 路径有几段（'/asmr2/CV/x' = 3）。 */
  function depth(path) {
    var p = String(path == null ? '' : path);
    if (!p) return 0;
    var parts = p.split('/');
    var n = 0;
    for (var i = 0; i < parts.length; i++) if (parts[i].trim().length > 0) n++;
    return n;
  }

  function leaf(path) {
    var p = String(path == null ? '' : path).replace(/\/+$/, '');
    var slash = p.lastIndexOf('/');
    return slash < 0 ? p : p.substring(slash + 1);
  }

  function parentName(path) {
    var p = String(path == null ? '' : path).replace(/\/+$/, '');
    var slash = p.lastIndexOf('/');
    if (slash <= 0) return '';
    return leaf(p.substring(0, slash));
  }

  function stripExt(name) {
    var n = String(name == null ? '' : name);
    var dot = n.lastIndexOf('.');
    return dot > 0 ? n.substring(0, dot) : n;
  }

  function endsWithAny(lower, list) {
    for (var i = 0; i < list.length; i++) if (lower.slice(-list[i].length) === list[i]) return true;
    return false;
  }

  function isVideo(name) {
    return endsWithAny(String(name == null ? '' : name).toLowerCase(), VIDEO_EXT);
  }

  function isMedia(name) {
    if (isVideo(name)) return true;
    return endsWithAny(String(name == null ? '' : name).toLowerCase(), AUDIO_EXT);
  }

  /** 后缀本身像图片/字体 —— 但这个站上它们多半是视频。 */
  function isDisguised(name) {
    return endsWithAny(String(name == null ? '' : name).toLowerCase(), DISGUISE_EXT);
  }

  function isDisguisedMedia(name, size) {
    return isDisguised(name) && Number(size || 0) >= DISGUISE_MIN_SIZE;
  }

  function head(text, n) {
    var s = String(text == null ? '' : text);
    return s.length <= n ? s : s.substring(0, n) + '…';
  }

  /**
   * 是不是 Cloudflare / WAF 的挑战页。
   *
   * 判据取 SiteFetchWebView.looksLikeChallenge 的语义，但那边是 Java、能看状态码，
   * 这边只有正文，所以放宽成「正文里的特征词」：
   *
   *   "Just a moment"        CF 的默认挑战标题
   *   "cf-chl" / "cf_chl"    CF 的挑战参数名
   *   "challenge-platform"   CF 的脚本域名
   *   "Attention Required"   CF 的拦截页标题
   *
   * 再加一条「很短且含 challenge」的兜底：有的站把正文裁得只剩一句提示。
   *
   * **不判 "Enable JavaScript"**：AList 的前端页本身就可能含这句，误判的代价是
   * 把一个正常站判成被盾挡了，那比漏判更难查。
   */
  function looksLikeChallenge(text) {
    var s = String(text == null ? '' : text);
    if (!s) return false;
    if (s.indexOf('Just a moment') >= 0) return true;
    if (s.indexOf('cf-chl') >= 0 || s.indexOf('cf_chl') >= 0) return true;
    if (s.indexOf('challenge-platform') >= 0) return true;
    if (s.indexOf('Attention Required') >= 0) return true;
    if (s.length < 12000 && s.toLowerCase().indexOf('challenge') >= 0) return true;
    return false;
  }

  function pageOf(page) {
    var n = parseInt(page, 10);
    return isNaN(n) || n < 1 ? 1 : n;
  }

  // ── 索引 ──────────────────────────────────────────────────────────

  function cardOf(path, name) {
    return {
      id: encodeId(path),
      title: name,
      cover: '',
      score: '',
      tags: [],
      description: '',
      category: parentName(path),
      episodeCount: 0,
      badge: '',
      ref: path              // 内部用：详情/播放要拿它去列目录
    };
  }

  /**
   * 某个目录下的全部卡片（作品），按路径去重。
   *
   * 为什么按**目录深度**认作品、不按名字认：第一版用 keywords="[RJ" 认作品，
   * 只对 dlsite 命名的站成立。实测 /asmr 底下是「中文音声 / 橙澄子汉化组…」这种**组**，
   * 里面的作品大多没有 RJ 号（`5Gothicc_heel/Barber shop gonewild.m4a`），
   * 于是那一整个 Tab 几乎是空的。
   *
   * 站点结构实测（2026-10-18）：
   *   /asmr/{组}/{作品}/[mp3版/]*.m4a        ← 作品在第 3 段
   *   /asmr2/{CV}/{作品}/mp3版/*.mp3         ← 作品在第 3 段
   *   /asmr4/{作者}/{年}/{日期}/*.woff2      ← 第 3 段是「年」，详情页再往下钻
   *   /asmr6/{组}/{子组}/{dir}/*.jpg         ← 图片站，没有可播文件
   * 三个能播的形状里第 3 段都是「点进去能出东西」的那一层，所以卡片 = 第 3 段目录。
   */
  async function cardIndex(ctx, base, parent, keyword) {
    var out = [];
    var seen = {};
    for (var searchPage = 1; searchPage <= 2 && out.length < INDEX_ENOUGH; searchPage++) {
      var data = await postData(ctx, base, '/api/fs/search', {
        parent: parent,
        keywords: keyword || '',
        scope: 1,                       // 只要目录
        page: searchPage,
        per_page: SEARCH_PER_PAGE,
        password: ''
      });
      var content = data.content;
      if (!content || typeof content.length !== 'number' || content.length === 0) break;

      for (var i = 0; i < content.length; i++) {
        var entry = content[i];
        if (!entry || !entry.is_dir) continue;
        var name = String(entry.name || '');
        if (!name) continue;
        var full = join(String(entry.parent || parent), name);
        if (depth(full) !== WORK_DEPTH) continue;
        if (seen[full]) continue;
        seen[full] = 1;
        out.push(cardOf(full, name));
      }
      if (content.length < SEARCH_PER_PAGE) break;
    }
    return out;
  }

  /** 索引切片 + 分页。索引是整批捞的，所以页数算得出来。 */
  function pageOf_Index(all, page) {
    var skip = (Math.max(1, page) - 1) * PER_PAGE;
    var items = [];
    for (var i = skip; i < all.length && items.length < PER_PAGE; i++) items.push(all[i]);
    return {
      items: items,
      page: page,
      hasMore: skip + items.length < all.length
    };
  }

  // ── 详情：递归捞可播文件（对齐 OpenListAdapter.collect）────────────

  /**
   * fs/list → content 数组。目录不存在 / 要密码都返回 null，不抛 ——
   * 递归时某个子目录挂了不该让整个详情页失败。
   */
  async function listEntries(ctx, base, path) {
    try {
      var data = await postData(ctx, base, '/api/fs/list', {
        path: path,
        password: '',
        page: 1,
        per_page: 200,
        refresh: false
      });
      return data.content || [];
    } catch (e) {
      ctx.call('log', { msg: 'openlist 列目录失败 ' + path + '：' + ((e && e.message) || e) });
      return null;
    }
  }

  /**
   * 递归两层采集文件。
   *
   * 递归两层的理由：作品目录下面是 `mp3版/`、`wav版/`、`イラスト/` 这种子目录，
   * 真正的文件在第三层。只认媒体扩展名 + 大体积伪装文件，图片和 txt 自动被滤掉。
   */
  /**
   * 递归采集。
   *
   * ★ 三个去重/防跑偏的守卫，缺一个都会出真故障：
   *   ① `visited` 记已钻过的目录。AList 的 fs/list 对「路径不存在」不一定报错，
   *      有些反向代理会把任何路径都回成根目录的内容 —— 那样 list('x') 里又有一个
   *      叫 x 的子目录，顺着就能无限往下钻，或者把同一批文件收成好几份
   *      （实测合成数据就能复现：同一个 x.mp3 出现三条，path 完全相同）。
   *   ② `seen` 按 **完整路径** 去重。同一个文件可能被两条不同路径指到
   *      （软链/挂载点），选集里出现两条一模一样的条目，用户点了才发现是同一条。
   *   ③ budget 同时卡文件数和目录数：防一个畸形目录把请求打成雪崩。
   */
  async function collect(ctx, base, dir, out, seen, visited, level, budget) {
    if (level > 2 || budget.files <= 0 || budget.dirs <= 0) return;
    if (visited[dir]) return;                 // ① 这个目录已经钻过
    visited[dir] = 1;

    var entries = await listEntries(ctx, base, dir);
    if (!entries) return;
    for (var i = 0; i < entries.length; i++) {
      var entry = entries[i];
      if (!entry) continue;
      var name = String(entry.name || '');
      if (!name) continue;
      if (name.charAt(0) === '.' || name.indexOf('__MACOSX') >= 0) continue;

      var full = join(dir, name);
      if (entry.is_dir) {
        // 只往下钻两层，别把 イラスト / 画像 也爬一遍
        if (level < 2 && budget.dirs > 0) {
          budget.dirs--;
          await collect(ctx, base, full, out, seen, visited, level + 1, budget);
        }
        continue;
      }
      var size = Number(entry.size || 0);
      var disguised = false;
      if (!isMedia(name)) {
        if (!isDisguisedMedia(name, size)) continue;
        disguised = true;
      }
      if (seen[full]) continue;               // ② 同一个文件只收一次
      seen[full] = 1;
      if (budget.files <= 0) return;
      budget.files--;

      out.push({
        no: out.length + 1,
        title: stripExt(name) + (disguised ? '（伪装视频）' : ''),
        line: 'default',
        ref: String(out.length + 1),
        episodeNumber: out.length + 1,
        duration: '',
        // 内部用：播放时拿它换直链。前端不看（model.js 不会转出这个键）。
        path: full,
        disguised: disguised
      });
    }
  }

  async function episodesOf(ctx, base, path) {
    var out = [];
    var budget = { files: MAX_FILES, dirs: MAX_DIRS };
    var seen = {};        // 完整路径 → 已收，防同一个文件被两条路径各收一次
    var visited = {};     // 已钻过的目录，防同一批内容被收成好几份
    await collect(ctx, base, path, out, seen, visited, 0, budget);
    return out;
  }

  /** 目录的 readme 当简介。Markdown 剥掉，截 300 字。 */
  async function readmeOf(ctx, base, path) {
    try {
      var data = await postData(ctx, base, '/api/fs/list', {
        path: path, password: '', page: 1, per_page: 1, refresh: false
      });
      var readme = String(data.readme || '');
      if (!readme.trim()) return '';
      var text = readme
        .replace(/^#+\s*/gm, '')
        .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
        .replace(/[*_`>]/g, '')
        .replace(/\s+/g, ' ')
        .trim();
      if (text.length > 300) text = text.substring(0, 300) + '…';
      return text;
    } catch (e) {
      return '';
    }
  }

  // ── 能力 ──────────────────────────────────────────────────────────

  __spiderSite = {
    id: 'openlist',
    name: 'OpenList',
    version: 1,
    requires: '>=1',
    // net 白名单：站点域 + 直链 CDN。raw_url 落在另一个域名上（实测
    // asmr.121231234.xyz），播放器要能直连 —— 但**这个域名每站不同**，
    // 用户换站时要在这里补一条。DEFAULT_HOST 和在用站点都列上。
    hosts: ['www.asmrgay.com', 'asmrgay.com', 'asmr.121231234.xyz'],

    /**
     * 首页 = 全站作品索引第 1 页。
     * Java 那边切「前 6 进 featured、其余进 latest」，spider 只给一个平铺 items。
     */
    async home(ctx, args) {
      var base = originOf(args);
      var all = await cardIndex(ctx, base, '/', '');
      if (all.length === 0) {
        throw new Error('OpenList 首页没找到任何作品目录（' + base + '）。'
          + '可能：站点根目录结构变了（作品不再在第 ' + WORK_DEPTH + ' 段）、'
          + '搜到的全是父目录被过滤掉了、或者这个地址不是 AList/OpenList 站。');
      }
      var paged = pageOf_Index(all, 1);
      ctx.call('log', { msg: 'openlist 索引 ' + base + '：' + all.length + ' 张卡' });
      return paged;
    },

    /**
     * 分类 = 某个大目录下的作品索引，本地切片翻页。
     * args.cate / args.slug → 目录路径（'/asmr2'）或已编码 id，两种都收。
     */
    async category(ctx, args) {
      args = args || {};
      var base = originOf(args);
      var raw = String(args.cate != null ? args.cate : (args.slug != null ? args.slug : ''));
      if (!raw) {
        // 没给分类就等于首页那页 —— 比报错好用，而且和 Java 的 listing('') 一致。
        var all0 = await cardIndex(ctx, base, '/', '');
        return pageOf_Index(all0, pageOf(args.page));
      }
      var dir = decodeId(raw);
      if (dir.charAt(0) !== '/') dir = '/' + dir;
      var page = pageOf(args.page);
      var all = await cardIndex(ctx, base, dir, '');
      return pageOf_Index(all, page);
    },

    /** 搜索 = 全站作品索引 + 用户关键词（同一个 fs/search 接口）。 */
    async search(ctx, args) {
      args = args || {};
      var base = originOf(args);
      var keyword = String(args.q || args.keyword || '');
      var page = pageOf(args.page);
      if (!keyword) return { items: [], page: 1, hasMore: false };
      var all = await cardIndex(ctx, base, '/', keyword);
      return pageOf_Index(all, page);
    },

    async detail(ctx, args) {
      args = args || {};
      var base = originOf(args);
      var raw = String((args.ref || args.id) || '').trim();
      if (!raw) throw new Error('OpenList 详情缺少 ref（作品目录的 id）');
      var path = decodeId(raw);
      if (path.charAt(0) !== '/') path = '/' + path;

      var episodes = await episodesOf(ctx, base, path);
      if (episodes.length === 0) {
        throw new Error('这个目录里没有能播的文件（' + path + '）。'
          + '可能：只有图片和说明、目录名选错了一层、或者子目录超过两层没钻到底。');
      }
      var description = await readmeOf(ctx, base, path);

      return {
        id: encodeId(path),
        title: leaf(path),
        cover: '',
        description: description,
        duration: '',
        category: parentName(path),
        tags: [],
        episodes: episodes,
        episodeCount: episodes.length,
        lines: [{ line: 'default', name: '原文件' }],
        related: [],
        extra: { files: episodes.length }
      };
    },

    /**
     * 播放 = fs/get 换直链。地址**每次现取**（AList 的 raw_url 带签名，会过期）。
     *
     * episodes 里带 `path`（内部用，前端不看），这里直接拿它去换 raw_url。
     * ep 是第几集（1 起）；超范围就夹到最后一集，和 Java 行为一致。
     */
    async play(ctx, args) {
      args = args || {};
      var base = originOf(args);
      var ref = String((args.ref || args.id) || '').trim();
      if (!ref) throw new Error('OpenList 播放缺少 ref');
      // ref 支持 'id:集号' 和纯 id（集号从 args.ep 取）。
      var raw = ref;
      var epNo = 0;
      var colon = ref.lastIndexOf(':');
      if (colon > 0) {
        raw = ref.substring(0, colon);
        epNo = parseInt(ref.substring(colon + 1), 10);
      }
      if (!epNo) epNo = parseInt(args.ep || args.no || 1, 10);
      if (isNaN(epNo) || epNo < 1) epNo = 1;

      var path = decodeId(raw);
      if (path.charAt(0) !== '/') path = '/' + path;

      var episodes = await episodesOf(ctx, base, path);
      if (episodes.length === 0) {
        throw new Error('这个目录里没有能播的文件（' + path + '）。可能只有图片和说明。');
      }
      if (epNo > episodes.length) epNo = episodes.length;

      var entry = episodes[epNo - 1];
      var filePath = String(entry.path || '');
      var data = await postData(ctx, base, '/api/fs/get', { path: filePath, password: '' });
      var rawUrl = String(data.raw_url || '');
      if (!rawUrl) {
        throw new Error('站点没给这个文件的直链（' + filePath + '）。'
          + '可能：这个目录要密码、文件被移走了、或者对方关掉了「直接下载」。');
      }

      var label = isVideo(filePath) ? '原文件（视频）' : '原文件（音频）';
      return {
        url: rawUrl,
        source: rawUrl,
        alts: [{ line: 'default', name: label, url: rawUrl }],
        episodeNumber: epNo,
        title: entry.title || ''
      };
    }
  };
})();
