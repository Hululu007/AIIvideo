package com.huangguo.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * AIIvideo —— 苹果CMS 族适配器。
 *
 * ── 这是「族适配器」，不是「站适配器」─────────────────────────────────
 * 一个类覆盖**所有**跑苹果CMS 的站。2026-10-16 实测四家（暴风 bfzyapi、
 * 量子 cj.lziapi、非凡 ffzyapi、无尽 wujinapi）：顶层键完全一致，条目的字段
 * 交集 95 / 并集 95 —— 同族内是 100% 同构的。
 *
 * 所以「又多了一个站要不要改代码」的答案分两种：
 *   · 又一个苹果CMS 站   → 不用。填个地址就行（字段名不一样就改配置里的 fields）
 *   · 又一个别的族       → 要。但那是**一个族一次**，不是一站一次
 *
 * 判断一个站属于哪个族，看它的接口：`?ac=list` 回 `{code, class[], list[]}` 就是
 * 这个族。不是的话，这个类帮不上忙，得另写一个 —— 而且新的那个同样会按「族」摊。
 *
 * 把苹果CMS 的采集接口（`/api.php/provide/vod`）包成**我们内部那套 JSON 形状**，
 * 于是前端的 `adapters.js`（那 16 个字段别名角色）一行都不用改。
 *
 * ── 为什么要这一层 ──────────────────────────────────────────────────
 * 前端的调用约定是「发一个 /api/ 路径，拿回一个符合我们形状的 JSON」。
 * 这些路径从「黄果站的真实路径」升级成了**内部动作词汇表**，由适配器负责实现。
 * 代价是语义上绕一点（CMS 站没有 /api/videos/123），收益是前端零改动 ——
 * 而那层字段别名是前几轮辛苦搭起来的，复用它是这里最大的收益。
 *
 * ── 苹果CMS 的接口形状（2026-10-16 实测 api.apibdzy.com）─────────────
 *   取列表  GET ?ac=detail&t=<type_id>&pg=<page>
 *          → {code, page, pagecount, limit, total, list:[…], class:[…]}
 *   取详情  GET ?ac=detail&ids=<vod_id>
 *          → {code, list:[{…, vod_play_url, vod_play_from, vod_content}]}
 *
 * 注意 `class[]`（分类表）**和 list 在同一个响应里**，不带参数也返回 ——
 * 所以一次请求就能同时拿到列表和分类，不用分两次。
 *
 * ── 播放地址 ────────────────────────────────────────────────────────
 *   vod_play_from: "dbm3u8"                       线路名，多条用 $$$ 分隔
 *   vod_play_url:  "第1集$https://…/index.m3u8#第2集$…"
 *                  └ # 分集      └ $ 分「集名 / 地址」
 *
 * 里面是 **m3u8 直链**，WebView 的 HLS 播放器直接吃，不需要解析器或嗅探。
 * 但部分源的地址是**分享页**而不是 m3u8 —— 那种要 parses[] 才能解，一期不处理，
 * 遇到就明确报错，不静默失败（静默失败是这个项目里反复踩的坑）。
 */
public final class CmsAdapter {

    private static final int TIMEOUT = 12;

    /** 详情缓存。播放地址要靠它 —— CMS 没有「取第 N 集」这种接口，得先拿全量再挑。 */
    private static final Map<String, JSONObject> DETAIL_CACHE = new HashMap<String, JSONObject>();
    private static final int CACHE_LIMIT = 12;

    private CmsAdapter() {}

    // ── 对外入口 ──────────────────────────────────────────────────────

    /**
     * 按我们内部的路径词汇表取数据，返回**我们形状**的 JSON 字节。
     * 由 AiiHttp.get() 在 adapter == "cms" 时分流过来。
     */
    public static byte[] fetch(String path, SiteProfile profile) throws Exception {
        String api = profile == null ? "" : profile.rule("api");
        if (api == null || api.length() == 0) throw new Exception("配置里没有 api 字段");
        String base = normalizeApi(api);

        JSONObject result;
        if (path.startsWith("/api/home")) {
            result = home(base, profile);
        } else if (path.startsWith("/api/tags")) {
            result = tags(base);
        } else if (path.startsWith("/api/search")) {
            result = search(base, param(path, "q"), page(path), profile);
        } else if (path.startsWith("/api/videos")) {
            if (path.indexOf("/play") > 0) {
                result = play(base, videoId(path), episode(path), profile);
            } else if (path.indexOf("/api/videos/") == 0) {
                result = detail(base, videoId(path), profile);
            } else if (path.indexOf("sort=new") > 0) {
                result = listing(base, "", page(path), pageSize(path), profile);
            } else {
                result = listing(base, param(path, "cate_id"), page(path), pageSize(path), profile);
            }
        } else {
            throw new Exception("CMS 适配器不认识的路径：" + path);
        }
        // 我们的接口在外面包了一层 {"data": …}（fetchApi 读的是 payload.data），
        // 适配器必须照做 —— 不然前端拿到的是「缺少 data」，而它并不知道
        // 自己背后连的是 CMS 还是黄果。
        JSONObject envelope = new JSONObject();
        envelope.put("data", result);
        return envelope.toString().getBytes("UTF-8");
    }

