package com.huangguo.mobile;

import static com.huangguo.mobile.SiteCfg.*;
import static com.huangguo.mobile.UserSource.*;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** 每日大赛（mrds66）：单站 HTML 型 + 域名/指纹双重识别。 */
final class Mrds66Site implements SiteAdapter {
    @Override public String id() { return "mrds66"; }

    /**
     * 地址是不是「每日大赛」（mrds66.com 那一族镜像）。
     *
     * ── 为什么先认域名、再认指纹 ────────────────────────────────────────
     * 这类站的域名换得比版本还快：主页上常年挂着备用域（发布页里能看到
     * mrdsv2.com / mrds.com 这种），把域名写死等于「明天这个源就废了，
     * 而代码没有任何问题」。所以认出站点的**证据是页面结构**，域名只是
     * 一个「省掉一次请求」的快捷方式。
     *
     * ── 指纹四条，命中两条才算 ─────────────────────────────────────────
     *
     *   /archives/{数字}/               文章链接（Typecho 默认路由）
     *   post-card-title                 卡片标题 class（Mirages 主题）
     *   loadBannerDirect(               懒加载封面（这个主题独有的写法）
     *   /category/{slug}/               分类路由
     *
     * 单看 `/archives/` 不够 —— 一大票 Typecho 站都是这个路由。
     * 但 `post-card-title` + `loadBannerDirect(` 同时出现，基本只有 Mirages 主题
     * 的站会有。实测首页 45 个 article 里 30 个是卡片、15 个是广告位，四个特征全在。
     *
     * ── 为什么探测是安全的 ──────────────────────────────────────────────
     * 只在「域名不认识」时发生。而且这个站**不查 UA**（裸 curl / Dalvik / okhttp /
     * 安卓 Chrome 四种全是 200、页面大小一字不差），也不在 Cloudflare 的
     * TLS 指纹闸后面 —— 探针不需要伪装，也不需要短超时。
     */
    public boolean matchesUrl(String url) {
        String raw = url == null ? "" : url.trim();
        if (raw.length() == 0) return false;

        // ① 路径特征：贴的是文章页或分类页，直接认
        if (raw.contains("/archives/") || raw.contains("mrds66.com")) {
            return true;
        }

        String host;
        try {
            host = new URI(raw).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) return false;

        // ② 已知域名
        for (int i = 0; i < MRDS66_HOSTS.length; i++) {
            if (host.equals(MRDS66_HOSTS[i]) || host.endsWith("." + MRDS66_HOSTS[i])) {
                return true;
            }
        }

        // ③ 指纹探测
        try {
            return probeMrds66(url) != null;
        } catch (Throwable e) {
            log("mrds66 指纹探测失败（按不认识处理）：" + e);
            return false;
        }
    }

    /**
     * 已知的每日大赛域名。**这不是白名单** —— 不在表里的镜像照样会被
     * 指纹探测认出来（见 isMrds66 第 ③ 步）。这里只是省掉一次请求。
     */
    static final String[] MRDS66_HOSTS = {
        "mrds66.com",
    };

    /**
     * 探针：拉一次首页，看指纹。认出来返回站点根，认不出来返回 null。
     *
     * 判据和 isMrds66 里那段注释一致 —— 关键是「至少 5 条文章链接」这一条：
     * 只有指纹词没有卡片，说明拿到的是错误页或者被墙的跳转页。
     */
    static String probeMrds66(String url) {
        try {
            String base = url.replaceAll("/+$", "");
            // 用户填的可能是任意路径，统一回到站点根再探
            try {
                URI uri = new URI(base);
                base = uri.getScheme() + "://" + uri.getHost()
                        + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            } catch (Exception ignored) {}

            byte[] body = AiiHttp.getRaw(base + "/", SiteCfg.browserHeaders(), 20);
            if (body == null || body.length == 0) {
                log("mrds66 探针拿到空响应 —— 站点可能被墙或改了首页");
                return null;
            }
            String html = new String(body, "UTF-8");

            int hits = 0;
            if (html.contains("post-card-title")) hits++;
            if (html.contains("loadBannerDirect(")) hits++;
            if (html.contains("/category/")) hits++;
            if (html.contains("/archives/")) hits++;
            if (hits < 2) return null;

            // 数一遍文章链接，确认首页真的有卡片（不是「主题对了但站被清空」）
            int cards = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("/archives/").matcher(html);
            while (m.find() && cards < 5) cards++;
            if (cards < 5) {
                log("mrds66 首页只有 " + cards + " 条文章链接 —— 不算探通");
                return null;
            }

            log("mrds66 探针通过：" + base + "，首页文章链接 " + cards + "+ 条");
            return base;
        } catch (Throwable e) {
            log("mrds66 探针失败：" + e);
            return null;
        }
    }

