package com.huangguo.mobile;

import static com.huangguo.mobile.SiteCfg.*;
import static com.huangguo.mobile.UserSource.log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;

/**
 * MissAV（missav.live）。
 *
 * 单站 HTML 型，但和 mrds66 / porn91 有一个**根本区别**：整站（含 {@code /api/*}）
 * 挂在 Cloudflare 的 JS 挑战后面。2026-10-18 实测四条路全部 403：
 *
 * <pre>
 *   curl + 完整浏览器头                        → 403 Just a moment
 *   curl + 换成安卓 Chrome 的 UA               → 403
 *   curl + 从真 Chrome 里偷出来的 cf_clearance  → 403
 *   okhttp（本 App 的默认出口）                 → 403
 * </pre>
 *
 * 第四条是最贵的那条教训：<b>{@code cf_clearance} 绑的是发出请求那台的 TLS 指纹</b>
 * （JA3/JA4），不是绑 cookie 本身。所以「让 WebView 拿到 cookie、再喂给 okhttp」
 * 这条看似很自然的路在协议上就是死的 —— 别再往那个方向试第二次。
 *
 * 所以这里的所有抓取都走 {@link SiteFetchWebView}：在真 WebView 里过一次盾，
 * 然后在那个页面里发同源 fetch（只有同源才自动带上 clearance）。
 *
 * <b>播放地址不用过盾的方式拿</b>：详情页里那段 packed JS 里就明文写着
 * {@code https://surrit.com/{uuid}/{画质}/video.m3u8}，字典没加密，
 * {@link MissavParser#unpackScript} 直接解出来。所以播放器侧只多一道「surrit.com
 * 也在盾后」的坎，处理方式和页面侧是同一条路。
 *
 * ── 站点结构（2026-10-18 实测）────────────────────────────────────────
 *   /dm539/cn/new                最近更新（过盾用的就是这一页，最轻）
 *   /dm96/cn/genres/高清          类型页。分类 id 是路径里的数字（dm96）
 *   /cn/fct-210                  详情页。番号即 id
 *   surrit.com/{uuid}/1080p/video.m3u8   播放
 */
final class MissavSite implements SiteAdapter {

    /**
     * 已知域名。
     *
     * 这个站的换域名很勤（2026-10-18 实测 missav.ai / .app / .club / .123 / .01
     * 全部还在盾后，其中 .app 直接 404）。所以这里是「认得的主域名」，
     * 真正的探针仍然要过一遍盾才算数 —— 域名对但盾过不去，一样要报明确的错，
     * 不能让用户存一份点了没反应的配置。
     */
    private static final String[] HOSTS = { "missav.live" };

    /** 底部导航前五个上主位，其余折进「更多」。 */
    private static final int TAB_COUNT = 5;

    /**
     * 导航入口。**刻意写死，不从站点抓。**
     *
     * 类型页有 42 页、上千个分类（实测 `/cn/genres` 共 42 页，每页 36 个），
     * 抓全量会让探针慢到不能接受，而且「全部类型」页根本展示不完。
     * 这里挑的是站点页脚那一组 —— 那才是站点自己认的入口顺序。
     *
     * 另外四个（无码流出 / 中文字幕 / 女优 / 发行商）是不同性质的列表，
     * 形状一样但不该和「类型」混在一排，所以放进「更多」。
     */
    /**
     * 底部导航。**一行一个 tab，前三个是 tab 自己（key / 名字 / 图标），
     * 从第四个开始成对出现的是它的子分类（站点 slug / 显示名）。**
     *
     * <p>为什么要子分类：站点自己的导航就是两级的 ——「无码影片」下面挂着
     * 无码流出 / FC2 / HEYZO / 东京热 / 一本道 / Caribbeancom / Gachinco /
     * XXX-AV / 人妻斩 / 顽皮…（用户 2026-10-18 直接截了官网那张菜单图）。
     * 一个 tab 只给一个总列表，等于把站点最好的那层结构扔了。
     *
     * <p>slug 形态 `dm597/fc2` / `dm539/new` 里的数字是站点的分类 id，
     * **必须带** —— 站点对同一个名字的不同 id 给的是不同列表。
     * 拼地址的逻辑全在 {@link MissavAdapter#categoryUrl}。
     *
     * <p>「女优」和「发行商」是索引页而不是作品列表（`/cn/actresses` 列的是
     * 女优，不是片），所以它们只能自己一项，不能当作品列表 tab 用 ——
     * 真机的表现是「女优作品 / 已显示 0 部」。
     */
    private static final String[][] NAV = {
        { "new", "最近更新", "sparkle",
          "dm539/new", "最近更新" },

        { "uncensored", "无码影片", "film",
          "dm817/uncensored-leak", "无码流出",
          "dm597/fc2",             "FC2",
          "dm2208642/heyzo",       "HEYZO",
          "dm42/tokyohot",         "东京热",
          "dm5199603/1pondo",      "一本道",
          "dm7704788/caribbeancom", "Caribbeancom",
          "dm91887/caribbeancompr", "Caribbeancompr",
          "dm150/gachinco",        "Gachinco",
          "dm42/xxxav",            "XXX-AV",
          "dm37/marriedslash",     "人妻斩",
          "dm33/naughty4610",      "顽皮4610",
          "dm37/naughty0930",      "顽皮0930" },

        { "amateur", "素人", "user",
          "dm3600557/pacopacomama", "pacopacomama",
          "dm7208981/10musume",     "10musume",
          "dm38/scute",             "scute",
          "dm36/siro",              "SIRO",
          "dm15/furuke",            "furuke" },

        { "chinese-subtitle", "中文字幕", "tv",
          "dm278/chinese-subtitle", "中文字幕" },

        { "release", "新作上市", "star",
          "dm635/release", "新作上市" },
    };

