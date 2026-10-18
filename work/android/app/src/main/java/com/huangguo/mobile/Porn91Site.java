package com.huangguo.mobile;

import static com.huangguo.mobile.SiteCfg.*;
import static com.huangguo.mobile.UserSource.log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;

/**
 * 91porn（KVS 模板）。
 *
 * 单站 HTML 型：`/api/*` 全 404，数据全在服务端渲染的页面里。解析在
 * {@link Porn91Adapter}，这里负责**认站点 + 生成配置**。
 *
 * 以前这一站没有识别逻辑 —— 配置是外部直接给的，用户填地址根本加不进来
 * （load() 会 fetch 一次拿到 HTML，然后报「返回的不是合法 JSON」）。
 * 现在补上：
 *
 *   ① 域名（901.26702.app 及同类镜像）
 *   ② 指纹：`/v.php` 里有 video-title 卡片 + 足量 view_video.php?viewkey=
 *
 * 域名不在表里、指纹又没机会跑 —— 那就认不出来。这个站的镜像换得勤，
 * 换域名时往 HOSTS 里加一行即可。
 */
final class Porn91Site implements SiteAdapter {

    /** 已知镜像。不是白名单 —— 指纹过了才算数。 */
    private static final String[] HOSTS = { "901.26702.app", "91porn.com" };

    /**
     * 分类表。站点导航栏里就这十个，**不认识的 code 回的是「全站最新」而不是 404**
     * —— 所以只能写实测过的。
     */
    /** 分类表。适配器在「导航抓不到」时拿它兜底。 */
    static String[][] categoryCodes() { return CATEGORIES; }

    private static final String[][] CATEGORIES = {
        { "ori",    "91原创"   },
        { "hot",    "热门"     },
        { "top",    "本周榜"   },
        { "hd",     "高清"     },
        { "rf",     "精选"     },
        { "tf",     "收藏"     },
        { "long",   "10分钟+"  },
        { "longer", "20分钟+"  },
        { "md",     "讨论多"   },
        { "mf",     "最多收藏" },
    };

    /** 底部导航：前五个上主位，其余折进「更多」。 */
    private static final int TAB_COUNT = 5;

    @Override public String id() { return "porn91"; }

    @Override public boolean matchesUrl(String url) {
        String host = hostOf(url);
        if (host.length() == 0) return false;
        for (int i = 0; i < HOSTS.length; i++) {
            if (host.equals(HOSTS[i]) || host.endsWith("." + HOSTS[i])) return true;
        }
        return false;
    }