    // ── 各个动作 ──────────────────────────────────────────────────────

    /** 首页。CMS 没有「首页」概念，拿最新一页当推荐 + 最新。 */
    private static JSONObject home(String base, SiteProfile profile) throws Exception {
        JSONObject raw = call(base, "ac=detail&pg=1");
        JSONArray list = raw.optJSONArray("list");
        JSONArray videos = toVideos(profile, list);
        JSONObject out = new JSONObject();
        out.put("featured", head(videos, 6));
        out.put("latest", videos);
        // adaptHome 还会读 cate_rec 做去重拼接，CMS 这边留空数组，让它走 featured 那份
        out.put("cate_rec", new JSONArray());
        return out;
    }

    /** 列表 / 分类。cateId 为空表示不带分类过滤。 */
    private static JSONObject listing(String base, String cateId, int page, int size, SiteProfile profile) throws Exception {
        StringBuilder q = new StringBuilder("ac=detail");
        // CMS 不认 size —— 每页固定 20 条（实测 limit=20）。size 只用于给前端算分页。
        if (cateId != null && cateId.length() > 0) q.append("&t=").append(cateId);
        q.append("&pg=").append(page);
        JSONObject raw = call(base, q.toString());
        JSONObject out = new JSONObject();
        out.put("items", toVideos(profile, raw.optJSONArray("list")));
        out.put("pagination", pagination(raw, page));
        return out;
    }

    private static JSONObject search(String base, String keyword, int page, SiteProfile profile) throws Exception {
        if (keyword == null || keyword.length() == 0) {
            JSONObject empty = new JSONObject();
            empty.put("items", new JSONArray());
            empty.put("pagination", pagination(null, 1));
            return empty;
        }
        JSONObject raw = call(base, "ac=detail&wd=" + enc(keyword) + "&pg=" + page);
        JSONObject out = new JSONObject();
        out.put("items", toVideos(profile, raw.optJSONArray("list")));
        out.put("pagination", pagination(raw, page));
        return out;
    }

    /** 详情。顺带把 vod_play_url 拆成剧集列表，缓存起来给 play() 用。 */
    private static JSONObject detail(String base, String id, SiteProfile profile) throws Exception {
        JSONObject raw = call(base, "ac=detail&ids=" + enc(id));
        JSONArray list = raw.optJSONArray("list");
        JSONObject item = (list != null && list.length() > 0) ? list.optJSONObject(0) : null;
        if (item == null) throw new Exception("CMS 里找不到这个 id：" + id);

        JSONObject out = new JSONObject();
        // 配置驱动的投影先铺一层（title / cover / badge / category 这些纯改名靠它）。
        // id / description / episodes 是本适配器自己产出的，别让别名表绕过去。
        project(profile, item, out, "id", "description", "episodes");
        out.put("id", String.valueOf(item.opt("vod_id")));
        out.put("description", stripHtml(item.optString("vod_content", "")));
        out.put("episodes", episodesOf(item));
        cache(id, item);
        return out;
    }

