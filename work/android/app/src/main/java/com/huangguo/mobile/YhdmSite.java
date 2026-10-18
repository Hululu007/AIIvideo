package com.huangguo.mobile;

import static com.huangguo.mobile.SiteCfg.*;
import static com.huangguo.mobile.UserSource.*;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;

/**
 * 樱花动漫（yhdm.one）。
 *
 * 纯字符串匹配的域名识别，零网络开销，排在所有会发请求的指纹探测前面
 * （AdaptersTest 那条排序硬规则）。站点实测（2026-10-18，Mac curl）：
 *
 *   无 Cloudflare、无 UA 检查 —— 裸 curl / okhttp 默认 UA 全 200；
 *   封面同域（/cover/ /cover2/），不用跨域白名单；
 *   播放地址在 /_get_plays/{id}/{ep} 的 JSON 里，第三方采集站的 m3u8 直链，
 *   本站自己不做签名。
 */
final class YhdmSite implements SiteAdapter {
    @Override public String id() { return "yhdm"; }

    /** 已知域名。樱花动漫一族常年换域（imomoe / yhdm…），这里只列实测过的。 */
    static final String[] HOSTS = { "yhdm.one", "imomoe.link", "imomoe.la" };

    @Override public boolean matchesUrl(String url) {
        String host = hostOf(url);
        if (host.length() == 0) return false;
        for (int i = 0; i < HOSTS.length; i++) {
            if (host.equals(HOSTS[i]) || host.endsWith("." + HOSTS[i])) return true;
        }
        return false;
    }

