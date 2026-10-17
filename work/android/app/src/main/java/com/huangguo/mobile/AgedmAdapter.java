package com.huangguo.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * AIIvideo —— AGE动漫 适配器。
 *
 * ── 为什么这个站需要适配器，而不是一份配置 ──────────────────────────────
 * 先说结论：**它的首页和列表本来就能纯配置接**（用 web.shapes + web.fields，
 * 2026-10-16 实测过：零代码把首页 12 条和排行榜列表拉下来了）。卡住的是详情：
 *
 *   站点给的是  video.playlists = { "xigua": [["第01集", "age_加密串"], …], "ffm3u8": … }
 *   我们要的是  episodes = [{ep_num, title, duration}, …]
 *
 * 「按位置把 [[名,地址]] 拆成对象」是**结构重排**，不是「取子集 + 改名」。
 * 声明层（web.fields 管值叫什么、web.shapes 管值挂在哪根枝上）覆盖不了它 ——
 * 而一旦让配置能表达「遍历 + 按位置访问」，它就不再是配置，是一门没有工具链的
 * 语言了。所以这一段留代码。
 *
 * ── 播放：密文交给 JS 侧的 wasm 解 ──────────────────────────────────────
 * 站点详情里给的不是 URL，是 `age_xxx` 密文，要过一道 Go/WASM 的签名门。
 * 那道门 2026-10-16 拆完了，链路见 assets/hxm/agedm-play.js 顶部；密码学部分
 * 直接请对面那个 wasm 当黑盒（密钥抠不出来：全内存横扫 16/24/32 字节都不命中，
 * 域名只用来查表选密钥）。
 *
 * 所以这里**只把密文交出去**，返回 `agedm-wasm:<token>`，JS 看到前缀就跑解析。
 * 之所以不在 Java 里解：签名要调用那个 wasm，而 wasm 只有 WebView 能跑。
 *
 * 下面 matchFromCms() 那套是上一版的绕路 —— 拿标题去同名苹果CMS 采集站搜明文。
 * 留着是因为它在 wasm 那条路失手时还能顶一下（对面改了签名就用得上），
 * 但目前没有调用方。
 *
 * ── 接口形状（2026-10-16 实测，全部裸请求可拉）────────────────────────
 *   /v2/home-list          →  {latest[12], recommend[12], week_list}
 *   /v2/catalog?…          →  {total, videos[]}        没有页码数，得自己算
 *   /v2/search?query=&page= →  {code, message, data:{videos, total, totalPage, …}}
 *                             ↑ 只有这一个带 data 信封，两边格式不统一
 *   /v2/detail/{id}        →  {video{…, playlists}, series, similar, player_label_arr, …}
 *   /v2/slipic             →  [{AID, Title, PicUrl}]
 */
public final class AgedmAdapter {

    private static final int TIMEOUT = 12;
    private static final String DEFAULT_API = "https://api.agedm.io";

    /** 详情缓存。播放要靠它 —— CMS 那边没有「取第 N 集」，得先有全量。 */
    private static final Map<String, JSONObject> DETAIL_CACHE = new HashMap<String, JSONObject>();
    private static final int CACHE_LIMIT = 12;

    /** 每页条数。站点支持到 100（实测），默认取 24 够首页和列表用。 */
    private static final int PAGE_SIZE = 24;

    private AgedmAdapter() {}

    // ── 对外入口 ──────────────────────────────────────────────────────

    /**
     * 按我们内部的路径词汇表取数据，返回**我们形状**的 JSON 字节（外面套 {"data":…}）。
     * 由 AiiHttp.get() 在 adapter == "agedm" 时分流过来。
     */
    public static byte[] fetch(String path, SiteProfile profile) throws Exception {
        String base = base(profile);

        JSONObject result;
        if (path.startsWith("/api/home")) {
            result = home(base);
        } else if (path.startsWith("/api/tags")) {
            result = tags();
        } else if (path.startsWith("/api/search")) {
            result = search(base, param(path, "q"), page(path));
        } else if (path.startsWith("/api/videos")) {
            if (path.indexOf("/play") > 0) {
                result = play(profile, videoId(path), episode(path));
            } else if (path.indexOf("/api/videos/") == 0) {
                result = detail(base, videoId(path));
            } else {
                result = listing(base, param(path, "cate_id"), param(path, "order"), page(path));
            }
        } else {
            throw new Exception("AGE动漫 适配器不认识的路径：" + path);
        }

        JSONObject envelope = new JSONObject();
        envelope.put("data", result);
        return envelope.toString().getBytes("UTF-8");
    }

