package com.huangguo.mobile;

import static com.huangguo.mobile.UserSource.log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;

/**
 * MissAV 的 {@code /api/*} 分流。
 *
 * 这一层<b>只做两件事</b>：把内部动作路径翻成站点真实地址，再把 {@link MissavParser}
 * 解析出来的结果套进我们内部约定的信封。<b>它自己不发任何 HTTP 请求</b> ——
 * 抓取一律走 {@link SiteFetchWebView}（Cloudflare 挡 okhttp，见 {@link SiteFetchWebView}
 * 顶部那段实测记录）。
 *
 * ── 动作映射 ──────────────────────────────────────────────────────────
 *   /api/home                       → /dm539/cn/new
 *   /api/category?slug=&page=      → /dm{cateId}/cn/{slug}   cateId 就是 dm96 里的数字
 *   /api/search?q=&page=           → /cn/search?q=&page=
 *   /api/detail/{id}               → /cn/{id}
 *   /api/play/{id}                 → /cn/{id}（从详情页那段 packed JS 里解 m3u8）
 *   /api/genres?page=              → /cn/genres?page=
 *
 * 注意 category 和 tag-list 是两个入口、同一份数据：前者是底部导航那排胶囊，
 * 后者是「全部类型」页。前端不会把 slug 传错 —— 两者各自只能拿到自己的参数集。
 */
final class MissavAdapter {

    /** 「全部类型」抓前几页。见 {@link #genres} 里那段取舍说明。 */
    private static final int GENRE_PAGES = 3;

    private MissavAdapter() {}

    /**
     * 首页那排胶囊。
     *
     * 站点首页只有「最近更新」一排，没有分区推荐。所以首页返回 featured（也用最近更新，
     * 取前 8）+ latest（全量 12）+ cate_rec（空）。
     *
     * **featured 和 latest 内容相同是有意的**：站点的「精选」在页脚那个
     * {@code /cn/release}（新作上市）里，那是一个独立列表而不是首页的一部分。
     * 宁可两行都显示最近更新，也不要 featured 那一行空着 —— 空行比重复行更像故障。
     * adaptHome 读 cate_rec 做去重拼接，这里留空数组正好走 featured 那份。
     */
    private static final int FEATURED_LIMIT = 8;

    static byte[] fetch(String path, SiteProfile profile) throws Exception {
        String base = base(profile);
        JSONObject result;

        if (path.startsWith("/api/home")) {
            result = home(base);
        } else if (path.startsWith("/api/genres")) {
            result = genres(base, page(path));
        } else if (path.startsWith("/api/reconcile")) {
            result = reconcile(param(path, "ids"));
        } else if (path.startsWith("/api/search")) {
            result = search(base, param(path, "q"), page(path));
        } else if (path.startsWith("/api/category")) {
            result = category(base, param(path, "slug"), page(path));
        } else if (path.startsWith("/api/detail")) {
            result = detail(base, idFrom(path));
        } else if (path.startsWith("/api/play")) {
            result = play(base, idFrom(path));
        } else {
            throw new Exception("missav 适配器不认识的路径：" + path);
        }

        JSONObject envelope = new JSONObject();
        envelope.put("data", result);
        return envelope.toString().getBytes("UTF-8");
    }

    // ── 各个动作 ──────────────────────────────────────────────────────

    private static JSONObject home(String base) throws Exception {
        JSONArray latest = MissavParser.parseList(get(base + "/dm539/cn/new"));
        remember(latest);

        JSONArray featured = new JSONArray();
        for (int i = 0; i < latest.length() && featured.length() < FEATURED_LIMIT; i++) {
            featured.put(latest.opt(i));
        }

        JSONObject out = new JSONObject();
        out.put("featured", featured);
        out.put("latest", latest);
        // 站点没有分区接口，留空 —— adaptHome 会去重拼接，正好走 featured 那份。
        out.put("cate_rec", new JSONArray());
        return out;
    }

