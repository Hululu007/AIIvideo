package com.huangguo.mobile;

import static com.huangguo.mobile.SiteCfg.*;
import static com.huangguo.mobile.UserSource.log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * OpenList / AList 文件站（asmrgay.com）。
 *
 * 和另外四个适配器的根本区别：那个站不是内容站，是**文件站**。它没有海报墙、
 * 没有分类表，接口就是 AList 那套 `/api/fs/*`（列目录、取直链、递归搜）。
 * 具体的映射关系写在 {@link OpenListAdapter} 的类注释里。
 *
 * 识别方式两道：
 *   ① 域名（asmrgay.com / asmrgay.org）
 *   ② 指纹：`/api/public/settings` 回 200 且带 AList 那套字段
 *
 * 只按域名认的话，站点换域名就废；只按指纹认的话，每填一个地址都要先
 * 发一次请求才能判断「这不是」。所以域名优先，指纹兜底。
 */
final class OpenListSite implements SiteAdapter {

    /** 已知域名。不是白名单 —— 指纹过了照样认。 */
    private static final String[] HOSTS = { "asmrgay.com", "asmrgay.org" };

    @Override public String id() { return "openlist"; }

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

        // ① 指纹：AList / OpenList 的公开设置接口。不是它的话这里就 404 了。
        JSONObject settings = settings(base);
        if (settings == null) {
            throw new IOException("这不是 OpenList 站点：/api/public/settings 没认出 AList 的响应");
        }

        // ② 根目录。空站点（一个目录都没有）也认，但要在报错里说清楚。
        JSONArray roots = OpenListAdapter.rootDirs(base);
        log("openlist 探测通过：" + base + "，根目录 " + roots.length() + " 个");

        JSONObject config = new JSONObject();
        config.put("adapter", "openlist");
        config.put("origin", base);

        // ── 封面 ────────────────────────────────────────────────────────
        // 文件站没有海报，thumb 恒为空。但 SiteProfile 的构造器**要求**
        // allowedHosts 非空（否则抛「缺少封面域名」），所以这里放站点自己 —— 
        // 一个都不会被用到，只是让配置能过构造。
        JSONObject cover = new JSONObject();
        cover.put("mode", "plain");
        JSONArray hosts = new JSONArray();
        hosts.put(hostOf(base));
        cover.put("allowedHosts", hosts);
        cover.put("hostRewrites", new JSONObject());
        config.put("cover", cover);

        // ── web ─────────────────────────────────────────────────────────
        JSONObject web = new JSONObject();
        web.put("firstPageSize", 24);
        web.put("pageSize", OpenListAdapter.PER_PAGE);

        JSONObject paths = new JSONObject();
        paths.put("home", "/api/home");
        paths.put("detail", "/api/videos/{id}");
        paths.put("episode", "/api/videos/{id}/play?ep={episode}");
        paths.put("search", "/api/search?q={keyword}&page={page}");
        paths.put("category", "/api/videos?cate_id={cateId}&page={page}");
        paths.put("tags", "/api/tags");
        // 标签落地页也是「按目录列作品」—— 标签的 slug 就是目录路径。
        paths.put("tag-list", "/api/videos?cate_id={slug}&page={page}");
        web.put("paths", paths);

        // 分类表：slug → 目录路径。{cateId} 会被 encodeURIComponent，
        // 到 Java 侧 param() 再解回来，所以这里写裸路径就行。
        JSONObject categories = new JSONObject();
        JSONObject ui = new JSONObject();
        JSONArray tabs = new JSONArray();
        JSONObject ids = new JSONObject();
        JSONObject labels = new JSONObject();
        JSONObject titles = new JSONObject();
        JSONObject leads = new JSONObject();
        JSONArray popular = new JSONArray();
        JSONArray moreSlugs = new JSONArray();