    /**
     * 每日大赛 → 我们的配置。
     *
     * 和 hanime1 一样是「没有接口可探」的站，探针拿页面本身当判据
     * （见 probeMrds66）。探针失败就抛，不留一份点了没反应的配置。
     *
     * ── origin 为什么必须是站点根 ──────────────────────────────────────
     * 适配器自己往上拼 `/category/`、`/archives/`、`/page/`，所以 origin 只能是
     * 裸域名。SiteProfile 的构造器会校验这一条（带路径/查询串直接抛
     * 「站点必须是 HTTPS 域名」）。
     *
     * ── 封面白名单 ────────────────────────────────────────────────────
     * 封面全在图床 `pic.jtzatly.cn` 上，**不在站点域**。allowedHosts 是硬闸
     * （不在表里直接抛「无效封面域名」，不是降级），漏了就是「一进首页封面全挂」。
     * 地址藏在 `loadBannerDirect('https://…')` 的参数里，探测时顺手抓出来。
     */
    public JSONObject probe(String url) throws Exception {
        String base = probeMrds66(url);
        if (base == null) {
            throw new IOException("没认出这是「每日大赛」。判断依据：首页里要同时有"
                    + " post-card-title、loadBannerDirect(、/category/、/archives/ 里至少两条，"
                    + "且至少 5 条文章链接。一条都对不上 —— 站点可能改版、被墙，"
                    + "或者填的不是这个站。");
        }
        log("mrds66 探测通过：" + base);

        JSONObject config = new JSONObject();
        config.put("adapter", "mrds66");
        config.put("origin", base);

        // ── 封面 ────────────────────────────────────────────────────────
        //
        // 2026-10-18 实测：图床上的文件**不是图片，是 AES 密文**。
        //
        //   4d 3c d8 41 d3 9b 11 79 …   ← 214048 字节，Content-Type 还是
        //                                  binary/octet-stream
        //
        // 站点自己在前端解：`/usr/plugins/tbxw/js/zzz.js` 里的 decryptImage()，
        // 键和 IV 是两串 ASCII 码拼出来的（`57_55_98_…`.split('_') 再 fromCharCode）。
        // 解出来是标准的 FF D8 FF DB（JPEG）和 GIF89a，长度对得上、PKCS7 补齐。
        //
        // 之前这里写的是 "plain"，表现是首页卡片全是灰底 + 标题首字，一张图都没有。
        // 现在按 aes-cbc 走，密钥用站点自己那份。
        //
        // 注意 IV 和内置模板（site-default.json）**差一个字符**：
        //   内置  97b60374abc2fbe1
        //   本站  97b60394abc2fbe1
        // 抄错一位就是「封面解密结果无效」，别把两份混用。
        JSONObject cover = new JSONObject();
        cover.put("mode", "aes-cbc");
        cover.put("transformation", "AES/CBC/PKCS5Padding");
        cover.put("key", "f5d965df75336270");
        cover.put("iv", "97b60394abc2fbe1");
        cover.put("keyEncoding", "text");
        JSONArray hosts = new JSONArray();
        hosts.put("pic.jtzatly.cn");
        // 把首页里真用到的封面域名补进来 —— 这个站换图床比换域名还勤，
        // 写死一个等它换就是全站封面挂掉。地址在 loadBannerDirect('…') 里。
        JSONArray probed = probeCoverHosts(base,
                "loadBannerDirect\\(\\s*[\"']https?://([a-zA-Z0-9.-]+)/");
        for (int i = 0; i < probed.length(); i++) {
            String host = probed.optString(i, "");
            if (host.length() == 0) continue;
            boolean dup = false;
            for (int j = 0; j < hosts.length(); j++) {
                if (host.equalsIgnoreCase(hosts.optString(j, ""))) { dup = true; break; }
            }
            if (!dup) hosts.put(host);
        }
        cover.put("allowedHosts", hosts);
        cover.put("hostRewrites", new JSONObject());
        config.put("cover", cover);

        // ── web ─────────────────────────────────────────────────────────
        JSONObject web = new JSONObject();
        // 站点每页 30 条（首页 45 个 article 里 15 个是广告位，真卡也是 30）。
        // 写 30 是**跟随站点**，不是我们的偏好 —— 给别的数字不会让站点多返回，
        // 只会让「够不够翻页」的判断错。
        web.put("firstPageSize", 30);
        web.put("pageSize", 30);

        JSONObject paths = new JSONObject();
        paths.put("home", "/api/home");
        paths.put("detail", "/api/videos/{id}");
        // 单集站：{episode} 占位符只为接住模板参数，站点一个 id 只给一个播放器。
        paths.put("episode", "/api/videos/{id}/play?ep={episode}");
        paths.put("search", "/api/search?q={keyword}&page={page}");
        paths.put("category", "/api/videos?cate_id={cateId}&page={page}&size={size}");
        paths.put("tags", "/api/tags");
        web.put("paths", paths);

        // ── 分类 ────────────────────────────────────────────────────────
        // slug 直接当 cateId：适配器把它拼进 /category/{slug}/。一个没改，
        // 顺序照站点导航栏。
        JSONObject categories = new JSONObject();
        for (int i = 0; i < Mrds66Adapter.CATEGORIES.length; i++) {
            categories.put(Mrds66Adapter.CATEGORIES[i][0], Mrds66Adapter.CATEGORIES[i][0]);
        }
        web.put("categories", categories);

        // ── 底部导航 ────────────────────────────────────────────────────
        // 五个 tab：四个主分类 + 一个「更多」。
        // 主位给量大、且最能代表这个站的四个：每日大赛（主分类）、热搜吃瓜
        // （首页角标一半是它）、AI剧场（内容最独特）、影视国漫。剩下折进「更多」。
        JSONObject ui = new JSONObject();
        JSONArray tabs = new JSONArray();
        String[][] plan = {
            { "mrds", "每日大赛", "film" },
            { "rstt", "热搜吃瓜", "sparkle" },
            { "aijc", "AI剧场",   "star" },
            { "dypd", "影视国漫", "tv" },
            { "more", "更多",     "grid" },
        };
        for (int i = 0; i < plan.length; i++) {
            JSONObject tab = new JSONObject();
            tab.put("key", plan[i][0]);
            tab.put("label", plan[i][1]);
            tab.put("icon", plan[i][2]);
            tabs.put(tab);
        }
        ui.put("tabs", tabs);

        JSONArray more = new JSONArray();
        String[] rest = {
            "ztds", "xazd", "blyp", "fctg", "mhds", "lqdp", "jdsj", "mxwh",
            "smdh", "mtds", "ysds", "czds", "hjds", "tgds", "omjp", "qwcs",
        };
        for (int i = 0; i < rest.length; i++) more.put(rest[i]);

        JSONObject cids = new JSONObject();
        cids.put("mrds", oneArray("mrds"));
        cids.put("rstt", oneArray("rstt"));
        cids.put("aijc", oneArray("aijc"));
        cids.put("dypd", oneArray("dypd"));
        cids.put("more", more);
        ui.put("categoryIds", cids);

        JSONObject clabels = new JSONObject();
        clabels.put("mrds", oneArray("每日大赛"));
        clabels.put("rstt", oneArray("热搜吃瓜"));
        clabels.put("aijc", oneArray("AI剧场"));
        clabels.put("dypd", oneArray("影视国漫"));
        JSONArray moreLabels = new JSONArray();
        for (int i = 0; i < more.length(); i++) {
            moreLabels.put(mrdsLabelOf(more.optString(i, "")));
        }
        clabels.put("more", moreLabels);
        ui.put("categoryLabels", clabels);

        JSONObject ctitles = new JSONObject();
        ctitles.put("mrds", "每日大赛");
        ctitles.put("rstt", "热搜吃瓜");
        ctitles.put("aijc", "AI剧场");
        ctitles.put("dypd", "影视国漫");
        ctitles.put("more", "更多分类");
        ui.put("categoryTitles", ctitles);

        JSONObject cleads = new JSONObject();
        cleads.put("mrds", "站点主分类，量最大。");
        cleads.put("rstt", "首页角标一半是这个分区。");
        cleads.put("aijc", "AI 生成短剧。");
        cleads.put("dypd", "影视国漫。");
        cleads.put("more", "其余 16 个分类。");
        ui.put("categoryLeads", cleads);

        JSONArray popular = new JSONArray();
        String[][] picks = {
            { "mrds", "每日大赛" },
            { "mhds", "网红黑料" },
            { "fctg", "反差泄密" },
            { "mtds", "cos写真" },
        };
        for (int i = 0; i < picks.length; i++) {
            JSONArray pair = new JSONArray();
            pair.put(picks[i][0]);
            pair.put(picks[i][1]);
            popular.put(pair);
        }
        ui.put("popularTags", popular);
        ui.put("publicPopularTags", new JSONArray());

        web.put("ui", ui);
        config.put("web", web);

        config.put("_note", "每日大赛（mrds66.com）。Typecho + Mirages 主题，"
                + "服务端渲染，/api/* 全 404，数据全在 HTML 里。"
                + "播放器是一个 <div class=\"dplayer\" data-config='{…}'>，"
                + "里面的 video.url 是**带 auth_key 签名的 m3u8**（AES-128 加密分片，"
                + "key 在 ts.zhixunkeji.xyz 单独一个 URL）。签名绑时间戳和路径，"
                + "实测改一个字节就 400，所以**每次点播必须现抓详情页**，不能进缓存。"
                + "第 1 页的 URL 不带数字（/category/x/1/ 会 301），越界页回 404 不是空列表，"
                + "总页数只信页面里的 page-info。适配器见 Mrds66Adapter.java。");
        return config;
    }

    /** mrds66 的 slug → 显示名。查不到就原样返回（不猜）。 */
    static String mrdsLabelOf(String slug) {
        for (int i = 0; i < Mrds66Adapter.CATEGORIES.length; i++) {
            if (Mrds66Adapter.CATEGORIES[i][0].equals(slug)) return Mrds66Adapter.CATEGORIES[i][1];
        }
        return slug;
    }

    @Override public byte[] fetch(SiteProfile profile, String path) throws Exception {
        return Mrds66Adapter.fetch(path, profile);
    }

    @Override public String check(JSONObject config) {
        return SiteCfg.checkOrigin(config);
    }
}