    /**
     * 类型列表。
     *
     * 这个站点的类型分两级，路径形如 {@code /dm96/cn/genres/高清} —— 中间那个
     * {@code dm96} 是分类 id，末段是分类名（URL 编码的）。
     *
     * slug 的编码约定见 {@link #categoryUrl}。
     */
    private static JSONObject category(String base, String slug, int page) throws Exception {
        if (slug == null || slug.length() == 0) {
            throw new Exception("missav 分类少了 slug");
        }
        int no = page < 1 ? 1 : page;
        // 第一页不带 ?page= —— 站点两种都能过，但带上的那份实测更稳。
        String url = categoryUrl(base, slug, no);
        String html = get(url);
        JSONArray items = MissavParser.parseList(html);
        remember(items);
        if (items.length() == 0) {
            // 0 张卡是最难查的一种：接口 200、日志无异常、界面「已显示 0 部」。
            // 原因可能是地址拼错（站点回 404 内容页）、站点改版、或者过盾掉了
            // 拿到的是挑战页。这三种看这一行就能分开。
            log("missav 列表 0 张卡：" + shortUrl(url)
                    + " html=" + html.length() + "B"
                    + " 含thumbnail=" + html.contains("thumbnail")
                    + " 像挑战页=" + SiteFetchWebView.looksLikeChallenge(html));
        }

        JSONObject out = new JSONObject();
        out.put("items", items);
        out.put("page", no);
        // ── pages 而不是 hasMore ────────────────────────────────────────
        // 前端 adapters.js 的 nextPageOf 只读 page 和 pages：
        //     return page < pages ? page + 1 : null;
        // 之前只给了 hasMore，pages 取不到 → 0 → nextPage 恒为 null → 界面直接
        // 写「已经到底了」，滑到底也不会加载第二页。这就是「每个分类的作品都
        // 太少」的全部原因（2026-10-18 真机）。
        //
        // 站点没给总页数，用「这页满不满」推：满页说明后面还有。
        boolean full = items.length() >= MissavSitePages.PAGE_SIZE;
        out.put("pages", full ? no + 1 : no);
        out.put("hasMore", full);
        return out;
    }

    private static JSONObject search(String base, String keyword, int page) throws Exception {
        int no = page < 1 ? 1 : page;
        JSONArray items;
        try {
            items = MissavParser.parseList(get(base + "/cn/search?q=" + enc(keyword) + "&page=" + no));
            remember(items);
        } catch (Exception e) {
            // 搜索接口挂了不该让搜索页整体报错 —— 空结果比一个错误提示好用，
            // 而且用户能立刻看出来是「没搜到」还是「搜不了」。
            log("missav 搜索失败（q=" + keyword + "）：" + e.getMessage());
            items = new JSONArray();
        }
        JSONObject out = new JSONObject();
        out.put("items", items);
        out.put("page", no);
        out.put("hasMore", items.length() >= MissavSitePages.PAGE_SIZE);
        return out;
    }

    /**
     * 类型页（「全部类型」）。
     *
     * <p><b>输出形状必须对齐 adaptTags</b>（2026-10-18 真机踩到）：前端把 tags 页
     * 的数据走 {@code adaptTags}，它认的是<b>分组</b>结构 ——
     * {@code {items:[{code, tags:[{slug, tagName, group}]}]}}。
     * 第一版这里返回的是平铺的 {@code {items:[{id,name,count}]}}，形状不匹配，
     * {@code asArray(shapeValue(raw,'tags','categories'))} 拿到空 ——
     * 页面标题写着「35 个标签」，内容一片空白（renderTags 注释里管这叫
     * 「最难查的那种」）。
     *
     * <p>分组怎么给：站点的分类没有层级，全部塞一个组，code=category
     * （和 Porn91Site 的 tagGroups 声明对齐）。group 字段直接挂在每个 tag 上
     * （adaptTags 里「标签自己的 group 优先」那条路）。
     *
     * <p>slug 形态是 {@code dm96/高清}（分类 id + 斜杠 + 名字）——点标签后走
     * tag-list → category，categoryUrl 按斜杠分段编码。name 给 count 的原因是
     * adaptTags 的 tagName 会直接显示，纯 id 太难看。
     */
    private static JSONObject genres(String base, int page) throws Exception {
        int no = page < 1 ? 1 : page;
        // ── 为什么要多抓几页 ────────────────────────────────────────────
        // 站点把分类摊在 42 页里（实测每页 36 个，约 1500 个）。只取第一页的话
        // 「全部类型」页只有 35 个胶囊，用户的原话是「类型有几十页 你类型抓的
        // 也不对」—— 他要的是能选到后面那些。
        //
        // 全量 42 页不现实：每页一次 WebView fetch，串行下来十几秒，加源体验
        // 会崩。取前 GENRE_PAGES 页是折中 —— 站点把常用分类排在前面，三页
        // 一百多个覆盖了实际会用到的全部；剩下的仍然可以从「更多」入口或
        // 直接搜索到达。
        JSONArray raw = new JSONArray();
        java.util.Set<String> fetched = new java.util.HashSet<String>();
        for (int i = 0; i < GENRE_PAGES; i++) {
            JSONArray part;
            try {
                part = MissavParser.parseCategories(get(base + "/cn/genres?page=" + (no + i)));
            } catch (Exception e) {
                // 后面几页拿不到不该让整页报错：第一页有内容就已经有用了。
                if (raw.length() == 0) throw e;
                break;
            }
            if (part.length() == 0) break;
            int added = 0;
            for (int k = 0; k < part.length(); k++) {
                JSONObject one = part.optJSONObject(k);
                String cid = one == null ? "" : one.optString("id", "");
                if (cid.length() == 0 || !fetched.add(cid)) continue;
                raw.put(one);
                added++;
            }
            // 用「这一页有没有带来新分类」判断到底了，**不要**用「这页够不够 36 个」：
            // 站点最后一页不满，而且第一页实测只解析出 35 个（36 个里有重复 id）。
            // 按 36 判断的话第一页就 break 了，多页配置形同虚设 —— 2026-10-18 真机
            // 日志「抓了 35 个分类」就是这么来的。
            if (added == 0) break;
        }

        log("missav 类型：抓了 " + fetched.size() + " 个分类（起始页 " + no + "）");

        JSONArray tags = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject c = raw.optJSONObject(i);
            if (c == null) continue;
            String id = c.optString("id", "");
            String name = c.optString("name", "");
            int count = c.optInt("count", 0);
            if (id.length() == 0 || !seen.add(id)) continue;
            JSONObject tag = new JSONObject();
            tag.put("slug", "genres/" + id + "/" + name);
            // 只给分类名，不带「· 290816」那种计数：renderTags 按名字长度决定
            // 胶囊宽度，带上计数后每个都超 8 字被排成整行宽的，一屏看不了几个。
            tag.put("tagName", name);
            if (count > 0) tag.put("count", count);
            tag.put("group", "category");
            tags.put(tag);
        }