        int shown = 0;
        for (int i = 0; i < roots.length(); i++) {
            String name = roots.optString(i, "");
            if (name.length() == 0) continue;
            // 空大类（asmr3 / asmr5 实测是空的）不进 Tab —— 点进去一片空白比不给入口更糟
            if (OpenListAdapter.dirTotal(base, "/" + name) == 0) {
                log("openlist：/ " + name + " 是空的，跳过");
                continue;
            }
            categories.put(name, "/" + name);
            if (shown >= 5) {
                // 多出来的折进「更多」，落地页是根目录
                moreSlugs.put(name);
                continue;
            }
            JSONObject tab = new JSONObject();
            tab.put("key", name);
            tab.put("label", name);
            tab.put("icon", iconFor(name, shown));
            tabs.put(tab);

            ids.put(name, new JSONArray().put(name));
            labels.put(name, new JSONArray().put(name));
            titles.put(name, name);
            leads.put(name, "目录 /" + name + " 下的作品。");
            if (shown < 4) popular.put(new JSONArray().put(name).put(name));
            shown++;
        }
        if (moreSlugs.length() > 0) {
            categories.put("more", "/");
            JSONObject tab = new JSONObject();
            tab.put("key", "more");
            tab.put("label", "更多");
            tab.put("icon", "grid");
            tabs.put(tab);
            ids.put("more", moreSlugs);
            JSONArray moreLabels = new JSONArray();
            for (int i = 0; i < moreSlugs.length(); i++) moreLabels.put(moreSlugs.optString(i, ""));
            labels.put("more", moreLabels);
            titles.put("more", "更多目录");
            leads.put("more", "其余的目录。");
        }
        web.put("categories", categories);

        // ui 必须在 web 里面 —— 页面读的是 window.HG_SITE.ui，而 HG_SITE 就是
        // 这个 web 块。放顶层等于没写，底部导航会一直是模板里那套「剧场 / AI」。
        ui.put("tabs", tabs);
        ui.put("categoryIds", ids);
        ui.put("categoryLabels", labels);
        ui.put("categoryTitles", titles);
        ui.put("categoryLeads", leads);
        ui.put("popularTags", popular);
        ui.put("publicPopularTags", new JSONArray());
        web.put("ui", ui);
        config.put("web", web);

        config.put("_note", "OpenList 文件站（" + hostOf(base) + "）。卡片是作品目录，"
                + "详情里的分集就是目录里的音频/视频文件，播放走 /api/fs/get 换直链。");
        return config;
    }

    @Override public byte[] fetch(SiteProfile profile, String path) throws Exception {
        return OpenListAdapter.fetch(path, profile);
    }

    @Override public String check(JSONObject config) {
        return checkOrigin(config);
    }

    @Override public String identity(JSONObject config) {
        String origin = config.optString("origin", "").trim();
        return origin.length() == 0 ? null : "hgsrc:openlist|" + origin;
    }

    // ── 探测用的小工具 ────────────────────────────────────────────────

    /** 站点根：scheme://host[:port]。路径一律丢掉 —— 用户可能粘的是某一层目录。 */
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

    /** GET /api/public/settings，认出来返回 data，认不出返回 null。 */
    private static JSONObject settings(String base) {
        try {
            byte[] raw = AiiHttp.getRaw(base + "/api/public/settings",
                    SiteCfg.browserHeaders(), 20);
            if (raw == null || raw.length == 0) return null;
            JSONObject json = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            if (json.optInt("code", 0) != 200) return null;
            JSONObject data = json.optJSONObject("data");
            if (data == null) return null;
            // AList / OpenList 的指纹字段。别的站在 /api/public/settings 上
            // 也可能回 JSON，但这几个字段是这套程序独有的。
            if (data.has("allow_indexed") || data.has("default_page_size")
                    || data.has("favicon") || data.has("announcement")) {
                return data;
            }
            return null;
        } catch (Throwable e) {
            log("openlist 指纹失败：" + e);
            return null;
        }
    }

    private static String iconFor(String name, int index) {
        if (name.startsWith("asmr2")) return "sparkle";
        if (index == 0) return "film";
        String[] icons = { "tv", "star", "grid" };
        return icons[Math.min(index - 1, icons.length - 1)];
    }
}