    /** 某一集的播放地址。多线路时交给 LineRace 择优。 */
    private static JSONObject play(String base, String id, int episode, SiteProfile profile) throws Exception {
        JSONObject item = DETAIL_CACHE.get(id);
        if (item == null) {
            detail(base, id, profile);              // 顺手填缓存
            item = DETAIL_CACHE.get(id);
        }
        if (item == null) throw new Exception("拿不到这一集的播放地址：" + id);

        String raw = item.optString("vod_play_url", "");
        List<String[]> eps = splitPlayUrl(raw);
        int index = episode - 1;
        if (index < 0 || index >= eps.size()) {
            throw new Exception("第 " + episode + " 集不存在（共 " + eps.size() + " 集）");
        }

        // 同一集的全部线路候选；只有一条时 LineRace 原样返回，开销为零。
        List<String> candidates = LineRace.candidatesFrom(allLines(raw), index);
        String url = candidates.isEmpty() ? eps.get(index)[1] : LineRace.pick(candidates);
        if (url == null || url.length() == 0) url = eps.get(index)[1];
        if (url.length() == 0) throw new Exception("第 " + episode + " 集没有播放地址");
        // 分享页而不是直链时明确报错。这种情况要 parses[] 才能解，一期不支持 ——
        // 但不能静默失败，否则用户看到的是「点了没反应」。
        if (url.indexOf(".m3u8") < 0 && url.indexOf(".mp4") < 0) {
            throw new Exception("这一集不是直链（要第三方解析），暂不支持：" + head(url, 60));
        }

        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("ep_num", episode);
        out.put("title", eps.get(index)[0]);
        out.put("video_url", url);
        return out;
    }

    /** 标签页。CMS 的「标签」其实就是 type 分类表。 */
    private static JSONObject tags(String base) throws Exception {
        JSONObject raw = call(base, "ac=list");
        JSONArray classes = raw.optJSONArray("class");
        JSONArray groups = new JSONArray();
        JSONArray tagsArr = new JSONArray();
        if (classes != null) {
            for (int i = 0; i < classes.length(); i++) {
                JSONObject c = classes.optJSONObject(i);
                if (c == null) continue;
                JSONObject tag = new JSONObject();
                String name = c.optString("type_name", "");
                tag.put("name", name);
                tag.put("slug", String.valueOf(c.opt("type_id")));
                tag.put("group", "cms");
                tagsArr.put(tag);
            }
        }
        JSONObject group = new JSONObject();
        group.put("code", "cms");
        group.put("tags", tagsArr);
        groups.put(group);
        JSONObject out = new JSONObject();
        out.put("categories", groups);
        return out;
    }

    // ── 映射 ──────────────────────────────────────────────────────────