    @Override public JSONObject probe(String url) throws Exception {
        String base = originOf(url);
        if (base.length() == 0) throw new IOException("地址看不懂：" + url);

        String html = fetchHome(base);
        if (html.length() == 0) {
            throw new IOException("站点返回了空响应。这个站会按 User-Agent 分流，"
                    + "非浏览器 UA 直接回空 —— 也可能是当前网络连不上它。");
        }
        if (!html.contains("video-title")) {
            throw new IOException("没认出这是 91porn：首页里没有 video-title 卡片。"
                    + "站点可能改版，或者填的不是这个站。");
        }
        int cards = count(html, "view_video.php?viewkey=");
        if (cards < 5) {
            throw new IOException("首页只有 " + cards + " 条视频链接 —— 不算探通"
                    + "（可能拿到的是错误页或拦截页）。");
        }
        log("porn91 探测通过：" + base + "，首页视频链接 " + cards + " 条");

        JSONObject config = new JSONObject();
        config.put("adapter", "porn91");
        config.put("origin", base);

        // ── 封面 ────────────────────────────────────────────────────────
        // 封面在 CDN 上（实测 1729130453.rsc.cdn77.org），不在站点域。
        // 白名单漏了就是「首页全是灰底」，所以从首页 HTML 里现抓一遍图片域名。
        JSONObject cover = new JSONObject();
        cover.put("mode", "plain");
        cover.put("allowedHosts", coverHosts(html, hostOf(base)));
        cover.put("hostRewrites", new JSONObject());
        config.put("cover", cover);

        // ── web ─────────────────────────────────────────────────────────
        JSONObject web = new JSONObject();
        web.put("firstPageSize", 24);
        web.put("pageSize", 24);

        JSONObject paths = new JSONObject();
        paths.put("home", "/api/home");
        paths.put("detail", "/api/videos/{id}");
        paths.put("episode", "/api/videos/{id}/play?ep={episode}");
        paths.put("search", "/api/search?q={keyword}&page={page}");
        paths.put("category", "/api/videos?cate_id={cateId}&page={page}");
        paths.put("tags", "/api/tags");
        // 「标签」在这个站上就是顶部分类栏，点进去就是分类列表 —— 所以 tag-list
        // 直接复用分类接口。没有这一条的话，全部类型页和首页那排胶囊点下去都
        // 落在 Java 桥的 tag-list 指令上，那个指令这个源没有，等于没反应。
        paths.put("tag-list", "/api/videos?cate_id={slug}&page={page}");
        web.put("paths", paths);

        JSONObject categories = new JSONObject();
        for (int i = 0; i < CATEGORIES.length; i++) {
            categories.put(CATEGORIES[i][0], CATEGORIES[i][0]);
        }
        web.put("categories", categories);

        // ── 底部导航 ────────────────────────────────────────────────────
        // ui 必须在 web 里 —— 页面读 window.HG_SITE.ui。
        JSONObject ui = new JSONObject();
        JSONArray tabs = new JSONArray();
        JSONObject ids = new JSONObject();
        JSONObject labels = new JSONObject();
        JSONObject titles = new JSONObject();
        JSONObject leads = new JSONObject();
        JSONArray popular = new JSONArray();
        JSONArray more = new JSONArray();

        for (int i = 0; i < CATEGORIES.length; i++) {
            String code = CATEGORIES[i][0];
            String label = CATEGORIES[i][1];
            if (i >= TAB_COUNT) {
                more.put(code);
                continue;
            }
            JSONObject tab = new JSONObject();
            tab.put("key", code);
            tab.put("label", label);
            tab.put("icon", tabIcon(i));
            tabs.put(tab);
            ids.put(code, new JSONArray().put(code));
            labels.put(code, new JSONArray().put(label));
            titles.put(code, label);
            leads.put(code, label + "分类。");
            if (i < 4) popular.put(new JSONArray().put(code).put(label));
        }
        if (more.length() > 0) {
            JSONObject tab = new JSONObject();
            tab.put("key", "more");
            tab.put("label", "更多");
            tab.put("icon", "grid");
            tabs.put(tab);
            ids.put("more", more);
            JSONArray moreLabels = new JSONArray();
            for (int i = TAB_COUNT; i < CATEGORIES.length; i++) moreLabels.put(CATEGORIES[i][1]);
            labels.put("more", moreLabels);
            titles.put("more", "更多分类");
            leads.put("more", "其余 " + more.length() + " 个分类。");
        }
        ui.put("tabs", tabs);
        ui.put("categoryIds", ids);
        ui.put("categoryLabels", labels);
        ui.put("categoryTitles", titles);
        ui.put("categoryLeads", leads);
        // 标签页按 tagGroups 分组渲染，没声明的组一个都不显示（renderTags 只认这份
        // 配置）。这个站只有一组：站点的分类栏。
        JSONArray tagGroups = new JSONArray();
        tagGroups.put(new JSONArray().put("category").put("分类"));
        ui.put("tagGroups", tagGroups);
        ui.put("popularTags", popular);
        ui.put("publicPopularTags", new JSONArray());
        web.put("ui", ui);
        config.put("web", web);

        config.put("_note", "91porn（" + hostOf(base) + "）。KVS 模板，服务端渲染，"
                + "/api/* 全 404。单集站：一个 viewkey 一部片，播放地址在详情页里，"
                + "没有多档清晰度。标签页列的是站点的分类栏（导航里那 11 个），"
                + "点进去就是对应的分类列表。");
        return config;
    }

    @Override public byte[] fetch(SiteProfile profile, String path) throws Exception {
        return Porn91Adapter.fetch(path, profile);
    }

    @Override public String check(JSONObject config) {
        return checkOrigin(config);
    }

    @Override public String identity(JSONObject config) {
        String origin = config.optString("origin", "").trim();
        return origin.length() == 0 ? null : "hgsrc:porn91|" + origin + "/view_video.php?viewkey=";
    }

    // ── 探测 ──────────────────────────────────────────────────────────

    private static String fetchHome(String base) {
        try {
            byte[] raw = AiiHttp.getRaw(base + "/v.php", SiteCfg.browserHeaders(), 20);
            return raw == null || raw.length == 0 ? "" : new String(raw, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            log("porn91 探针失败：" + e);
            return "";
        }
    }

    /** 首页里出现过的图片域名。站点自己那个也留着 —— 有的封面走相对路径。 */
    private static JSONArray coverHosts(String html, String siteHost) {
        LinkedHashSet<String> hosts = new LinkedHashSet<String>();
        if (siteHost.length() > 0) hosts.add(siteHost);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:src|poster|data-original)=[\"']https://([a-zA-Z0-9.-]+)/").matcher(html);
        int guard = 0;
        while (m.find() && guard < 400) {
            guard++;
            String host = m.group(1);
            if (host.length() == 0) continue;
            // 统计/广告域名不是封面，别放进来
            if (host.contains("googletagmanager") || host.contains("google-analytics")
                    || host.contains("baidu") || host.contains("pv112") || host.contains("sp2026")) {
                continue;
            }
            hosts.add(host);
            if (hosts.size() >= 8) break;
        }
        JSONArray out = new JSONArray();
        for (String host : hosts) out.put(host);
        return out;
    }

    private static int count(String text, String needle) {
        int n = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
            if (n > 1000) break;
        }
        return n;
    }

    private static String tabIcon(int index) {
        String[] icons = { "film", "star", "sparkle", "tv", "grid" };
        return icons[Math.min(index, icons.length - 1)];
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
            return host == null ? "" : host.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}