    static String hostOf(String url) {
        try {
            String host = new URI(url == null ? "" : url.trim()).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 探针：拉一次首页，认指纹。
     *
     * 判据（都是这个站独有的组合，一大票 bootstrap 风格站都有 container/row）：
     *   /vod/{数字}.html          详情路由
     *   /vod-play/{数字}/…        播放路由
     *   /static/css/yhdm.css      站点名命名的样式表
     *   樱花动漫                   页面标题/品牌词
     *
     * 四条命中两条、且至少有 5 条 /vod/ 链接才算探通 —— 只有指纹词没有卡片的
     * 时候，拿到的是错误页或被劫持的跳转页。
     */
    static String probeBase(String url) throws Exception {
        String base = originRoot(url);
        byte[] body = AiiHttp.getRaw(base + "/", SiteCfg.browserHeaders(), 15);
        if (body == null || body.length == 0) {
            log("yhdm 探针拿到空响应 —— 站点可能被墙或改了首页");
            return null;
        }
        if (probeHtml(body) == null) return null;
        return base;
    }

    /** 指纹判据（独立成纯函数，离线可测）。通过返回站点根，不通过返回 null。 */
    static String probeHtml(byte[] raw) {
        if (raw == null || raw.length == 0) return null;
        String html = new String(raw, java.nio.charset.StandardCharsets.UTF_8);

        int hits = 0;
        if (html.contains("/static/css/yhdm.css")) hits++;
        if (html.contains("樱花动漫")) hits++;
        if (html.contains("/vod-play/")) hits++;
        if (html.contains("my-thumbnail")) hits++;
        if (hits < 2) return null;

        int cards = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/vod/\\d+\\.html").matcher(html);
        while (m.find() && cards < 5) cards++;
        if (cards < 5) {
            log("yhdm 首页只有 " + cards + " 条影片链接 —— 不算探通");
            return null;
        }
        return html;
    }

    /** 按地址识别（load() 第一轮走这个，不带 body）。探针失败就抛。 */
    @Override public JSONObject probe(String url) throws Exception {
        return probeWithBody(url, null);
    }

    /** yhdm → 我们的配置。探针失败就抛，不留一份点了没反应的配置。 */
    @Override public JSONObject probe(String url, JSONObject body) throws Exception {
        return probeWithBody(url, body);
    }

    private JSONObject probeWithBody(String url, JSONObject body) throws Exception {
        String base = probeBase(url);
        if (base == null) {
            throw new IOException("没认出这是「樱花动漫」。判断依据：首页里要同时有"
                    + " yhdm.css、樱花动漫、/vod-play/、my-thumbnail 里至少两条，"
                    + "且至少 5 条 /vod/ 链接。站点可能改版、被墙，或者填的不是这个站。");
        }
        return buildConfig(base);
    }

    static JSONObject buildConfig(String base) throws Exception {
        JSONObject config = new JSONObject();
        config.put("adapter", "yhdm");
        config.put("origin", base);

        // 封面全在本站域（/cover/{id}.jpg 详情页、/cover2/{id}.jpg 列表页），
        // 没有 AES、没有图床跨域 —— allowedHosts 一条就够。
        JSONObject cover = new JSONObject();
        cover.put("mode", "plain");
        cover.put("allowedHosts", oneArray(new URI(base).getHost()));
        cover.put("hostRewrites", new JSONObject());
        config.put("cover", cover);

        // ── 网络路由：只把站点入口交给代理 ───────────────────────────
        //
        // 2026-10-18 真机：内置核心开在荷兰节点，这个站**播放全线失败**
        // （403 / TLS 被掐），而 12 条线路的 CDN 直连全是好的。
        // 手动往 DirectHosts 填了 16 个采集站域名才播通 —— 但那些域名天天换，
        // 白名单追不上。
        //
        // 规律是反向的：墙只挡入口域（yhdm.one），播放 CDN 九成在境内。
        // 所以这里声明「只有 yhdm.one 走代理」，其余一切直连 —— 覆盖封面、
        // 分片、以及明天才出现的第 17 个采集站。
        JSONObject net = new JSONObject();
        JSONArray proxyHosts = new JSONArray();
        proxyHosts.put(YhdmSite.hostOf(base));
        net.put("proxy", proxyHosts);
        config.put("net", net);

        JSONObject web = new JSONObject();
        // 实测每页 18 条（/list 分页卡片）。
        web.put("firstPageSize", 18);
        web.put("pageSize", 18);

        JSONObject paths = new JSONObject();
        paths.put("home", "/api/home");
        paths.put("detail", "/api/detail/{id}");
        paths.put("episode", "/api/play/{id}?ep={episode}");
        paths.put("search", "/api/search?q={keyword}&page={page}");
        paths.put("category", "/api/list?cate={cateId}&page={page}");
        paths.put("tags", "/api/tags");
        paths.put("tag-list", "/api/list?cate={slug}&page={page}");
        web.put("paths", paths);

        // ── fields 别名表：默认表里 episodeNumber 只认 ep_num，source 只认
        // video_url（AGE动漫/CMS 的字段名）。适配器返回的就是我们自己的键名
        // （id/title/episodeNumber/source），漏声明的下场是「数据都在，界面全是
        // undefined」—— 2026-10-18 真机踩到：选集格子全部写着 undefined，
        // 因为 pick(entry,'episodeNumber') 走默认表取空。照 MissavSite 的规矩，
        // 适配器返回什么键名，这张表里就必须声明同名候选。
        JSONObject fields = new JSONObject();
        fields.put("id", arr("id"));
        fields.put("title", arr("title"));
        fields.put("cover", arr("cover"));
        fields.put("score", arr("score"));
        fields.put("tags", arr("tags"));
        fields.put("description", arr("description"));
        fields.put("badge", arr("badge"));
        fields.put("category", arr("category"));
        fields.put("episodeCount", arr("episodeCount"));
        fields.put("episodeNumber", arr2("episodeNumber", "ep_num"));
        JSONArray fSrc = new JSONArray();
        fSrc.put("source"); fSrc.put("url"); fSrc.put("videoUrl");
        fields.put("source", fSrc);
        fields.put("duration", arr("duration"));
        fields.put("slug", arr("slug"));
        fields.put("tagName", arr2("tagName", "name"));
        fields.put("code", arr("code"));
        web.put("fields", fields);
        // shapes 用默认的 pagination.page/pages —— 适配器就是按这个形状返回的。

        // ── 分类 ────────────────────────────────────────────────────────
        // 站点导航的分类：/list/?country=jp /list/?type=movie /list?genre=… /list?region=…
        // slug 直接当 cateId，适配器拼回 /list/?{查询串}。
        JSONObject categories = new JSONObject();
        for (int i = 0; i < YhdmAdapter.CATEGORIES_TABLE.length; i++) {
            categories.put(YhdmAdapter.CATEGORIES_TABLE[i][0], YhdmAdapter.CATEGORIES_TABLE[i][0]);
        }
        web.put("categories", categories);

        // ── 底部导航 ────────────────────────────────────────────────────
        JSONObject ui = new JSONObject();
        JSONArray tabs = new JSONArray();
        tabs.put(tab("jp", "日本", "sparkle"));
        tabs.put(tab("cn", "国产", "tv"));
        tabs.put(tab("us", "欧美", "film"));
        tabs.put(tab("movie", "电影", "star"));
        tabs.put(tab("more", "更多", "grid"));
        ui.put("tabs", tabs);

        JSONArray more = new JSONArray();
        String[] rest = { "剧场版", "OVA", "TV", "latest" };
        for (int i = 0; i < rest.length; i++) more.put(rest[i]);

        JSONObject cids = new JSONObject();
        cids.put("jp", oneArray("jp"));
        cids.put("cn", oneArray("cn"));
        cids.put("us", oneArray("us"));
        cids.put("movie", oneArray("movie"));
        cids.put("more", more);
        ui.put("categoryIds", cids);

        JSONObject clabels = new JSONObject();
        clabels.put("jp", oneArray("日本动漫"));
        clabels.put("cn", oneArray("国产动漫"));
        clabels.put("us", oneArray("欧美动漫"));
        clabels.put("movie", oneArray("动漫电影"));
        JSONArray moreLabels = new JSONArray();
        for (int i = 0; i < more.length(); i++) moreLabels.put(more.optString(i, ""));
        clabels.put("more", moreLabels);
        ui.put("categoryLabels", clabels);

        JSONObject ctitles = new JSONObject();
        ctitles.put("jp", "日本动漫");
        ctitles.put("cn", "国产动漫");
        ctitles.put("us", "欧美动漫");
        ctitles.put("movie", "动漫电影");
        ctitles.put("more", "更多分类");
        ui.put("categoryTitles", ctitles);

        JSONArray popular = new JSONArray();
        String[][] picks = {
            { "jp", "日本动漫" },
            { "cn", "国产动漫" },
            { "剧场版", "剧场版" },
            { "latest", "最近更新" },
        };
        for (int i = 0; i < picks.length; i++) popular.put(pair(picks[i][0], picks[i][1]));
        ui.put("popularTags", popular);
        ui.put("publicPopularTags", new JSONArray());

        web.put("ui", ui);
        config.put("web", web);

        config.put("_note", "樱花动漫（yhdm.one）。服务端渲染，无接口无鉴权无 UA 检查。"
                + "详情 /vod/{id}.html；播放地址在 /_get_plays/{id}/{ep} 的 JSON 里"
                + "（video_plays[].play_data，第三方采集站 m3u8 直链，多条线路），"
                + "本站不做签名，但线路地址会换，播放必须每次现取。"
                + "封面同域 /cover2/{id}.jpg。"
                + "ep 的形态有两种：ep{数字}（多集）和 zheng_pian/hd（单集剧场版的线路名），"
                + "适配器把详情页解析出的 ep 列表原样当 episode 标识用。"
                + "适配器见 YhdmAdapter.java。");
        return config;
    }

    /**
     * 给旧配置补 {@code net} 块（2026-10-18 之前的配置里没有）。
     *
     * 纯本地：只要知道 origin 就能推导「只有站点入口走代理」，不用探针。
     * 不补的话老用户升上来，CDN 仍然被全局代理拖走 —— 症状和没修一样，
     * 而代码看起来是完全正确的。
     */
    @Override public void patch(JSONObject config) {
        try {
            if (config.optJSONObject("net") != null) return;
            String origin = config.optString("origin", "").trim();
            String host = hostOf(origin);
            if (host.length() == 0) return;
            JSONObject net = new JSONObject();
            JSONArray list = new JSONArray();
            list.put(host);
            net.put("proxy", list);
            config.put("net", net);
            log("yhdm 旧配置已补 net 路由（代理域 " + host + "）");
        } catch (Throwable ignored) {}
    }

    @Override public byte[] fetch(SiteProfile profile, String path) throws Exception {
        return YhdmAdapter.fetch(path, profile);
    }

    @Override public String check(JSONObject config) {
        return SiteCfg.checkOrigin(config);
    }

    @Override public String identity(JSONObject config) {
        String origin = config.optString("origin", "").trim();
        return origin.length() == 0 ? null : "hgsrc:yhdm|" + origin + "/vod/";
    }

    /** 从任意 URL 提取 https://host 形式的站点根。 */
    static String originRoot(String url) {
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            if (uri.getHost() == null) return url.replaceAll("/+$", "");
            return scheme + "://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (Exception e) {
            return url.replaceAll("/+$", "");
        }
    }
}