    // ── 各个动作 ──────────────────────────────────────────────────────

    /**
     * 首页。站点自己有 /v2/home-list，直接用它 —— 不需要像苹果CMS 那样拿最新一页凑。
     *
     *   recommend → 我们的 featured（轮播）
     *   latest    → 我们的 latest（最近更新）
     *   week_list → 丢掉（按星期排的节目单，首页那两个位置用不上）
     */
    private static JSONObject home(String base) throws Exception {
        JSONObject raw = get(base + "/v2/home-list");
        JSONArray featured = toVideos(raw.optJSONArray("recommend"));
        JSONArray latest = toVideos(raw.optJSONArray("latest"));
        JSONObject out = new JSONObject();
        out.put("featured", featured);
        out.put("latest", latest);
        // adaptHome 会读 cate_rec 做去重拼接。站点没有这个概念，留空数组 ——
        // 那它就走 featured 那份，正好。
        out.put("cate_rec", new JSONArray());
        return out;
    }

    /** 列表 / 分类。cateId 空表示不按类型过滤（首页那两个「更多」走这条）。 */
    private static JSONObject listing(String base, String label, String order, int page) throws Exception {
        String url = base + "/v2/catalog?" + catalogQuery(label, order) + "&page=" + page + "&size=" + PAGE_SIZE;
        JSONObject raw = get(url);
        JSONObject out = new JSONObject();
        out.put("items", toVideos(raw.optJSONArray("videos")));
        out.put("pagination", pagination(raw, page));
        return out;
    }

    /**
     * 目录查询串。**参数名有坑，两个：**
     *   · genre 不是「类型」而是「版本」（TV / 剧场版 / OVA）
     *   · 真正的类型叫 label，值是中文原词（搞笑 / 校园 / 奇幻…），不是代码
     * 所以「分类」在 AGE动漫 这边就是 label=<中文词>。
     */
    private static String catalogQuery(String label, String order) {
        String sort = (order == null || order.length() == 0) ? "time" : order;
        if (!"time".equals(sort) && !"hits".equals(sort) && !"name".equals(sort)) sort = "time";
        StringBuilder q = new StringBuilder("genre=all&letter=all&year=all&season=all"
                + "&status=all&resource=all&region=all&order=" + sort);
        if (label != null && label.length() > 0 && !"all".equals(label)) {
            q.append("&label=").append(enc(label));
        } else {
            q.append("&label=all");
        }
        return q.toString();
    }

    /**
     * 搜索。**只有这一个接口带 data 信封**，和另外几个不统一，所以要单独拆一次。
     * 参数名是 `query`，写 `wd` 或 `keyword` 会拿到 {"code":40050,"message":"参数错误！"}。
     */
    private static JSONObject search(String base, String keyword, int page) throws Exception {
        if (keyword == null || keyword.length() == 0) {
            JSONObject empty = new JSONObject();
            empty.put("items", new JSONArray());
            empty.put("pagination", pagination(null, 1));
            return empty;
        }
        JSONObject raw = get(base + "/v2/search?query=" + enc(keyword) + "&page=" + page);
        JSONObject payload = raw.optJSONObject("data");
        if (payload == null) payload = raw;                  // 万一哪天信封没了也别崩
        JSONObject out = new JSONObject();
        out.put("items", toVideos(payload.optJSONArray("videos")));
        // 搜索这边站点给了 totalPage，比自己算准，优先用。
        JSONObject p = new JSONObject();
        try {
            p.put("page", page);
            p.put("pages", payload.optInt("totalPage", 1));
        } catch (Exception ignored) {}
        out.put("pagination", p);
        return out;
    }

