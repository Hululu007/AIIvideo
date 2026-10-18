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

/**
 * 适配器共用的配置拼装小工具。
 *
 * 原来这一坨全在 UserSource 里，和「源管理」「代理核心」「播放器」挤在一个文件。
 * 它们只被各个站点适配器调用 —— 谁生成配置，工具就跟着谁走。
 */
final class SiteCfg {
    private SiteCfg() {}

    static JSONArray oneArray(String value) {
        JSONArray a = new JSONArray();
        a.put(value);
        return a;
    }

    /**
     * 一个导航入口。key 同时是 categoryIds / categoryLabels / categoryTitles 的键，
     * 也是前端 data-nav 的值 —— 三处必须一字不差，否则那个入口点进去是空的。
     */
    static JSONObject tab(String key, String label, String icon) throws Exception {
        JSONObject t = new JSONObject();
        t.put("key", key);
        t.put("label", label);
        t.put("icon", icon);
        return t;
    }

    /** [slug, 显示名] —— 标签表就是这个形状。 */
    static JSONArray pair(String slug, String name) {
        JSONArray a = new JSONArray();
        a.put(slug);
        a.put(name);
        return a;
    }

    /**
     * JSONObject.opt() 拿不到时返回 null，String.valueOf(null) 是字符串 "null" ——
     * 那个会当成合法的 type_id 用下去，最后变成一个点了没反应的空入口。
     */
    static String plain(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).trim();
        return "null".equals(text) ? "" : text;
    }

    /**
     * 首页那排标签挑哪几个。每个频道轮流取一个，取满为止；还剩位置就补上那些没有
     * 子分类的顶层，最后再按原顺序兜底。
     *
     * 为什么不从头截 8 个：`class[]` 是按 type_id 排的，前 8 个大概率全属于同一个
     * 频道（电影片底下就一长串），首页那排看着像只有电影。轮流取出来的分布均匀。
     *
     * 返回 [[slug, 名字], …]。slug 在 CMS 那边就是 type_id —— 前端拿它填 tag-list
     * 路径里的 {slug}，直接当 cate_id 用。
     */
    static JSONArray hotTags(LinkedHashMap<String, JSONObject> byId,
                                     ArrayList<String> channels,
                                     LinkedHashMap<String, ArrayList<String>> children,
                                     ArrayList<String> bare,
                                     ArrayList<String> order,
                                     int want) {
        JSONArray out = new JSONArray();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        int round = 0;
        while (!channels.isEmpty() && out.length() < want && round < 64) {
            boolean added = false;
            for (int i = 0; i < channels.size() && out.length() < want; i++) {
                ArrayList<String> kids = children.get(channels.get(i));
                if (round >= kids.size()) continue;
                String id = kids.get(round);
                if (seen.add(id)) { out.put(pair(id, nameOf(byId, id))); added = true; }
            }
            if (!added) break;              // 所有频道的子分类都取完了
            round++;
        }
        for (int i = 0; i < bare.size() && out.length() < want; i++) {
            String id = bare.get(i);
            if (seen.add(id)) out.put(pair(id, nameOf(byId, id)));
        }
        // 整棵树都没有层级（或者顶层全没有子分类）：只能按原顺序取。
        for (int i = 0; i < order.size() && out.length() < want; i++) {
            String id = order.get(i);
            if (seen.add(id)) out.put(pair(id, nameOf(byId, id)));
        }
        return out;
    }

    static String nameOf(LinkedHashMap<String, JSONObject> byId, String id) {
        JSONObject c = byId.get(id);
        return c == null ? id : c.optString("type_name", id);
    }

    /** 导航上的字要短。「电影片/综艺片/动漫片」去掉尾巴那个片更好看，也更塞得下。 */
    static String shortLabel(String name) {        String text = name == null ? "" : name.trim();
        if (text.length() > 2 && text.endsWith("片")) text = text.substring(0, text.length() - 1);
        return text.length() == 0 ? "分类" : text;
    }

    /**
     * 按名字猜个图标 —— 猜不中就用 grid，不影响功能，只是几个入口长得不一样好认。
     * 顺序有讲究：动漫片同时命中「动漫」和「片」，得先判动漫。
     */
    static String iconFor(String name) {
        if (name.contains("动漫") || name.contains("动画") || name.contains("漫画")) return "sparkle";
        if (name.contains("综艺") || name.contains("娱乐") || name.contains("真人秀")) return "star";
        if (name.contains("电影")) return "film";
        if (name.contains("剧") || name.contains("电视")) return "tv";
        if (name.contains("短")) return "fire";
        return "grid";
    }

    static JSONArray arr(String value) {
        JSONArray a = new JSONArray();
        a.put(value);
        return a;
    }

    /**
     * 一个角色的候选键列表，按顺序取第一个有值的。
     * 苹果CMS 那边一个概念常有两三个名字，这张表就是那件事的唯一真相 ——
     * Java 不认字段名，只按它把命中的键原样搬过去。
     */
    static JSONArray arr2(String first, String second) {
        JSONArray a = new JSONArray();
        a.put(first);
        a.put(second);
        return a;
    }

    static boolean hasHierarchy(JSONObject config) {
        JSONArray classes = config == null ? null : config.optJSONArray("class");
        for (int i = 0; classes != null && i < classes.length(); i++) {
            JSONObject c = classes.optJSONObject(i);
            if (c != null && plain(c.opt("type_pid")).length() > 0) return true;
        }
        return false;
    }

    /**
     * 把 donor 里的 type_pid 按 type_id 补进 target，只补缺的。
     * 返回补上的条数 —— 0 多半意味着两边根本不是同一张表，那就不该硬套。
     */
    static int mergeParents(JSONArray target, JSONArray donor) {
        if (target == null || donor == null) return 0;
        LinkedHashMap<String, String> pidById = new LinkedHashMap<String, String>();
        for (int i = 0; i < donor.length(); i++) {
            JSONObject c = donor.optJSONObject(i);
            if (c == null) continue;
            String id = plain(c.opt("type_id"));
            String pid = plain(c.opt("type_pid"));
            if (id.length() > 0 && pid.length() > 0) pidById.put(id, pid);
        }
        int filled = 0;
        for (int i = 0; i < target.length(); i++) {
            JSONObject c = target.optJSONObject(i);
            if (c == null || plain(c.opt("type_pid")).length() > 0) continue;
            String pid = pidById.get(plain(c.opt("type_id")));
            if (pid == null) continue;
            try { c.put("type_pid", pid); filled++; } catch (Exception ignored) {}
        }
        return filled;
    }

    /** 从探测响应里把所有 https 链接的域名捞出来，去重，限量。 */
    /** 取探测响应里第一条的 id，用来单独探详情。 */
    static String firstVideoId(JSONObject probe) {
        JSONArray list = probe == null ? null : probe.optJSONArray("list");
        if (list == null || list.length() == 0) return "";
        JSONObject first = list.optJSONObject(0);
        if (first == null) return "";
        String id = String.valueOf(first.opt("vod_id"));
        return "null".equals(id) || "0".equals(id) ? "" : id;
    }

    /** 往地址上补查询参数，已经在带查询的用 & 接。 */
    static String withParams(String url, String query) {
        String base = url.trim();
        int q = base.indexOf('?');
        String head = q < 0 ? base : base.substring(0, q);
        return head + (head.indexOf('?') >= 0 ? "&" : "?") + query;
    }

    /**
     * 从列表页里按给定正则抓封面域名。抓不到返回空数组 —— 调用方都有写死的兜底，
     * 所以这里失败不算错。
     *
     * 去重，最多取 4 个（CDN 轮换不会超过这个数；多了只会让白名单变长）。
     */
    static JSONArray probeCoverHosts(String base, String hostPattern) {
        JSONArray out = new JSONArray();
        try {
            byte[] body = AiiHttp.getRaw(base + "/", 20);
            if (body == null || body.length == 0) return out;
            String html = new String(body, "UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(hostPattern).matcher(html);
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            while (m.find() && out.length() < 4) {
                String host = m.group(1);
                if (host == null || host.length() == 0) continue;
                if (!seen.add(host)) continue;
                out.put(host);
            }
        } catch (Throwable e) {
            log("封面域名探测失败，用默认那一个：" + e);
        }
        return out;
    }

    static JSONArray coverHostsOf(JSONObject probe) {
        java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<String>();
        collectHosts(probe, hosts, 0);
        JSONArray out = new JSONArray();
        for (String h : hosts) {
            if (out.length() >= 24) break;
            out.put(h);
        }
        return out;
    }

    static void collectHosts(Object node, java.util.LinkedHashSet<String> out, int depth) {
        if (node == null || depth > 6 || out.size() >= 24) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) collectHosts(o.opt(keys.next()), out, depth + 1);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectHosts(a.opt(i), out, depth + 1);
        } else if (node instanceof String) {
            String text = (String) node;
            if (!text.startsWith("https://")) return;
            try {
                String host = new java.net.URI(text).getHost();
                if (host != null && host.length() > 0) out.add(host);
            } catch (Exception ignored) {}
        }
    }

    // ── 请求头 ────────────────────────────────────────────────────────

    /**
     * 浏览器样子的请求头。
     *
     * 2026-10-18 实测，两个不同的站栽在同一件事上，而且挡法还不一样：
     *
     *   mrds66   UA = okhttp/4.10.0 → **403**（首页还行，/category/ 和 /archives/ 全 403）
     *            UA = Dalvik / 安卓 Chrome → 200
     *   porn91   UA = okhttp/4.10.0 → 200 但 **0 字节**（静默空响应，最难查）
     *            UA = 不带 / 安卓 Chrome → 200，内容正常
     *
     * 所以 OkHttp 那个默认 UA 不能往外发。给一份浏览器头是最省事的收口 ——
     * 别在每个适配器里各抄一遍。
     */
    private static final java.util.Map<String, String> BROWSER_HEADERS = buildBrowserHeaders();

    static java.util.Map<String, String> browserHeaders() {
        return BROWSER_HEADERS;
    }

    private static java.util.Map<String, String> buildBrowserHeaders() {
        java.util.Map<String, String> h = new java.util.LinkedHashMap<String, String>();
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36");
        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        return java.util.Collections.unmodifiableMap(h);
    }

    // ── 各适配器共用的必填项校验 ──────────────────────────────────────

    /** 接口型（cms / agedm）：只要一个 https 的 api 地址 + web.paths。 */
    static String checkApi(JSONObject config) {
        String api = config.optString("api", "").trim();
        if (api.length() == 0) return "缺少 api 地址";
        if (!api.startsWith("https://")) return "api 地址必须是 https 开头";
        JSONObject web = config.optJSONObject("web");
        if (web == null || web.optJSONObject("paths") == null) return "缺少 web.paths";
        return null;
    }

    /** 单站 HTML 型（hanime1 / mrds66 / porn91）：只要站点根 + web.paths。 */
    static String checkOrigin(JSONObject config) {
        String origin = config.optString("origin", "").trim();
        if (origin.length() == 0) return "缺少 origin（这个源要填站点地址）";
        if (!origin.startsWith("https://")) return "origin 必须是 https 开头";
        JSONObject web = config.optJSONObject("web");
        if (web == null || web.optJSONObject("paths") == null) return "缺少 web.paths";
        return null;
    }
}