    // ── 为什么没有「女优」「发行商」──────────────────────────────────
    //
    // `/cn/actresses` 和 `/cn/makers` 是**索引页**：列的是女优和片商，不是作品。
    // 当成作品列表接进来，症状是「女优作品 / 已显示 0 部」（2026-10-18 真机）。
    //
    // 要真正用起来得加一层：点女优 → `/cn/actress/{slug}` 看她的作品。那需要
    // 前端多一个页面（现在只有「列表」和「影片详情」两种），属于下一轮的事。
    // 在那之前，把它们塞进底部导航只会让用户点到一个永远空着的入口。

    /** 类型页每页多少个。实测 36 个/页。 */
    private static final int GENRE_PAGE_SIZE = 36;

    @Override public String id() { return "missav"; }

    @Override
    public boolean matchesUrl(String url) {
        String host = hostOf(url);
        if (host.length() == 0) return false;
        for (int i = 0; i < HOSTS.length; i++) {
            if (host.equals(HOSTS[i]) || host.endsWith("." + HOSTS[i])) return true;
        }
        return false;
    }

    @Override
    public JSONObject probe(String url) throws Exception {
        String base = originOf(url);
        if (base.length() == 0) throw new IOException("地址看不懂：" + url);

        // 探针打在列表页上：它和过盾页是同一个 URL，所以这一次请求既验证了
        // 「盾过得了吗」也验证了「这个域是不是真的 missav」—— 两件事一次做完。
        // 用户加源时最痛的不是慢，是「加成功了但首页空的」，这里先把它挡掉。
        String html;
        try {
            html = SiteFetchWebView.fetchPage(base + "/dm539/cn/new");
        } catch (Exception e) {
            throw new IOException("连不上 " + base + "（" + e.getMessage() + "）。");
        }

        if (SiteFetchWebView.looksLikeChallenge(html)) {
            throw new IOException("拿到的是站点的验证页，不是内容。"
                    + "换��网络或者开启代理再试一次。");
        }
        JSONArray cards = MissavParser.parseList(html);
        if (cards.length() < 3) {
            throw new IOException("首页只认出 " + cards.length() + " 个影片卡片 —— 不算探通。"
                    + "站点可能改版了，或者填的不是这个站。");
        }
        log("missav 探测通过：" + base + "，首页卡片 " + cards.length() + " 个");
        return buildConfig(base, html);
    }