    /** 详情。剧集要从 video.playlists 里挑一条线路拆出来。 */
    private static JSONObject detail(String base, String id) throws Exception {
        JSONObject raw = get(base + "/v2/detail/" + enc(id));
        JSONObject video = raw.optJSONObject("video");
        if (video == null) throw new Exception("AGE动漫 里找不到这个 id：" + id);

        // ── player_vip 在**响应顶层**，不在 video 里 ────────────────────────
        // 而下面缓存的是 video 本身。于是 pickSource / lineOrder 里那句
        // video.optString("player_vip") 永远拿空串，**站点给的线路优先级从来
        // 没生效过** —— 一直退化成「按 playlists 的键序取第一个」。
        //
        // 不改缓存结构（缓存的就是 video，改动面太大），把这份顺序塞回 video 身上
        // 带个下划线前缀。它不是站点字段，是我们自己的。
        video.put("_player_vip", raw.optString("player_vip", ""));
        video.put("_player_jx", raw.optString("player_jx", ""));

        JSONObject out = new JSONObject();
        out.put("id", String.valueOf(video.opt("id")));
        out.put("title", video.optString("name", ""));
        out.put("cover", video.optString("cover", ""));
        out.put("description", video.optString("intro", ""));
        out.put("score", video.opt("star"));
        out.put("tags", video.optJSONArray("tags_arr") != null
                ? video.optJSONArray("tags_arr") : new JSONArray());
        out.put("episodes", episodesOf(video));
        // 播放要用的东西一起留着，免得再请求一次。
        out.put("_play_source", pickSource(video));
        cache(id, video);
        return out;
    }

    /**
     * 剧集列表。站点给的是 `playlists{源: [[集名, 密文], …]}`，我们要平的 [{ep_num,title}]。
     *
     * 只取**一条线路** —— 多线路切换是以后的事，我们的界面现在也只画一条。
     * 挑法见 pickSource()。
     */
    private static JSONArray episodesOf(JSONObject video) {
        JSONArray out = new JSONArray();
        JSONObject playlists = video.optJSONObject("playlists");
        if (playlists == null) return out;
        String source = pickSource(video);
        JSONArray eps = source.length() == 0 ? null : playlists.optJSONArray(source);
        if (eps == null) return out;
        for (int i = 0; i < eps.length(); i++) {
            JSONArray pair = eps.optJSONArray(i);
            if (pair == null) continue;
            JSONObject e = new JSONObject();
            try {
                e.put("ep_num", i + 1);
                e.put("title", pair.optString(0, "第 " + (i + 1) + " 集"));
                e.put("duration", "");
            } catch (Exception ignored) {}
            out.put(e);
        }
        return out;
    }

    /**
     * 挑一条线路。优先按 player_vip 给的顺序（站点自己标了「能直连」的那批），
     * 都没有就取第一个。
     */
    /**
     * 站点的线路优先级。它在**响应顶层**，detail() 会把它塞进 video 的 `_player_vip`。
     * 顺手也认一下站点原名，免得旧缓存里那份走岔。
     */
    private static String playerVip(JSONObject video) {
        String v = video.optString("_player_vip", "");
        return v.length() > 0 ? v : video.optString("player_vip", "");
    }

    private static String pickSource(JSONObject video) {
        JSONObject playlists = video.optJSONObject("playlists");
        if (playlists == null || playlists.length() == 0) return "";
        String vip = playerVip(video);
        if (vip.length() > 0) {
            String[] order = vip.split(",");
            for (int i = 0; i < order.length; i++) {
                String key = order[i].trim();
                if (key.length() > 0 && playlists.optJSONArray(key) != null) return key;
            }
        }
        Iterator<String> keys = playlists.keys();
        return keys.hasNext() ? keys.next() : "";
    }

    /**
     * 标签页。站点没有标签接口 —— 它的「类型」就是目录接口 label 参数的那 46 个中文词。
     * 这里把它们当标签交上去，`tag-list` 走 /api/videos?cate_id=<词>（见 wrapAgedm 里
     * 配的那条路径）。分组名交给配置里的 ui.tagGroups。
     */
    private static JSONObject tags() throws Exception {
        JSONArray tags = new JSONArray();
        for (int i = 0; i < LABELS.length; i++) {
            JSONObject tag = new JSONObject();
            try {
                tag.put("name", LABELS[i]);
                tag.put("slug", LABELS[i]);         // 这个站的"slug"就是中文词本身
                tag.put("group", "agedm");
            } catch (Exception ignored) {}
            tags.put(tag);
        }
        JSONObject group = new JSONObject();
        try {
            group.put("code", "agedm");
            group.put("tags", tags);
        } catch (Exception ignored) {}
        JSONArray groups = new JSONArray();
        groups.put(group);
        JSONObject out = new JSONObject();
        out.put("categories", groups);
        return out;
    }

