// 星空影视（www.dozagonza.com）—— spider 版。
//
// ── 形态：苹果CMS + 自定义模板，站前挂 Cloudflare 挑战 ──────────────────
// `maccms` 变量、`player_aaaa`、`/static/js/playerconfig.js` 三处都是苹果CMS
// 的原样特征，但 URL 结构和标准苹果CMS 完全不同（不是 /voddetail/xxx.html，
// 而是 /video/275134247.html），模板目录 `template/y_md_3`。
//
// 裸 curl 拿首页回 403 + `cf-mitigated: challenge`，所以配置里必须有
// `web.shield`，抓取交给真浏览器内核过盾。
//
// ── URL 结构 ────────────────────────────────────────────────────────
//   首页      /
//   分类      /tv/<tid>.html          tid: 1电影 2电视剧 3短剧 4动漫 5综艺
//   分类翻页  /tv/<tid>/page/<n>.html  ← 每页 42 条
//   搜索      /search.html?wd=<kw>
//   详情      /video/<id>.html
//   播放      /play/<id>/<sid>/<nid>.html
//
// **分页只有一种写法**：`/tv/1/page/2.html`。试过 `/tv/1-2.html`，它对 n≥2
// 全部返回同一份内容（第 2 页），看起来像分页生效了 — 其实是路由把 `-2`
// 当成了 tid 的一部分然后回落到默认页。判「还有下一页」用分页控件里的
// `title="下一页"`，别数卡片数量。
//
// ── 卡片 ────────────────────────────────────────────────────────────
//   <li class="item">
//     <div class="item-con">
//       <span class="state"><em>悬疑</em></span>              ← 分类标签
//       <a href="/video/275134247.html" class="thumb" title="势在必行">
//         <img class="lazyload" src="…/load.gif" data-original="https://img.dozagonza.com/…jpg">
//         <span class="hot">已完结</span>                      ← 更新状态
//       </a>
//       <h5 class="subject"><a href="/video/275134247.html"><b>势在必行</b></a></h5>
//     </div>
//   </li>
//
// 封面只在 `data-original` 里 —— `src` 是模板自带的转圈 gif。
// 图床有两个路径：`upload/vod/` 和 `upload/img/`，两种都在 `img.dozagonza.com`。
//
// ── 播放：内嵌 player_aaaa，直给 m3u8 ───────────────────────────────
// 播放页 HTML 里就一段：
//   var player_aaaa={"flag":"play","encrypt":0,…,
//     "url":"https://play.hhuus.com/play/lejBBoPe/index.m3u8",…}
// `encrypt` 恒为 0，`url` 就是媒体列表，不用二次解析、不用算签名。
//
// ── 片源是「CDN 域名池」，别指望穷举 ────────────────────────────────
// 抽 45 部片子，拿到 26 个不同主机：v1..v12.ppqrrs.com、v1..v6.qrssuv.com、
// fengbao9/11/12/14.com、vip1.lz-cdn*.com、play.hhuus.com、m3u8.hmrvideo.com……
// 编号还会继续涨。所以：
//   · `hosts` 只声明站点本体；
//   · 配置里的 `http.hosts` 列已知的给 JS 播放器兜底；
//   · **VLC 那条路不受 http.hosts 管**（它只问 NetRoute.mustProxy），
//     池子里冒出没列过的域名也能播 —— 这是实测过的，别照 51cg 的注释去理解。
//
// ── 加密：AES-128，key 是相对路径 ───────────────────────────────────
//   #EXT-X-KEY:METHOD=AES-128,URI="enc.key",IV=0x00000000000000000000000000000000
// `enc.key` 相对 `index.m3u8` 解析，实测裸取 200（16 字节），不要 Referer。
// 但这个「相对」在绕中继时会坏：VLC 拿它对着 `http://127.0.0.1:PORT/s?u=…`
// 解析，query 丢掉落到 `/enc.key`，中继只认 `/s?u=`，回 404，表现是能开播
// 但一直黑屏。VlcRelay.rewriteTagUris 就是为这个加的（标签行里的 URI 也改写）。
(function () {
  var BASE = 'https://www.dozagonza.com';

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
      throw new Error('星空影视 请求失败 ' + url + '：' + ((e && e.message) || e));
    }
    if (!res || res.ok !== true) {
      throw new Error('星空影视 请求失败 ' + url + '：' + ((res && res.error) || '没有响应'));
    }
    var html = String(res.body == null ? '' : res.body);
    if (html.length === 0) throw new Error('星空影视 返回空页面：' + url);
    // 盾没过去的话拿到的是挑战页，早点报清楚，别让下游以为「解析改版了」。
    if (html.indexOf('Just a moment') >= 0 || html.indexOf('cf-mitigated') >= 0) {
      throw new Error('星空影视 被 Cloudflare 挑战页挡住：' + url
        + ' —— 检查配置里的 web.shield 是否生效');
    }
    return html;
  }

  // ── 列表解析 ────────────────────────────────────────────────────────

  /**
   * 卡片锚点。
   *
   * 首页/分类是 `<li class="item">`，搜索页是 `<li class="item mb15 card-wrap">`
   * —— 多两个类名。写死 `class="item"` 的话搜索页一个锚点都切不出来，
   * items 恒为 0（2026-10-21 真机：cap=search ok 但 itemCount=0）。
   * 所以只认前缀，后面必须是空格或引号。
   */
  var RE_ITEM = /<li class="item(?=[ "])/g;
  var RE_VIDEO_ID = /\/video\/(\d+)\.html/;
  var RE_THUMB_TITLE = /class="thumb"[^>]*title="([^"]*)"/;
  var RE_COVER = /data-original="([^"]+)"/;
  var RE_HOT = /<span class="hot">([^<]*)<\/span>/;
  var RE_STATE = /<span class="state"><em>([^<]*)<\/em><\/span>/;
  var RE_BOLD = /<b>([\s\S]*?)<\/b>/;
  /** 搜索页的标题容器；首页同样有它，所以两种页型可以走同一条路。 */
  var RE_SUBJECT = /<h5 class="subject[^"]*">([\s\S]*?)<\/h5>/;
  /** 片名后面挂的评分（`9.0分`），不剥会粘成「嫁金钗9.0分」。 */
  var RE_SCORE = /<span class="douban_score">[\s\S]*?<\/span>/;
  /** 搜索页没有 .hot，更新状态写在 `<p>资源：更新第10集</p>` 里。 */
  var RE_RES = /资源：([^<]{1,24})/;

  /**
   * 一页 HTML → 卡片数组。
   *
   * 用 `<li class="item` 当锚点切，每段到下一个锚点为止。别用
   * `/<li class="item[^>]*>([\s\S]*?)<\/li>/g` —— 卡片里可能有嵌套 li
   *（标签列表），那样会在第一个内层 `</li>` 就截断。
   */
  function parseCards(html) {
    var out = [];
    var marks = [];
    var re = new RegExp(RE_ITEM.source, 'g');
    var m;
    while ((m = re.exec(html)) !== null) marks.push(m.index);

    for (var i = 0; i < marks.length; i++) {
      var from = marks[i];
      var to = i + 1 < marks.length ? marks[i + 1] : Math.min(html.length, from + 6000);
      var blk = html.slice(from, to);

      var idm = RE_VIDEO_ID.exec(blk);
      if (!idm) continue;

      // 标题取值三条路，优先级从高到低：
      //   ① <h5 class="subject">：首页和搜索页都有，是最稳的一条；
      //   ② class="thumb" 的 title 属性：首页模板有，搜索页没有；
      //   ③ 裸 <b>：兜底。
      //
      // ① 里有两个坑，都是 search 页独有的：
      //   · 关键词被站点高亮成 `<b><b style="color:red;">嫁</b>金钗</b>` —— 嵌套 b，
      //     所以**不能**用 `/<b>([\s\S]*?)<\/b>/` 去抠，非贪婪只吃到「嫁」；
      //   · 片名后面跟着 `<span class="douban_score">9.0分</span>`，不剥会变成
      //     「嫁金钗9.0分」。先整块删 span 再剥标签。
      var sm = RE_SUBJECT.exec(blk);
      var tm = RE_THUMB_TITLE.exec(blk);
      var bm = RE_BOLD.exec(blk);
      var title = '';
      if (sm) title = clean(decodeEntities(stripTags(sm[1].replace(RE_SCORE, ''))));
      if (!title && tm) title = clean(decodeEntities(tm[1]));
      if (!title && bm) title = clean(decodeEntities(stripTags(bm[1])));
      if (!title) continue;

      var cm = RE_COVER.exec(blk);
      var hm = RE_HOT.exec(blk);
      var rm = RE_RES.exec(blk);
      var sm2 = RE_STATE.exec(blk);

      // 首页写 <span class="hot">已完结</span>，搜索页写 <p>资源：更新第10集</p>。
      var hot = hm ? clean(decodeEntities(hm[1]))
                   : (rm ? clean(decodeEntities(rm[1])) : '');
      out.push({
        id: idm[1],
        title: title,
        cover: cm ? decodeEntities(cm[1]) : '',
        // 卡片右下角那行小字。**必须叫 playAt** —— 前端 model.js 只读这个键
        //（来源是 adaptVideo 那套老算法：'共 N 集' → badge → playAt）。
        // 自己起名 note 的话不报错，卡片小字就是空的。
        playAt: hot,
        note: hot,
        // 分类角标：首页 `<span class="state"><em>悬疑</em></span>`。
        // 搜索页没有这个容器，所以这里的 tags 是空数组 —— 别拿 sm（标题容器）顶。
        tags: sm2 ? [clean(decodeEntities(sm2[1]))] : []
      });
    }
    return out;
  }

  /** 分页控件里 `title="下一页"` 就是唯一可靠的「还有下一页」。 */
  function hasNext(html) {
    return /title="下一页"/.test(String(html || ''));
  }

  function listResult(html, page) {
    return { items: parseCards(html), page: page, hasMore: hasNext(html) };
  }

  // ── 详情解析 ────────────────────────────────────────────────────────

  function titleOf(html) {
    var m = /<h1[^>]*>([\s\S]*?)<\/h1>/.exec(html);
    if (m) {
      var t = clean(decodeEntities(stripTags(m[1])));
      if (t) return t;
    }
    var og = /<meta property="og:title" content="([^"]*)"/.exec(html);
    return og ? clean(decodeEntities(og[1])) : '';
  }

  /** 详情页封面是 og:image，真的不是占位图（这点和 51cg 相反）。 */
  function coverOf(html) {
    var og = /<meta property="og:image" content="([^"]+)"/.exec(html);
    if (og && /img\.dozagonza\.com/.test(og[1])) return decodeEntities(og[1]);
    var m = /https:\/\/img\.dozagonza\.com\/upload\/(?:vod|img)\/[^"'\s)]+\.(?:jpg|jpeg|png|webp)/i.exec(html);
    return m ? m[0] : '';
  }

  function descOf(html) {
    var m = /<div class="summary-con"[^>]*>([\s\S]*?)<\/div>/.exec(html);
    if (m) {
      var t = clean(decodeEntities(stripTags(m[1])));
      if (t) return t;
    }
    var d = /<meta name="description" content="([^"]*)"/.exec(html);
    return d ? clean(decodeEntities(d[1])) : '';
  }

  /**
   * 元信息字段。
   *
   * 详情页是 `<p class="director"><label>导演：</label><span>王斯阳</span></p>`
   * 这种结构。**不能**先 stripTags 再正则 `导演：(...)` —— 剥完是
   * `分类：国产剧导演：王斯阳主演：周冬雨…`，一整串黏在一起，正则会把
   * 「王斯阳主演：」当成导演名吃进去。按 class 取 span 才稳。
   */
  function fieldOf(html, cls) {
    var re = new RegExp('<p class="' + cls + '"><label>[^<]*</label><span>([\\s\\S]*?)</span>');
    var m = re.exec(html);
    return m ? clean(decodeEntities(stripTags(m[1]))) : '';
  }

  function infoOf(html) {
    return {
      '分类': fieldOf(html, 'types'),
      '导演': fieldOf(html, 'director'),
      '主演': fieldOf(html, 'performer'),
      '地区': fieldOf(html, 'country')
    };
  }

  /**
   * 剧集列表。
   *
   * 只收 `href="/play/<当前id>/…"` 的链接 —— 详情页底部还有一大堆推荐位的
   * `/video/…`，混进来会把剧集数量算错。
   *
   * **按 sid 分组，取集数最多的那一条线路**（并列时取 sid 小的）。
   *
   * 2026-10-23 修：原来取的是 sid 最小的那组，而 play 里把 sid 写死成 1。
   * 嫁金钗（275189709）有 sid=3 和 sid=8 两条线路、各 15 集，取到的 sid 是 3，
   * 真正请求的却是 /play/<id>/1/<nid>.html —— 站点对不存在的线路一律回「第 1 集」
   * 的播放页，症状就是「点第 15 集、播第 1 集」，而且每一集都这样。
   */
  function episodesOf(html, id) {
    var bySid = {};
    var order = [];
    var re = new RegExp('/play/' + id + '/(\\d+)/(\\d+)\\.html', 'g');
    var m;
    while ((m = re.exec(html)) !== null) {
      var sid = Number(m[1]);
      var nid = Number(m[2]);
      if (!bySid[sid]) { bySid[sid] = []; order.push(sid); }
      var arr = bySid[sid];
      var dup = false;
      for (var i = 0; i < arr.length; i++) if (arr[i].nid === nid) { dup = true; break; }
      if (!dup) arr.push({ sid: sid, nid: nid });
    }
    if (!order.length) return [];

    var best = order[0];
    for (var k = 1; k < order.length; k++) {
      var cand = order[k];
      if (bySid[cand].length > bySid[best].length
          || (bySid[cand].length === bySid[best].length && cand < best)) {
        best = cand;
      }
    }
    var out = bySid[best].slice();
    out.sort(function (a, b) { return a.nid - b.nid; });
    return out;
  }

  /**
   * 拆 ref。三种形态都要认：
   *   · `<id>`             —— 只有片子
   *   · `<id>:<nid>`       —— 旧写法（2026-10-23 之前存的详情缓存）
   *   · `<id>:<sid>:<nid>` —— 现在 detail 给的那一份，带线路
   * 深链直接进播放器时会拿到 `<id>:ep<N>`，拆出来 sid/nid 都是 NaN，交给调用方兜底。
   */
  function refParts(ref) {
    var raw = String(ref == null ? '' : ref).trim();
    var p = raw.split(':');
    var id = p[0] || '';
    var sid = p.length >= 3 ? Number(p[1]) : 0;
    var nid = p.length >= 3 ? Number(p[2]) : (p.length === 2 ? Number(p[1]) : 0);
    return {
      id: id,
      sid: isFinite(sid) && sid > 0 ? sid : 0,
      nid: isFinite(nid) && nid > 0 ? nid : 0
    };
  }

  function idOf(ref) { return refParts(ref).id; }

  function pageUrl(kind, key, page) {
    if (kind === 'category') {
      return BASE + '/tv/' + enc(key) + (page <= 1 ? '.html' : ('/page/' + page + '.html'));
    }
    return BASE + '/';
  }

  // ── 能力 ────────────────────────────────────────────────────────────

  /** 首页抽出来单独一个函数：category 里不能写 `this.home(ctx)`。见 51cg 的注释。 */
  async function homePage(ctx) {
    var html = await fetchHtml(ctx, BASE + '/');
    var out = listResult(html, 1);
    if (!out.items.length) {
      throw new Error('星空影视 首页没解析到卡片（' + html.length + ' 字节）'
        + ' —— 多半是模板改版，li.item / .thumb 结构变了');
    }
    return out;
  }

  __spiderSite = {
    id: 'dozagonza',
    name: '星空影视',
    version: 1,
    requires: '>=1',
    // net 的白名单是**精确相等**（SpiderRuntime.allowed 里就是 host.equals），
    // 不是后缀匹配 —— 写 `dozagonza.com` 挡不住 `www.dozagonza.com`，真机上
    // 报「net 拒绝：主机不在白名单」，而界面上只看得到一句「源站响应太慢」。
    // 这里两个都写。注意这和 web.shield.hosts 的语义不同：那个是后缀匹配。
    hosts: ['www.dozagonza.com', 'dozagonza.com'],

    async home(ctx) {
      return homePage(ctx);
    },

    async category(ctx, args) {
      args = args || {};
      var slug = String(args.slug || '').trim();
      var page = Math.max(1, Number(args.page) || 1);
      if (!slug) return homePage(ctx);
      var html = await fetchHtml(ctx, pageUrl('category', slug, page));
      var out = listResult(html, page);
      if (!out.items.length) {
        throw new Error('星空影视 分类页没解析到卡片（' + slug + ' 第 ' + page + ' 页，'
          + html.length + ' 字节）');
      }
      return out;
    },

    async search(ctx, args) {
      args = args || {};
      var kw = String(args.keyword || '').trim();
      var page = Math.max(1, Number(args.page) || 1);
      if (!kw) return { items: [], page: page, hasMore: false };
      var html = await fetchHtml(ctx, BASE + '/search.html?wd=' + enc(kw));
      var out = listResult(html, page);
      // 搜索页的分页表单没实测过，先只认第一页 —— 宁可少翻，也别拼一个
      // 会回落到首页模板的地址出来（那样第二页会显示一堆无关片子）。
      out.hasMore = false;
      return out;
    },

    async detail(ctx, args) {
      args = args || {};
      var id = idOf(args.ref || args.id);
      if (!id) throw new Error('星空影视 详情缺少 id');

      var html = await fetchHtml(ctx, BASE + '/video/' + enc(id) + '.html');
      var title = titleOf(html);
      if (!title) {
        throw new Error('星空影视 详情没解析到标题（id=' + id + '，' + html.length
          + ' 字节）—— 片子可能已下架');
      }

      var eps = episodesOf(html, id);
      var episodes = [];
      for (var i = 0; i < eps.length; i++) {
        episodes.push({
          no: eps[i].nid,
          ref: id + ':' + eps[i].sid + ':' + eps[i].nid,
          // 单片（电影）只有一集，叫「正片」比「第01集」像人话。
          title: eps.length === 1
            ? '正片'
            : ('第' + (eps[i].nid < 10 ? '0' : '') + eps[i].nid + '集')
        });
      }
      if (!episodes.length) {
        // 电影这种单片：详情页仍然有「立即播放」链到 /play/<id>/1/1.html。
        episodes.push({ no: 1, ref: id + ':1', title: '正片' });
      }

      var info = infoOf(html);
      var tags = [];
      // 详情页那个 `<label class="state">` 是空的，分类只能从 p.types 里取。
      if (info['分类']) tags.push(info['分类']);
      if (info['地区']) tags.push(info['地区']);

      return {
        id: id,
        title: title,
        cover: coverOf(html),
        description: descOf(html),
        tags: tags,
        episodeCount: episodes.length,
        episodes: episodes,
        lines: [{ line: 'default', name: '高清线路' }]
      };
    },

    /**
     * 播放：播放页内嵌 player_aaaa，`url` 就是媒体列表 m3u8。
     *
     * 不解析 `MacPlayerConfig` —— 那是站点自己的播放器配置，`parse` 字段
     * 只在 from 是第三方解析站时才有意义，这里的 from 恒为 dplayer。
     */
    async play(ctx, args) {
      args = args || {};
      var r = refParts(args.ref || args.id);
      var id = r.id;
      if (!id) throw new Error('星空影视 播放缺少 id');
      // nid 优先取 ref 里的（那一份是详情页真实链接里的集号），退到 args.ep。
      var nid = r.nid || Math.max(1, Number(args.ep) || 1);
      // sid 同理：ref 里带就用，深链没有就猜 1 —— 猜错时站点会回第 1 集，
      // 至少不是 404；要修就得让前端一直带着 detail 的 ref（现在是这样）。
      var sid = r.sid;
      if (!sid) {
        // 旧缓存里的 ref 是 `<id>:<集号>`（2026-10-23 之前存的详情），没有线路号。
        // 猜 1 在单线路的站上碰巧对，在多线路的站上必然回第 1 集（嫁金钗就是），
        // 所以宁可多抓一次详情页，把线路找出来。
        var page = await fetchHtml(ctx, BASE + '/video/' + enc(id) + '.html');
        var eps = episodesOf(page, id);
        for (var i = 0; i < eps.length; i++) {
          if (eps[i].nid === nid) { sid = eps[i].sid; break; }
        }
        if (!sid && eps.length) sid = eps[0].sid;
        sid = sid || 1;
      }

      var html = await fetchHtml(ctx, BASE + '/play/' + enc(id) + '/' + sid + '/' + nid + '.html');
      var m = /player_aaaa\s*=\s*(\{[\s\S]*?\})\s*<\/script>/.exec(html);
      if (!m) {
        throw new Error('星空影视 播放页没有 player_aaaa（id=' + id + ' 第 ' + nid
          + ' 集，' + html.length + ' 字节）—— 可能这一集没有片源');
      }

      var cfg;
      try {
        cfg = JSON.parse(m[1]);
      } catch (e) {
        throw new Error('星空影视 player_aaaa 不是合法 JSON（id=' + id + '）');
      }

      var url = String(cfg.url || '').trim();
      if (!url) {
        return { reason: 'no-source', note: '这一集没有解析到可播地址' };
      }
      if (Number(cfg.encrypt) !== 0) {
        // 苹果CMS 的 encrypt=1 是站点自己那套 url 加密，这里还没见过。
        return { reason: 'encrypted', note: '片源被站点加密（encrypt=' + cfg.encrypt + '），需要额外算法' };
      }

      return {
        url: url,
        source: url,
        alts: [{ line: 'default', name: '高清线路', url: url }],
        episodeNumber: nid,
        note: 'HLS 走 AES-128，密钥是清单里的相对路径 enc.key（裸取 200，不要 Referer）'
      };
    }
  };
})();