    /**
     * 由站点根和一份首页 HTML 生成整份配置。<b>不发任何请求。</b>
     *
     * <p>抽出来是为了能被单测直接调：配置里的 paths / fields / ui 是「适配器契约」
     * 的另一半，错了的表现全是「接口 200、界面空」。这些东西完全由参数决定，
     * 没有理由非得联网才能拿到 —— 抽出来之后测试能把它整份钉住，改配置时
     * 少一个字段会当场红。
     */
    static JSONObject buildConfig(String base, String html) throws Exception {
        JSONObject config = new JSONObject();
        config.put("adapter", "missav");
        config.put("origin", base);

        // ── 封面 ────────────────────────────────────────────────────────
        // 封面和预览图都在 fourhoi.com，不在站点域 —— 白名单漏了就是「首页全灰底」。
        // 从刚拉下来的 HTML 里现抓一遍，别写死：CDN 换域名是常事。
        JSONObject cover = new JSONObject();
        cover.put("mode", "plain");
        cover.put("allowedHosts", coverHosts(html, hostOf(base)));
        cover.put("hostRewrites", new JSONObject());
        config.put("cover", cover);

        // ── headers：封面域名的 CF 免检票根 ──────────────────────────────
        //
        // 封面在 fourhoi.com（也在 Cloudflare 后面）。okhttp 默认 UA 打它 → 403
        // （2026-10-18 真机实测），Mac 上裸 curl 却 200 —— 挡的是 UA 而不是 IP。
        // applyHeaders 按 profile.headers 给封面请求补头，{origin} 占位符换成站点根。
        JSONObject headers = new JSONObject();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36");
        headers.put("Referer", base + "/");
        headers.put("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
        config.put("headers", headers);

        // ── web ─────────────────────────────────────────────────────────
        JSONObject web = new JSONObject();
        web.put("firstPageSize", 12);   // 实测首页 12 张卡
        web.put("pageSize", GENRE_PAGE_SIZE);

        JSONObject paths = new JSONObject();
        paths.put("home", "/api/home");
        paths.put("detail", "/api/detail/{id}");
        paths.put("episode", "/api/play/{id}");
        paths.put("search", "/api/search?q={keyword}&page={page}");
        paths.put("category", "/api/category?slug={cateId}&page={page}");
        paths.put("tags", "/api/genres");
        // tag-list 是「点了一个分类之后看作品」，所以它必须落到 /api/category。
        // 第一版这里写成 /api/genres?page={page}（想的是「类型页也分页」），
        // 结果前端把分类索引的分组信封当成作品列表，点任何分类都是空列表 ——
        // 2026-10-18 真机。两件事名字都带 genres，但一个是索引、一个是列表。
        paths.put("tag-list", "/api/category?slug={slug}&page={page}");
        web.put("paths", paths);

        // ── fields 别名表：漏配的下场是「数据都在，界面全是空」──────────────
        //
        // 2026-10-18 真机踩到：适配器返回的 episodes[{episodeNumber:1}] 是好的，
        // 但 pick(entry,'episodeNumber') 走这张别名表 —— 默认表里 episodeNumber
        // 只有 ['ep_num']（AGE动漫/CMS 的字段名），没有 'episodeNumber' 字面量，
        // 于是 number 取到空，openAt 里 filter(Boolean) 把唯一的集滤掉，
        // 症状是「这部剧还没有可播放的选集」。source 同理：默认 ['video_url']，
        // 我返回的 'source' 键照样被滤。
        //
        // 所以适配器自己返回什么键名，这张表里就必须声明同名候选 —— 这不是可选
        // 步骤，是适配器契约的一半（另一半在响应形状上）。
        JSONArray fArr = arr("episodeNumber");
        JSONArray fSrc = new JSONArray();
        fSrc.put("source"); fSrc.put("playUrl"); fSrc.put("video_url");
        // ── shapes：翻页字段挂在哪根枝上 ─────────────────────────────────
        //
        // app.js 的 nextPageOf 是：
        //     const page  = Number(shapeValue(raw,'list','page','pagination.page')) || 1;
        //     const pages = Number(shapeValue(raw,'list','pages','pagination.pages')) || 0;
        //     return page < pages ? page + 1 : null;
        //
        // 没配 shapes 时它退回 `pagination.page` / `pagination.pages` 那套老形状，
        // 而我们是平铺的 `page` / `pages` —— 读不到 → pages=0 → nextPage 恒为
        // null → 每个分类页都只有第一页 12 部，滑到底写「已经到底了」。
        // 这就是「每个的数量都太少了」的全部原因（2026-10-18 真机）。
        JSONObject listShape = new JSONObject();
        listShape.put("items", "items");
        listShape.put("page", "page");
        listShape.put("pages", "pages");
        JSONObject shapes = new JSONObject();
        shapes.put("list", listShape);
        web.put("shapes", shapes);

        JSONObject fields = new JSONObject();
        fields.put("id", arr("id"));
        fields.put("title", arr("title"));
        fields.put("cover", arr("cover"));
        fields.put("score", arr("score"));
        fields.put("tags", arr("tags"));
        fields.put("description", arr("description"));
        fields.put("badge", arr("badge"));
        fields.put("episodeNumber", fArr);
        fields.put("episodeCount", arr("episodeCount"));
        fields.put("source", fSrc);
        fields.put("duration", arr("duration"));
        // 标签页那一组。少了 tagName 的下场和 episodeNumber 一模一样：
        // adaptTags 里 name 是 pick(tag,'tagName') || pick(tag,'slug')，默认别名表
        // 只认 'name'，我返回的是 'tagName' → 取空 → 退到 slug，
        // 「全部类型」页上每个胶囊都写成「dm96/高清」。2026-10-18 真机。
        JSONArray fTagName = new JSONArray();
        fTagName.put("tagName");
        fTagName.put("name");
        fields.put("tagName", fTagName);
        fields.put("slug", arr("slug"));
        fields.put("code", arr("code"));
        web.put("fields", fields);

        // ── 底部导航 ────────────────────────────────────────────────────
        JSONObject ui = new JSONObject();
        JSONArray tabs = new JSONArray();
        JSONObject ids = new JSONObject();
        JSONObject labels = new JSONObject();
        JSONObject titles = new JSONObject();
        JSONObject leads = new JSONObject();
        JSONArray popular = new JSONArray();
        JSONArray more = new JSONArray();

        // 每个 tab 的子分类：categoryIds[key] / categoryLabels[key] 是两个等长
        // 数组，前端按 state.sub[key] 取同一位置。这就是「点了无码影片，上面
        // 能切 FC2 / HEYZO / 东京热…」那排胶囊的来源。
        JSONObject categories = new JSONObject();
        JSONArray moreSlugs = new JSONArray();
        JSONArray moreLabels = new JSONArray();
        for (int i = 0; i < NAV.length; i++) {
            String key = NAV[i][0];
            String label = NAV[i][1];
            String icon = NAV[i][2];
            JSONArray subIds = new JSONArray();
            JSONArray subLabels = new JSONArray();
            for (int k = 3; k + 1 < NAV[i].length; k += 2) {
                subIds.put(NAV[i][k]);
                subLabels.put(NAV[i][k + 1]);
            }
            if (subIds.length() == 0) {     // 没配子项时退回 tab 自己
                subIds.put(key);
                subLabels.put(label);
            }
            if (i >= TAB_COUNT) {
                for (int k = 0; k < subIds.length(); k++) {
                    moreSlugs.put(subIds.opt(k));
                    moreLabels.put(subLabels.opt(k));
                }
                continue;
            }
            JSONObject tab = new JSONObject();
            tab.put("key", key);
            tab.put("label", label);
            tab.put("icon", icon);
            tabs.put(tab);
            ids.put(key, subIds);
            labels.put(key, subLabels);
            titles.put(key, label);
            leads.put(key, label + "。");
            // web.categories 是 cateIdOf 查的那张表（slug → 站点 cateId）。
            // **每个 tab 的每个子分类都要进来**，不能只填前几个 —— 漏一个，
            // 前端 apiPathFor 里 `if (!cateId) return null` 就直接放弃请求，
            // 症状是「点了那个子标签，页面还停在上一页」。单测逮到过一次
            // （release tab 漏了）。
            for (int k = 0; k < subIds.length(); k++) {
                categories.put(subIds.optString(k, ""), subIds.optString(k, ""));
            }
            // 首页那排胶囊：取前三个 tab 的第一个子分类，**名字用 tab 的中文名**。
            // 用子分类名会让「素人」那颗胶囊写成 pacopacomama —— 那是站名，
            // 摆在首页当分类名没人看得懂。
            if (i < 3) {
                popular.put(new JSONArray().put(subIds.opt(0)).put(label));
            }
        }
        // 「更多」也是一个 tab，同样按子分类数组给 —— 否则它点开是一排
        // 什么也点不动的空按钮。
        if (moreSlugs.length() > 0) {
            JSONObject tab = new JSONObject();
            tab.put("key", "more");
            tab.put("label", "更多");
            tab.put("icon", "grid");
            tabs.put(tab);
            ids.put("more", moreSlugs);
            labels.put("more", moreLabels);
            titles.put("more", "更多入口");
            leads.put("more", "其余 " + moreSlugs.length() + " 个入口。");
            for (int k = 0; k < moreSlugs.length(); k++) {
                String slug = moreSlugs.optString(k, "");
                categories.put(slug, slug);
            }
        }
        // 前端 cateIdOf(slug) 就是查这张表（slug → 站点 cateId，这里是自映射）。
        // 漏一个 slug 就让那个子分类点了没反应 —— apiPathFor 里
        // `if (!cateId) return null` 会静默放弃这次请求。
        web.put("categories", categories);

        ui.put("tabs", tabs);
        ui.put("categoryIds", ids);
        ui.put("categoryLabels", labels);
        ui.put("categoryTitles", titles);
        ui.put("categoryLeads", leads);
        JSONArray tagGroups = new JSONArray();
        tagGroups.put(new JSONArray().put("category").put("分类"));
        ui.put("tagGroups", tagGroups);
        ui.put("popularTags", popular);
        ui.put("publicPopularTags", new JSONArray());
        web.put("ui", ui);
        config.put("web", web);

        config.put("_note", "MissAV（" + hostOf(base) + "）。单站 HTML 型，整站挂在 Cloudflare "
                + "后面 —— 所有抓取都在真 WebView 里过一次盾再做同源 fetch，"
                + "走 okhttp 会被挡（cf_clearance 绑 TLS 指纹，不是绑 cookie）。"
                + "番号即 id（/cn/fct-210）。播放是 surrit.com 的 HLS，"
                + "四档清晰度，地址明文写在详情页那段 packed JS 里，不用算。");
        return config;
    }


    @Override public byte[] fetch(SiteProfile profile, String path) throws Exception {
        return MissavAdapter.fetch(path, profile);
    }

    @Override public String check(JSONObject config) {
        return checkOrigin(config);
    }

    /**
     * id 空间。
     *
     * 这个站的 id 会从两种 URL 形态出现 —— 列表页给的是 {@code /dm539/cn/fct-210}，
     * 页脚链接给的是 {@code /cn/fct-210}。观看历史要互通，就必须把两者归一到同一串，
     * 所以这里定的是 id 的前缀而不是某一整条路径：同一个番号在两个页面之间切换，
     * 历史不会断成两条。
     */
    @Override public String identity(JSONObject config) {
        String origin = config.optString("origin", "").trim();
        return origin.length() == 0 ? null : "hgsrc:missav|" + origin + "/cn/";
    }

    // ── 探测辅助 ──────────────────────────────────────────────────────

    /** 页面里出现过的图片域名（含站点自己 —— 有封面走相对路径）。 */
    private static JSONArray coverHosts(String html, String siteHost) {
        LinkedHashSet<String> hosts = new LinkedHashSet<String>();
        if (siteHost.length() > 0) hosts.add(siteHost);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:src|poster|data-src|data-original)=[\"']?https://([a-zA-Z0-9.-]+)/").matcher(html);
        int guard = 0;
        while (m.find() && guard < 600) {
            guard++;
            String host = m.group(1);
            if (host.length() == 0) continue;
            // 统计 / 广告 / 头像域名不是封面。
            if (host.contains("googletagmanager") || host.contains("google-analytics")
                    || host.contains("cloudflare") || host.contains("myavlive")
                    || host.contains("doubleclick") || host.contains("snaptrckr")
                    || host.contains("wheelroot") || host.contains("mayzaent")) {
                continue;
            }
            hosts.add(host);
            if (hosts.size() >= 6) break;
        }
        JSONArray out = new JSONArray();
        for (String host : hosts) out.put(host);
        return out;
    }

    /** 两个字段的候选别名（fields 表用）。 */
    private static JSONArray arr2(String first, String second) {
        JSONArray a = new JSONArray();
        a.put(first);
        a.put(second);
        return a;
    }

    static String originOf(String url) {
        try {
            URI uri = new URI(url == null ? "" : url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return "";
            if (!"https".equalsIgnoreCase(scheme)) return "";
            return scheme.toLowerCase() + "://" + host
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (Exception e) {
            return "";
        }
    }

    static String hostOf(String url) {
        try {
            String host = new URI(url == null ? "" : url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }
}