    // ── 播放 ──────────────────────────────────────────────────────────

    /**
     * 某一集的播放地址。
     *
     * 站点给的 `age_xxx` 是加密的（Go/WASM 签名门，见类注释），直接解不了。
     * 走同名采集源：拿标题去配置里那个苹果CMS 接口搜，标题完全一致才用。
     *
     * 「完全一致」是刻意的。模糊匹配能提高命中率，但匹配错了就是**放错片子** ——
     * 那比「播不了」糟得多，而且用户根本看不出来。宁可报找不到。
     */
    private static JSONObject play(SiteProfile profile, String id, int episode) throws Exception {
        JSONObject video = DETAIL_CACHE.get(id);
        if (video == null) {
            detail(base(profile), id);                  // 顺手填缓存
            video = DETAIL_CACHE.get(id);
        }
        if (video == null) throw new Exception("拿不到详情，无法播放：" + id);

        // player_vip 在**响应顶层**，不在 video 里。front 端也是从顶层读的。
        JSONObject playlists = video.optJSONObject("playlists");
        String source = pickSource(video);
        JSONArray eps = (playlists == null || source.length() == 0) ? null : playlists.optJSONArray(source);
        int count = eps == null ? 0 : eps.length();          // org.json 的 JSONArray 用 length()
        if (episode < 1 || episode > count) {
            throw new Exception("第 " + episode + " 集不存在（共 " + count + " 集）");
        }
        JSONArray pair = eps.optJSONArray(episode - 1);
        String token = pair == null ? "" : pair.optString(1, "");
        if (!token.startsWith("age_")) {
            throw new Exception("这一集没有可解的播放密文（线路 " + source + "）");
        }

        // 真正的解析在 JS 侧：它要跑站点那个 TinyGo wasm 算签名。
        // 这里只把密文交给它 —— 见 assets/hxm/agedm-play.js。
        JSONObject out = new JSONObject();
        out.put("source", PLAY_MARKER + token);
        out.put("cover", video.optString("cover", ""));

        // ── 2026-10-16：把**所有**线路一起交出去 ───────────────────────────
        // 以前只给首选那条，解析失败就整部片播不了。实测「萝球社！OAD」只有一条
        // `tt` 线路，那条回 Code=9，于是这部剧在 App 里彻底没救 —— 而别的片
        // 多数有 5 条线路（xigua / ffm3u8 / bfzym3u8 / wjm3u8 / lzm3u8）。
        //
        // 线路之间是**独立**的：一条服务端没受理，不代表下一条也不行。所以顺序
        // 交给 JS 侧一条条试，谁先出 m3u8 就用谁。这里只搬 [名字, 密文] 两格 ——
        // 它要在桥上走一趟，别塞用不到的东西。
        JSONArray lines = new JSONArray();
        String[] order = lineOrder(video, playlists);
        for (int i = 0; i < order.length; i++) {
            JSONArray list = playlists.optJSONArray(order[i]);
            if (list == null || episode > list.length()) continue;
            JSONArray p = list.optJSONArray(episode - 1);
            String t = p == null ? "" : p.optString(1, "");
            if (!t.startsWith("age_")) continue;
            JSONArray one = new JSONArray();
            one.put(order[i]);
            one.put(t);
            lines.put(one);
        }
        out.put("lines", lines);
        return out;
    }