    /**
     * 分类表 → 我们 web.options 里的 categories。
     * 由 UserSource 在识别阶段调用，把 slug→type_id 写进配置。
     */
    public static JSONObject categoriesOf(JSONObject cmsResponse) {
        JSONObject out = new JSONObject();
        JSONArray classes = cmsResponse == null ? null : cmsResponse.optJSONArray("class");
        if (classes == null) return out;
        for (int i = 0; i < classes.length(); i++) {
            JSONObject c = classes.optJSONObject(i);
            if (c == null) continue;
            try {
                out.put(String.valueOf(c.opt("type_id")), c.optInt("type_id"));
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** CMS 的一条 list 条目 → adaptVideo 认识的形状。 */
    private static JSONObject toVideo(SiteProfile profile, JSONObject x) {
        JSONObject v = new JSONObject();
        try {
            // 标题 / 封面 / 角标 / 分类这几样**一个字都不改**，交给配置里的别名表。
            // 上一版是把 `vod_name → title` 写死在这里 —— 那等于同一个概念写两遍，
            // 而且写在了编译期那一遍（改个字段名就得重新打包）。
            project(profile, x, v, "id", "score");
            v.put("id", String.valueOf(x.opt("vod_id")));
            // 未评分的 CMS 返回 "0.0"，界面上会显示成一个 0.0 的角标。
            // 不给这个键，adaptVideo 那条 `pick(item,'score') != null` 就不会画它。
            String score = x.optString("vod_score", "").trim();
            if (score.length() > 0 && !score.startsWith("0.0") && !score.startsWith("0.00")) {
                v.put("score", score);
            }
        } catch (Exception ignored) {}
        return v;
    }

    private static JSONArray toVideos(SiteProfile profile, JSONArray list) {
        JSONArray out = new JSONArray();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x != null) out.put(toVideo(profile, x));
        }
        return out;
    }

    /**
     * 按配置里的别名表，把原始条目投影成「我们认识的角色」。
     *
     * ── 这一层解决什么 ────────────────────────────────────────────────
     * 苹果CMS 一家几千个站，**同族里字段名不完全一样**。上一版把
     * `vod_name → title` 这种改名写死在 Java 里，于是「换个字段名」= 改 Java =
     * 重新打包 = 用户更新。而配置里那张 `web.fields` 别名表本来就是干这个的
     * （前端的 pick() 按顺序取第一个有值的）—— 同一个概念写了两遍，一遍在
     * 运行期（对），一遍在编译期（错）。
     *
     * 现在只留一份：**这里不认字段名，只按配置表把命中的那个键原样搬过去**，
     * 改名交给前端的 pick()。所以「同一个族里换个字段名」改配置就行。
     *
     * 只搬命中的那**一个**键（pick 的语义就是取第一个有值的），载荷不会因为
     * 别名列表变长而变大 —— 苹果CMS 一个条目有 95 个字段，全搬过去是不可接受的。
     *
     * @param skip 由本适配器自己产出的角色，别名表别去碰 —— 否则 pick() 可能先取到
     *             原始值，把派生逻辑（比如评分里 "0.0" 的过滤）绕过去。
     */
    private static void project(SiteProfile profile, JSONObject raw, JSONObject out, String... skip) {
        JSONObject fields = fieldsOf(profile);
        if (fields == null || raw == null) return;
        List<String> skipped = Arrays.asList(skip);
        Iterator<String> roles = fields.keys();
        while (roles.hasNext()) {
            String role = roles.next();
            if (role.startsWith("_") || skipped.contains(role)) continue;
            JSONArray names = fields.optJSONArray(role);
            for (int i = 0; names != null && i < names.length(); i++) {
                String name = names.optString(i, "");
                if (name.length() == 0 || !raw.has(name)) continue;
                Object value = raw.opt(name);
                if (value == null || "".equals(value)) continue;
                try { out.put(name, value); } catch (Exception ignored) {}
                break;                       // pick() 取第一个有值的，多的不搬
            }
        }
    }

    /** 合并后的字段别名表。读不到就返回 null —— 那时按空投影，不猜。 */
    private static JSONObject fieldsOf(SiteProfile profile) {
        if (profile == null) return null;
        try {
            JSONObject web = profile.object("web");
            return web == null ? null : web.optJSONObject("fields");
        } catch (Throwable e) {
            return null;
        }
    }

    /** CMS 的 pagination → 我们的 {page, pages}。adapters.js 的 nextPageOf 认这个。 */
    private static JSONObject pagination(JSONObject raw, int page) {
        JSONObject p = new JSONObject();
        try {
            p.put("page", page);
            p.put("pages", raw == null ? 1 : raw.optInt("pagecount", 1));
        } catch (Exception ignored) {}
        return p;
    }

    /** vod_play_url → 剧集列表（只给 adaptDetail 用，没有地址）。 */
    private static JSONArray episodesOf(JSONObject item) {
        JSONArray out = new JSONArray();
        List<String[]> eps = splitPlayUrl(item.optString("vod_play_url", ""));
        for (int i = 0; i < eps.size(); i++) {
            JSONObject e = new JSONObject();
            try {
                e.put("ep_num", i + 1);
                e.put("title", eps.get(i)[0]);
                e.put("duration", "");
            } catch (Exception ignored) {}
            out.put(e);
        }
        return out;
    }

    /**
     * 拆 vod_play_url。格式：`集名1$地址1#集名2$地址2#…`
     *
     * 线路用 `$$$` 分隔多条。这里挑一条（见 pickLine）；**全部线路**留给
     * play() 交给 LineRace 测速择优（见 allLines）。
     * 集名可能是空的，用「第 N 集」兜底。
     */
    /** 包级可见：AgedmAdapter 匹配到同名采集源之后要复用同一套拆法。 */
    static List<String[]> splitPlayUrl(String raw) {
        return splitLine(pickLine(raw));
    }

    /** 全部线路，每项是一条 `集名$地址#集名$地址…`。给 LineRace 用。 */
    static List<String> allLines(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null || raw.length() == 0) return out;
        String[] lines = raw.split("\\$\\$\\$");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() > 0) out.add(line);
        }
        return out;
    }

    /** 拆**单条线路**。splitPlayUrl 和 LineRace 共用这一段。 */
    static List<String[]> splitLine(String line) {
        List<String[]> out = new ArrayList<String[]>();
        if (line == null || line.length() == 0) return out;
        String[] parts = line.split("#");
        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i];
            if (seg.length() == 0) continue;
            int dollar = seg.indexOf('$');
            String name;
            String url;
            if (dollar < 0) {
                name = "";
                url = seg;
            } else {
                name = seg.substring(0, dollar);
                url = seg.substring(dollar + 1);
            }
            if (name.length() == 0) name = "第 " + (i + 1) + " 集";
            out.add(new String[]{name, url.trim()});
        }
        return out;
    }

    /**
     * 多条线路里挑一条能直接播的。
     *
     * 为什么不能直接取第一条：实测某 XML 源（量子，2026-10-16）的第一条线路
     * 是 `v.lzcdn27.com/share/47fa1e27…` —— 一个 /share/ 分享页，要第三方
     * 解析器才能变成 m3u8；第二条 `lzm3u8` 才是直链。取第一条的结果是
     * 「点了播放没反应」。
     *
     * 优先 .m3u8，其次 .mp4，都没有才退回第一条（那种情况下面会明确报错，
     * 而不是静默失败）。
     */
    private static String pickLine(String raw) {
        String[] lines = raw.split("\\$\\$\\$");
        for (String line : lines) if (line.contains(".m3u8")) return line;
        for (String line : lines) if (line.contains(".mp4")) return line;
        return lines[0];
    }

    // ── HTTP ──────────────────────────────────────────────────────────

    private static JSONObject call(String base, String query) throws Exception {
        String url = base + (base.indexOf('?') >= 0 ? "&" : "?") + query;
        SiteProfile probe = null;                    // 用系统 DNS：CMS 地址是用户填的，没有 DoH 配置
        byte[] body = AiiHttp.getRaw(url, TIMEOUT);
        if (body == null || body.length == 0) throw new Exception("CMS 返回空响应");
        String text = new String(body, "UTF-8");

        // XML 接口（type 0 那种 /at/xml/）先翻译成 JSON 那套形状，后面全部复用。
        if (CmsXml.looksLikeXml(text)) {
            JSONObject d = CmsXml.parse(text);
            if (d.optInt("code", 1) != 1) throw new Exception("CMS 返回错误：code=" + d.optInt("code"));
            return d;
        }

        try {
            JSONObject d = new JSONObject(text);
            if (d.has("code") && d.optInt("code", 1) != 1) {
                throw new Exception("CMS 返回错误：" + d.optString("msg", "code=" + d.optInt("code")));
            }
            return d;
        } catch (org.json.JSONException e) {
            // 多半是拿到了 XML（type=0 那种）或者 HTML
            throw new Exception("CMS 返回的不是 JSON（可能是 XML 接口或错误页）：" + head(text, 80));
        }
    }

    private static void cache(String id, JSONObject item) {
        if (DETAIL_CACHE.size() > CACHE_LIMIT) DETAIL_CACHE.clear();
        DETAIL_CACHE.put(id, item);
    }

    private static String normalizeApi(String api) {
        String s = api.trim();
        int q = s.indexOf('?');
        return q < 0 ? s : s.substring(0, q);
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    private static String param(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        String[] pairs = path.substring(q + 1).split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            if (eq < 0) continue;
            if (!key.equals(pairs[i].substring(0, eq))) continue;
            try { return java.net.URLDecoder.decode(pairs[i].substring(eq + 1), "UTF-8"); }
            catch (Exception e) { return ""; }
        }
        return "";
    }

    private static int page(String path) {
        return intOf(param(path, "page"), 1);
    }

    private static int pageSize(String path) {
        return intOf(param(path, "size"), 20);
    }

    private static String videoId(String path) {
        int start = path.indexOf("/api/videos/");
        if (start < 0) return "";
        String rest = path.substring(start + "/api/videos/".length());
        int end = rest.indexOf('/');
        if (end >= 0) rest = rest.substring(0, end);
        int q = rest.indexOf('?');
        if (q >= 0) rest = rest.substring(0, q);
        return rest;
    }

    private static int episode(String path) {
        return intOf(param(path, "ep"), 1);
    }

    private static int intOf(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception e) { return fallback; }
    }

    private static String enc(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8"); } catch (Exception e) { return value; }
    }

    /** 去掉 CMS 简介里的 HTML 标签 —— 实测 vod_content 是 `<p>…</p>`。 */
    private static String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&amp;", "&").trim();
    }

    private static String head(String text, int n) {
        if (text == null) return "";
        return text.length() <= n ? text : text.substring(0, n) + "…";
    }

    private static JSONArray head(JSONArray source, int n) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < source.length() && i < n; i++) out.put(source.opt(i));
        return out;
    }
}