        // 分组信封：一个组包全部。
        JSONObject group = new JSONObject();
        group.put("code", "category");
        group.put("tags", tags);

        JSONArray categories = new JSONArray();
        categories.put(group);

        JSONObject out = new JSONObject();
        out.put("items", new JSONArray());
        out.put("categories", categories);
        out.put("page", no);
        // 类型页也有分页（站点 42 页），但第一页 35 个已经够「全部类型」用，
        // 翻页交给 tag-list 那边的 category。这里不再报 hasMore，避免前端
        // 拿「类型页的分页」去翻「影片列表」。
        return out;
    }

    private static JSONObject detail(String base, String id) throws Exception {
        if (id.length() == 0) throw new Exception("missav 详情缺少 id");
        JSONObject detail = MissavParser.parseDetail(get(base + "/cn/" + enc(id)));
        if (detail == null || detail.optString("id", "").length() == 0) {
            throw new Exception("详情页解析不出影片（id=" + id + "）。站点可能改版了。");
        }
        // 详情顺带把播放地址解出来存着 —— 播放那次请求就是再拉一遍同一个页面，
        // 而拉一遍要过盾（1.5~4 秒），能从详情省一次是一次。
        detail.put("play", MissavParser.parsePlay(get(base + "/cn/" + enc(id))));

        // ── episodes：单集站也必须给 ─────────────────────────────────────
        //
        // 2026-10-18 真机验证踩到：这个站一部片就是一集，没有「集数列表」，
        // 于是详情响应里没 episodes 字段 —— 前端 adaptDetail 的
        // asArray(shapeValue(raw,'detail','episodes')) 拿到空数组，
        // 详情页一集都不渲染，用户看到的就是「无法拉到集数」。
        //
        // 单集站要给一个单元素数组，episodeNumber=1。这不是权宜，
        // 是适配器的责任：把「站点没有集数体系」翻译成「有一集」，
        // 而不是把「没有」原样交上去让前端空白。
        JSONObject play = detail.optJSONObject("play");
        org.json.JSONArray episodes = new org.json.JSONArray();
        JSONObject ep = new JSONObject();
        ep.put("episodeNumber", 1);
        ep.put("title", detail.optString("title", ""));
        ep.put("duration", play == null ? "" : play.optString("duration", ""));
        episodes.put(ep);
        detail.put("episodes", episodes);
        detail.put("episodeCount", 1);
        return detail;
    }

    private static JSONObject play(String base, String id) throws Exception {
        if (id.length() == 0) throw new Exception("missav 播放缺少 id");
        JSONObject play = MissavParser.parsePlay(get(base + "/cn/" + enc(id)));
        if (play.optString("playUrl", "").length() == 0) {
            throw new Exception("这一部没解析出播放地址（id=" + id + "）。"
                    + "片源下架，或者站点改了播放器那一段。");
        }
        // resolveUrl 取的是 info.source || info.url（见 feed-player.js:318），
        // videoUrl 它根本不读 —— 2026-10-18 第一次只补了 videoUrl，
        // 结果「这部剧还没有可播放的选集」变成了「未取得播放地址」。
        // source/url/videoUrl 三份都给，别名怎么配都不会漏。
        String url = play.optString("playUrl", "");
        play.put("source", url);
        play.put("url", url);
        play.put("videoUrl", url);
        play.put("episodeNumber", 1);
        return play;
    }

    /**
     * 列表校正（{@code /api/reconcile?ids=a,b,c}）。
     *
     * <p>前端进每个网格页都会调这个：它先在卡片上铺网格页抓到的标题/封面，
     * 再拿这个接口把标题/封面「盖成详情页真值」。加这个接口的起因是 91porn
     * 的网格页在给爬虫放迷魂阵（一页 40 张卡里 12 个 viewkey 重复，点进去是
     * 另一部），见 app.js 里 reconcileCards 上面那段。
     *
     * <p><b>missav 不需要校正，但必须应答。</b>不实现的话每次进列表都抛
     * 「适配器不认识的路径：/api/reconcile?ids=…」—— 真机日志里它比正常请求
     * 还密（一个页面 4~6 次），把真正的错误淹了。而前端一旦拿到空响应就
     * <b>什么都不记</b>，下次进来同样的 id 还会再问一遍，于是永远在报错。
     *
     * <p>所以这里做两件事，全程零网络：
     * <ol>
     *   <li>列表页解析出来的条目都进 {@link #SEEN}（id → 标题/封面/时长）；</li>
     *   <li>被问到的 id 命中缓存就给真值，没命中就给一个只带 id 的空壳 ——
     *       前端会把 id 记进 reconciledFixes，之后不再问（app.js 判的是
     *       {@code !reconciledFixes.has(id)}，不看内容）。</li>
     * </ol>
     * 空壳让 {@code applyFixes} 的 touched 保持 0，卡片上的标题/封面不会被
     * 改成空 —— 这两个字段本来就直接来自列表页，不需要盖。
     */
    private static JSONObject reconcile(String ids) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray items = new JSONArray();
        if (ids == null || ids.length() == 0) {
            out.put("items", items);
            return out;
        }
        for (String raw : ids.split(",")) {
            String id = raw == null ? "" : raw.trim();
            if (id.length() == 0) continue;
            JSONObject hit = lockup(id);
            if (hit != null) {
                items.put(hit);
                continue;
            }
            JSONObject shell = new JSONObject();
            shell.put("id", id);
            items.put(shell);
        }
        out.put("items", items);
        return out;
    }

    // ── 列表条目暂存 ──────────────────────────────────────────────────
    //
    // 只用来应答 reconcile，不参与别的逻辑。容量给 512 足够覆盖「首页 + 几个
    // 分类页」的来回切换；超了整表清空而不是淘汰最旧的那条 —— 这里没有
    // 「热的几条」，全清之后下一次点进去重新填一遍就行，比维护 LRU 省事。

    private static final int SEEN_MAX = 512;
    private static final java.util.LinkedHashMap<String, JSONObject> SEEN =
            new java.util.LinkedHashMap<String, JSONObject>();

    private static void remember(JSONArray items) {
        if (items == null || items.length() == 0) return;
        synchronized (SEEN) {
            if (SEEN.size() > SEEN_MAX) SEEN.clear();
            for (int i = 0; i < items.length(); i++) {
                JSONObject card = items.optJSONObject(i);
                if (card == null) continue;
                String id = card.optString("id", "");
                if (id.length() == 0) continue;
                SEEN.put(id, card);
            }
        }
    }

    private static JSONObject lockup(String id) {
        synchronized (SEEN) {
            return SEEN.get(id);
        }
    }

    // ── 地址拼装 ──────────────────────────────────────────────────────

    /**
     * slug → 分类页地址。
     *
     * **slug 就是完整的「dm96/高清」这样一截**，中间是分类 id、斜杠后是分类名。
     * 分类 id 不能省：站点对同一个分类名的不同 id 给的是不同列表（实测
     * 「高清」既有 {@code dm96} 也有别的编号），只带名字会落到默认那一份。
     *
     * 前端把 tag-list 那一页的 id 原样传下来，所以这里的格式是站点的形状，
     * 不是我们的 —— {@link MissavParser#parseCategories} 产出的 id 就是这个形状。
     */
    static String categoryUrl(String base, String slug, int page) {
        String s = slug == null ? "" : slug.replaceAll("^/+", "");
        int slash = s.indexOf('/');
        String head = slash > 0 ? s.substring(0, slash) : "";
        StringBuilder path = new StringBuilder();
        if ("genres".equals(head) && slash > 0) {
            // ── 类型页：genres/dm106/剧情 → /dm106/cn/genres/剧情 ─────────
            // 类型页比普通分类多一层 genres 段，所以 slug 里用 `genres` 打头
            // 把两种形态分开。见 MissavParser.parseCategories 产出的 id。
            String rest = s.substring(slash + 1);
            int sep = rest.indexOf('/');
            String dmId = sep > 0 ? rest.substring(0, sep) : rest;
            String name = sep > 0 ? rest.substring(sep + 1) : "";
            path.append('/').append(enc(dmId)).append("/cn/genres");
            if (name.length() > 0) path.append('/').append(enc(name));
        } else if (slash > 0 && head.matches("dm\\d+")) {
            // ── 普通分类：dm597/fc2 → /dm597/cn/fc2 ─────────────────────
            //
            // 分类 id 在**路径最前面**，`cn` 在中间，slug 在最后 —— 顺序是站点
            // 定的，不能挪。第一版拼成 `/cn/dm96/高清`（把 id 当成 cn 下面的一级），
            // 站点回的是 404 内容页，parseList 一张卡都认不出来，症状是
            // 「已显示 0 部作品」。证据：首页导航里每个二级分类都是这个形状
            // （dm150/cn/gachinco、dm597/cn/fc2、dm42/cn/tokyohot …）。
            path.append('/').append(enc(head)).append("/cn/")
                    .append(enc(s.substring(slash + 1)));
        } else {
            // ── 固定导航：/cn/new、/cn/release ───────────────────────────
            // 站点这两种写法都认（/cn/new 和 /dm539/cn/new 等价），用短的那个。
            path.append("/cn/").append(enc(s));
        }
        if (page > 1) path.append("?page=").append(page);
        return base + path.toString();
    }

    private static String get(String url) throws Exception {
        return SiteFetchWebView.fetchPage(url);
    }

    /** 站点根。从 profile.origin 取，只有它空着才退回默认域名。 */
    static String base(SiteProfile profile) {
        String origin = profile == null ? "" : profile.origin;
        if (origin == null) origin = "";
        origin = origin.replaceAll("/+$", "");
        return origin.length() == 0 ? "https://missav.live" : origin;
    }

    // ── 参数 ──────────────────────────────────────────────────────────

    /** 从 `/api/xxx/yyy?a=1&b=2` 里取最后一个斜杠段之后的 id。 */
    private static String idFrom(String path) {
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int slash = p.lastIndexOf('/');
        String id = slash >= 0 ? p.substring(slash + 1) : p;
        try {
            return java.net.URLDecoder.decode(id, "UTF-8");
        } catch (Exception e) {
            return id;
        }
    }

    private static String param(String query, String key) {
        int q = query.indexOf('?');
        String qs = q >= 0 ? query.substring(q + 1) : "";
        String[] pairs = qs.split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            if (eq < 0) continue;
            if (!key.equals(pairs[i].substring(0, eq))) continue;
            try {
                return java.net.URLDecoder.decode(pairs[i].substring(eq + 1), "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
        return "";
    }

    private static int page(String path) {
        try {
            return Integer.parseInt(param(path, "page"));
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * URL 编码。
     *
     * 走 {@code java.net.URLEncoder} 会把空格编成 {@code +} —— 而站点是 PHP，
     * 路径段里的 {@code +} 不被当空格解释。所以空格单独替换回 {@code %20}。
     * （这个坑很老但每站都要各自确认一次：分类名里带空格的不少。）
     */
    private static String enc(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    /** 日志里的地址：去掉 query，太长就截中间。 */
    private static String shortUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        String s = q > 0 ? url.substring(0, q) : url;
        if (s.length() <= 120) return s;
        return s.substring(0, 50) + "…" + s.substring(s.length() - 60);
    }

    /** 站点分页尺寸。集中在这儿，分类/搜索共用一个判断。 */
    static final class MissavSitePages {
        private MissavSitePages() {}

        /** 列表页实测 12 张卡（首页）/ 类型页 36 个。取小的那个当「还有下一页」的门槛。 */
        static final int PAGE_SIZE = 12;

        /** 类型页实测 36 个/页。 */
        static final int GENRE_PAGE_SIZE = 36;
    }
}