    /**
     * 线路的尝试顺序：`player_vip` 里那串优先，剩下的按 playlists 自己的键序接在后面。
     *
     * 以前的 pickSource 只返回**第一条**；现在要一条条试，所以顺序得整条列出来。
     * 站点给的 player_vip 是它自己认为的好坏次序，我们照抄，不自己排。
     */
    private static String[] lineOrder(JSONObject video, JSONObject playlists) {
        java.util.LinkedHashSet<String> order = new java.util.LinkedHashSet<String>();
        String vip = playerVip(video);
        if (vip.length() > 0) {
            String[] parts = vip.split(",");
            for (int i = 0; i < parts.length; i++) {
                String key = parts[i].trim();
                if (key.length() > 0 && playlists.optJSONArray(key) != null) order.add(key);
            }
        }
        if (playlists != null) {
            Iterator<String> keys = playlists.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (playlists.optJSONArray(key) != null) order.add(key);
            }
        }
        return order.toArray(new String[0]);
    }

    /** JS 侧认这个前缀，拿后面的密文去跑 wasm 解析。 */
    public static final String PLAY_MARKER = "agedm-wasm:";


    /**
     * 去苹果CMS 采集站上找同名作品，取第 episode 集的地址。
     *
     * 匹配规则：把标题里的空白（含全角空格）去掉之后**必须完全相等**。
     * AGE动漫 的命名和采集站常差一个空格（「第二季」vs「 第二季」），所以先归一化再比。
     */
    private static String matchFromCms(String cms, String title, int episode) throws Exception {
        String want = normalize(title);
        if (want.length() == 0) throw new Exception("没有标题，没法去采集站匹配");

        String url = cms + (cms.indexOf('?') >= 0 ? "&" : "?") + "ac=detail&wd=" + enc(title);
        JSONObject raw = get(url);
        JSONArray list = raw.optJSONArray("list");
        if (list == null || list.length() == 0) {
            throw new Exception("采集站上没搜到《" + title + "》，这个源播不了");
        }

        JSONObject hit = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            if (want.equals(normalize(item.optString("vod_name", "")))) { hit = item; break; }
        }
        if (hit == null) {
            throw new Exception("采集站上搜到了 " + list.length() + " 条，但没有标题完全一致的《"
                    + title + "》，不猜（猜错就是放错片子）");
        }

        List<String[]> eps = CmsAdapter.splitPlayUrl(hit.optString("vod_play_url", ""));
        if (episode < 1 || episode > eps.size()) {
            throw new Exception("采集站的《" + hit.optString("vod_name", "")
                    + "》只有 " + eps.size() + " 集，没有第 " + episode + " 集");
        }
        String play = eps.get(episode - 1)[1];
        if (play.indexOf(".m3u8") < 0 && play.indexOf(".mp4") < 0) {
            throw new Exception("采集站给的也不是直链（可能要第三方解析）：" + head(play, 60));
        }
        log("播放匹配到采集站：" + hit.optString("vod_name", "") + " 第 " + episode + " 集");
        return play;
    }

    /** 去掉所有空白（含全角）再比。这个站的命名差异基本只有空格。 */
    private static String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\s\u3000]+", "");
    }

    // ── 映射 ──────────────────────────────────────────────────────────

    /**
     * 站点的一条列表项 → adaptVideo 认识的形状。
     *
     * ⚠️ **首页列表和目录列表是两套命名**，实测（2026-10-16）：
     *   首页 /v2/home-list   {AID, Title, PicSmall, NewTitle, Href}
     *   目录 /v2/catalog     {id, name, cover, uptodate, tags_arr, intro, star, …}
     * 同一个站两个接口两套写法，所以这里按「候选键列表」取第一个有值的 ——
     * 和前端 fields 别名是同一个套路，只是发生在 Java 这一侧。
     */
    private static JSONArray toVideos(JSONArray list) {
        JSONArray out = new JSONArray();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x == null) continue;
            JSONObject v = new JSONObject();
            try {
                v.put("id", str(x, "id", "AID"));
                v.put("title", str(x, "name", "Title"));
                v.put("cover", str(x, "cover", "PicSmall"));
                v.put("badge", str(x, "uptodate", "NewTitle"));
                v.put("cate_id", first(x, "type", "tid"));
                Object tags = first(x, "tags_arr", "tagsArr");
                v.put("tags", tags instanceof JSONArray ? tags : new JSONArray());
                String intro = str(x, "intro");
                if (intro.length() > 0) v.put("description", intro);
                // 未评分的是 0，界面上会画成一个 0.0 的角标。不写这个键就不画。
                Object score = first(x, "star");
                if (score != null && !"0".equals(String.valueOf(score)) && !"0.0".equals(String.valueOf(score))) {
                    v.put("score", score);
                }
            } catch (Exception ignored) {}
            out.put(v);
        }
        return out;
    }

    /** 取第一个有值的键。空串也算没有 —— 站点有些字段是 "" 而不是缺省。 */
    private static Object first(JSONObject x, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            Object value = x.opt(keys[i]);
            if (value == null) continue;
            if (value instanceof String && ((String) value).length() == 0) continue;
            return value;
        }
        return null;
    }

    private static String str(JSONObject x, String... keys) {
        Object value = first(x, keys);
        return value == null ? "" : String.valueOf(value);
    }

    /** 站点没有页码数，只有 total。自己算 —— 和配置里那个 div 是同一个式子。 */
    private static JSONObject pagination(JSONObject raw, int page) {
        JSONObject p = new JSONObject();
        try {
            p.put("page", page);
            int total = raw == null ? 0 : raw.optInt("total", 0);
            p.put("pages", total <= 0 ? 1 : (int) Math.ceil(total / (double) PAGE_SIZE));
        } catch (Exception ignored) {}
        return p;
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    /** 配置里的 api 字段；没写就用默认。允许带 /v2 尾巴，这里统一削掉。 */
    private static String base(SiteProfile profile) {
        String api = profile == null ? "" : profile.rule("api");
        if (api == null || api.length() == 0) api = DEFAULT_API;
        api = api.trim();
        while (api.endsWith("/")) api = api.substring(0, api.length() - 1);
        if (api.endsWith("/v2")) api = api.substring(0, api.length() - 3);
        return api;
    }

    private static JSONObject get(String url) throws Exception {
        byte[] body = AiiHttp.getRaw(url, TIMEOUT);
        if (body == null || body.length == 0) throw new Exception("AGE动漫 返回空响应");
        try {
            return new JSONObject(new String(body, "UTF-8"));
        } catch (Exception e) {
            throw new Exception("AGE动漫 返回的不是 JSON：" + head(new String(body, "UTF-8"), 80));
        }
    }

    private static String param(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        String[] pairs = path.substring(q + 1).split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            if (eq < 0) continue;
            if (key.equals(pairs[i].substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(pairs[i].substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return pairs[i].substring(eq + 1);
                }
            }
        }
        return "";
    }

    private static int page(String path) {
        try { return Math.max(1, Integer.parseInt(param(path, "page"))); }
        catch (Exception e) { return 1; }
    }

    private static int episode(String path) {
        try { return Math.max(1, Integer.parseInt(param(path, "ep"))); }
        catch (Exception e) { return 1; }
    }

    private static String videoId(String path) {
        int start = path.indexOf("/api/videos/");
        if (start < 0) return "";
        String rest = path.substring(start + "/api/videos/".length());
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        int q = rest.indexOf('?');
        return q >= 0 ? rest.substring(0, q) : rest;
    }

    private static String enc(String value) {
        try { return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception e) { return value == null ? "" : value; }
    }

    private static String head(String text, int max) {
        if (text == null) return "";
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private static void cache(String id, JSONObject video) {
        synchronized (DETAIL_CACHE) {
            if (DETAIL_CACHE.size() >= CACHE_LIMIT) {
                Iterator<String> it = DETAIL_CACHE.keySet().iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
            DETAIL_CACHE.put(id, video);
        }
    }

    private static void log(String message) {
        try { android.util.Log.d("AIIvideo", "[agedm] " + message); } catch (Throwable ignored) {}
    }

    /** 目录接口 label 参数的全部取值。站点自己的筛选字典里抄的（2026-10-16）。 */
    static final String[] LABELS = {
        "搞笑", "运动", "励志", "热血", "战斗", "竞技", "校园", "青春", "爱情", "恋爱",
        "冒险", "后宫", "百合", "治愈", "萝莉", "魔法", "悬疑", "推理", "奇幻", "科幻",
        "游戏", "神魔", "恐怖", "血腥", "机战", "战争", "犯罪", "历史", "社会", "职场",
        "剧情", "伪娘", "耽美", "童年", "教育", "亲子", "真人", "歌舞", "肉番", "美少女",
        "轻小说", "吸血鬼", "女性向", "泡面番", "欢乐向"
    };
}
