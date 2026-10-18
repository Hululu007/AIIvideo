package com.huangguo.mobile;

import static com.huangguo.mobile.SiteCfg.*;
import static com.huangguo.mobile.SourceConfig.fetch;
import static com.huangguo.mobile.UserSource.log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenList / AList 文件站适配器。
 *
 * ── 和另外四个适配器的区别 ────────────────────────────────────────────
 *
 * 那四个接的是「内容站」：有海报墙、有分类表、详情页里有分集。这个接的是
 * **文件站**（asmrgay.com 跑的是 OpenList，就是 AList 那个分支）：
 *
 *   GET  /api/public/settings      站点信息（公告、默认每页条数）
 *   POST /api/fs/list              列目录
 *   POST /api/fs/get               取一个文件的直链 raw_url
 *   POST /api/fs/search            递归搜（scope=1 只回头目录）
 *
 * 所以映射是这样定的：
 *
 *   卡片（videos）  = **作品目录**，比如 /asmr2/野上菜月/[RJ111491]和み隠れ家
 *   分类（tabs）    = 站点根下那几个大目录 asmr / asmr2 / … / asmr6
 *   详情           = 作品目录里递归捞出来的音频文件
 *   播放           = fs/get 换 raw_url（直链在另一个域名上，Cloudflare 后面）
 *
 * ── 为什么卡片要从 fs/search 来，不是 fs/list ─────────────────────────
 *
 * 目录是四层：`/asmr2/{CV}/{作品}/{mp3版}/*.mp3`。而 App 只有三层导航
 * （首页 → 列表 → 详情 → 播放）。拿 fs/list 一层层点，用户点进 CV 目录
 * 就卡住了 —— 那一屏是「作品」，但 App 认为那是详情页，会把作品当成分集。
 *
 * `fs/search` 带 `keywords="[RJ"` + `scope=1` 一次就能把**所有作品目录**捞出来
 * （这个站的作品名一律以 `[RJ…` 开头，实测 2659 条），CV 那层自然消失。
 * 分页、分类过滤（parent 传大目录）都还是同一个接口。
 *
 * ── 没有封面这件事 ────────────────────────────────────────────────────
 *
 * 文件站没有海报。`thumb` 字段实测恒为空，目录里倒是有 `イラスト/*.jpg`，
 * 但为了一个封面在列表阶段多打 N 次请求不值得。所以卡片封面一律空串，
 * 前端会退回「标题首字」占位图 —— 这是这个源本来的样子，不是坏了。
 */
final class OpenListAdapter {

    /** 没配 origin 时的兜底（测试与本地调试用）。 */
    static final String DEFAULT_HOST = "https://www.asmrgay.com";

    private static final int TIMEOUT = 20;

    /** 一页给多少条。站点默认 30，跟它走。 */
    static final int PER_PAGE = 30;

    /** 作品永远在第 3 段路径：/asmr2/{CV}/{作品}。 */
    static final int WORK_DEPTH = 3;

    /** 卡片索引缓存。文件站翻页就是同一批目录切片，别每次都重打搜索。 */
    private static final long CARD_TTL = 5 * 60 * 1000L;
    private static final Map<String, CardCache> CARD_CACHE =
            new java.util.LinkedHashMap<String, CardCache>(8, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, CardCache> eldest) {
                    return size() > 12;
                }
            };

    private static final class CardCache {
        final JSONArray cards;
        final long at;
        CardCache(JSONArray cards, long at) { this.cards = cards; this.at = at; }
    }

    /** 详情页最多捞多少个文件 / 进多少个子目录 —— 防一个畸形目录把请求打成雪崩。 */
    private static final int MAX_FILES = 200;
    private static final int MAX_DIRS = 16;

    /** 能播的扩展名。音频和视频分开只是为了给 `lines` 标个名字。 */
    private static final String[] AUDIO_EXT = {
        ".mp3", ".m4a", ".flac", ".wav", ".ogg", ".opus", ".aac", ".wma"
    };
    private static final String[] VIDEO_EXT = {
        ".mp4", ".mkv", ".webm", ".mov", ".avi", ".ts", ".m4v", ".flv", ".wmv"
    };

    /**
     * **伪装扩展名**。这个站把视频改名成 .jpg / .woff2 再挂出来 ——
     * 实测 000.jpg 和 000.woff2 都是 1080p 的 MPEG-TS（ffprobe 认成 mpegts，
     * 12.5 秒、1.2 Mbps），文件头就是 TS 的同步字节 0x47，一点没加密。
     *
     * 这么干的目的通常是骗过 CDN 的缓存规则 / 扫描（Cloudflare 对字体、图片
     * 这类静态资源放行得更宽）。对我们来说只是「按内容放行，不按后缀」。
     */
    private static final String[] DISGUISE_EXT = {
        ".woff2", ".woff", ".ttf", ".otf", ".eot",
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"
    };

    /**
     * 伪装文件的大小门槛。真图片一般在几百 KB 以内，这里挂的视频都是 1.8~22MB。
     * 低于门槛的当图片处理 —— 硬把 30KB 的封面塞进选集，用户点了只会报错。
     */
    private static final long DISGUISE_MIN_SIZE = 512 * 1024L;

    /** 详情页解析结果的短期缓存：点播放时会再要一次同一批文件，别重复爬。 */
    private static final Map<String, JSONArray> EPISODE_CACHE =
            new LinkedHashMap<String, JSONArray>(8, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, JSONArray> eldest) {
                    return size() > 8;
                }
            };

    private OpenListAdapter() {}

    // ── 路由 ──────────────────────────────────────────────────────────

    static byte[] fetch(String path, SiteProfile profile) throws Exception {
        String base = AdapterIo.base(profile, DEFAULT_HOST);
        JSONObject result;
        // 顺序不能换：/api/videos 下面三个动作靠**参数名**分流，不靠路径形状。
        if (path.startsWith("/api/home")) {
            result = home(base);
        } else if (path.startsWith("/api/tags")) {
            result = tags(base);
        } else if (path.startsWith("/api/search")) {
            result = search(base, HtmlKit.param(path, "q"), HtmlKit.page(path));
        } else if (path.startsWith("/api/videos")) {
            // id 在**路径段**上（/api/videos/{id}/play），不在查询串里 ——
            // 一开始按 HtmlKit.param("id") 取，取到的永远是空串，详情页标题就成了空。
            String rest = path.substring("/api/videos".length());
            int mark = rest.indexOf('?');
            String seg = mark >= 0 ? rest.substring(0, mark) : rest;
            String query = mark >= 0 ? rest.substring(mark + 1) : "";
            String cate = HtmlKit.param(query, "cate_id");
            if (cate.length() > 0) {
                result = listing(base, cate, HtmlKit.page(query));
            } else if (seg.endsWith("/play")) {
                result = play(base, decodeId(seg.substring(1, seg.length() - "/play".length())),
                        HtmlKit.param(query, "ep"));
            } else {
                result = detail(base, decodeId(seg.replaceFirst("^/", "")));
            }
        } else {
            throw new Exception("openlist 适配器不认识的路径：" + path);
        }
        JSONObject envelope = new JSONObject();
        envelope.put("data", result);
        return envelope.toString().getBytes(StandardCharsets.UTF_8);
    }


    // ── 三个动作 ──────────────────────────────────────────────────────

    /** 首页 = 全站作品索引第 1 页。前 6 个进轮播，剩下的进「最新」。 */
    private static JSONObject home(String base) throws Exception {
        JSONArray all = cardIndex(base, "/", "");
        JSONArray featured = new JSONArray();
        JSONArray latest = new JSONArray();
        for (int i = 0; i < all.length() && i < 24; i++) {
            JSONObject video = all.optJSONObject(i);
            if (video == null) continue;
            if (i < 6) featured.put(video);
            else latest.put(video);
        }
        JSONObject out = new JSONObject();
        out.put("featured", featured);
        out.put("latest", latest);
        out.put("cate_rec", new JSONArray());
        return out;
    }

    /** 分类页 = 某个大目录下的作品索引，本地切片翻页。 */
    private static JSONObject listing(String base, String dir, int page) throws Exception {
        return pageOf(cardIndex(base, dir, ""), page);
    }

    /** 搜索 = 全站作品索引 + 用户关键词。 */
    private static JSONObject search(String base, String keyword, int page) throws Exception {
        return pageOf(cardIndex(base, "/", keyword), page);
    }

    /** 索引切片 + 分页。索引是本地缓存的，所以页数算得出来。 */
    private static JSONObject pageOf(JSONArray all, int page) throws Exception {
        int size = PER_PAGE;
        int skip = (Math.max(1, page) - 1) * size;
        JSONArray items = new JSONArray();
        for (int i = skip; i < all.length() && items.length() < size; i++) {
            items.put(all.getJSONObject(i));
        }
        JSONObject out = new JSONObject();
        out.put("items", items);
        JSONObject pagination = new JSONObject();
        int pages = Math.max(1, (all.length() + size - 1) / size);
        pagination.put("page", page);
        pagination.put("pages", pages);
        pagination.put("size", items.length());
        pagination.put("total", all.length());
        out.put("pagination", pagination);
        return out;
    }

    /**
     * 详情 = 作品目录里递归捞出来的音频/视频文件。
     *
     * 递归两层的理由：作品目录下面是 `mp3版/`、`wav版/`、`イラスト/` 这种子目录，
     * 真正的文件在第三层。只认媒体扩展名，图片和 txt 自动被滤掉。
     */
    private static JSONObject detail(String base, String path) throws Exception {
        String name = leaf(path);
        JSONArray episodes = episodes(base, path);

        JSONObject out = new JSONObject();
        out.put("id", encodeId(path));
        out.put("title", name);
        out.put("cover", "");
        out.put("description", readmeOf(base, path));
        out.put("duration", "");
        out.put("score", "");
        out.put("category", parentName(path));
        out.put("extra", new JSONObject().put("files", episodes.length()));
        out.put("tags", new JSONArray());
        out.put("episodes", episodes);
        out.put("related", new JSONArray());
        return out;
    }

    /**
     * 播放 = fs/get 换直链。
     *
     * `ep` 是第几集（1 起）。episodes 里带 `path`（内部用，前端不看），
     * 这里直接拿它去换 raw_url —— 注意 raw_url 在**另一个域名**上
     * （asmr.121231234.xyz），播放器要能直连那个域名。
     */
    private static JSONObject play(String base, String path, String ep) throws Exception {
        JSONArray episodes = episodes(base, path);
        if (episodes.length() == 0) {
            throw new Exception("这个目录里没有能播的文件（可能只有图片和说明）");
        }
        int index = 1;
        try {
            index = Math.max(1, Integer.parseInt(ep.trim()));
        } catch (Exception ignored) {}
        if (index > episodes.length()) index = episodes.length();

        JSONObject entry = episodes.getJSONObject(index - 1);
        String filePath = entry.optString("path", "");
        String raw = rawUrl(base, filePath);
        if (raw.length() == 0) throw new Exception("站点没给这个文件的直链");

        JSONObject out = new JSONObject();
        out.put("source", raw);
        out.put("quality", 0);
        JSONArray lines = new JSONArray();
        JSONObject line = new JSONObject();
        line.put("quality", 0);
        line.put("label", isVideo(filePath) ? "原文件（视频）" : "原文件（音频）");
        line.put("url", raw);
        lines.put(line);
        out.put("lines", lines);
        out.put("cover", "");
        return out;
    }

    /** 标签页 = 根目录那几个大目录。 */
    private static JSONObject tags(String base) throws Exception {
        JSONArray roots = rootDirs(base);
        JSONArray items = new JSONArray();
        for (int i = 0; i < roots.length(); i++) {
            String name = roots.optString(i, "");
            if (name.length() == 0) continue;
            JSONObject tag = new JSONObject();
            tag.put("slug", "/" + name);
            tag.put("name", name);
            tag.put("group", "openlist");
            items.put(tag);
        }
        JSONObject group = new JSONObject();
        group.put("code", "openlist");
        group.put("name", "目录");
        group.put("tags", items);

        JSONObject out = new JSONObject();
        out.put("categories", new JSONArray().put(group));
        return out;
    }

    // ── 索引 ──────────────────────────────────────────────────────────

    /**
     * 作品索引 —— 这个文件站最难的一块。
     *
     * ── 为什么不按名字认作品 ──────────────────────────────────────────
     *
     * 第一版用 `keywords="[RJ"` 认作品，只对 asmr2 那种 dlsite 命名的站成立。
     * 实测 /asmr 底下是「中文音声 / 英语音声 / 橙澄子汉化组…」这种**组**，
     * 里面的作品大多没有 RJ 号（`5Gothicc_heel/Barber shop gonewild.m4a`），
     * 于是那一整个 Tab 几乎是空的。
     *
     * ── 改成按**目录深度**认 ──────────────────────────────────────────
     *
     * 站点结构实测（2026-10-18）：
     *
     *   /asmr/{组}/{作品}/[mp3版/]*.m4a        ← 作品在第 3 段
     *   /asmr2/{CV}/{作品}/mp3版/*.mp3         ← 作品在第 3 段
     *   /asmr4/{作者}/{年}/{日期}/*.woff2      ← 第 3 段是「年」，详情页再往下钻
     *   /asmr6/{组}/{子组}/{dir}/*.jpg         ← 图片站，没有可播文件
     *
     * 三个能播的形状里，**第 3 段**都是「点进去能出东西」的那一层：
     * 要么自己就有音频，要么下一层（mp3版 / 日期）有。所以卡片 = 第 3 段目录，
     * 详情页再从它往下钻两层（见 {@link #collect}）。
     *
     * 用 fs/search + scope=1 一次把这个 Tab 底下**所有**目录捞回来，按段数筛，
     * 一次请求就够一页 —— 比一层层 fs/list 快一个数量级（/asmr2 有 16 个 CV，
     * 逐层列要 17 次请求）。
     */
    static JSONArray worksIndex(String base, String parent, String keyword, int page, int perPage)
            throws Exception {
        JSONArray all = cardIndex(base, parent, keyword);
        JSONArray out = new JSONArray();
        int skip = (Math.max(1, page) - 1) * Math.max(1, perPage);
        for (int i = skip; i < all.length() && out.length() < perPage; i++) {
            out.put(all.getJSONObject(i));
        }
        return out;
    }

    /**
     * 某个目录下的全部卡片（作品），按路径去重，缓存 5 分钟。
     *
     * `wantSegs` = 3：/asmr2/野上菜月/[RJ…] 这种三段路径。
     * 根目录（parent="/"）也一样 —— 作品永远在第 3 段。
     */
    private static JSONArray cardIndex(String base, String parent, String keyword) throws Exception {
        String key = base + "|" + parent + "|" + (keyword == null ? "" : keyword);
        synchronized (CARD_CACHE) {
            CardCache cached = CARD_CACHE.get(key);
            if (cached != null && System.currentTimeMillis() - cached.at < CARD_TTL) {
                return new JSONArray(cached.cards.toString());
            }
        }

        JSONArray out = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        // 一次性把 2000 个目录捞回来（实测 8~9 秒），够十几页卡片了。
        // 目录特别多的 Tab 再翻第二页，最多两次 —— 再多就是拿首屏时间换深度。
        for (int searchPage = 1; searchPage <= 2 && out.length() < 120; searchPage++) {
            JSONObject body = new JSONObject();
            body.put("parent", parent);
            body.put("keywords", keyword == null ? "" : keyword);
            body.put("scope", 1);            // 只要目录
            body.put("page", searchPage);
            body.put("per_page", 2000);
            body.put("password", "");
            JSONObject data;
            try {
                data = postData(base + "/api/fs/search", body);
            } catch (Exception e) {
                log("openlist 索引失败 " + parent + " 第 " + searchPage + " 页：" + e);
                break;
            }
            JSONArray content = data.optJSONArray("content");
            if (content == null || content.length() == 0) break;

            for (int i = 0; i < content.length(); i++) {
                JSONObject entry = content.optJSONObject(i);
                if (entry == null || !entry.optBoolean("is_dir")) continue;
                String name = entry.optString("name", "");
                if (name.length() == 0) continue;
                String full = join(entry.optString("parent", parent), name);
                if (depth(full) != WORK_DEPTH) continue;
                if (!seen.add(full)) continue;
                out.put(card(full, name));
            }
            if (content.length() < 2000) break;
        }
        log("openlist 索引 " + parent + "：" + out.length() + " 张卡");

        synchronized (CARD_CACHE) {
            CARD_CACHE.put(key, new CardCache(out, System.currentTimeMillis()));
        }
        return out;
    }

    /** 一张卡片。没有封面，没有简介 —— 文件站本来就只有名字。 */
    private static JSONObject card(String path, String name) throws Exception {
        JSONObject video = new JSONObject();
        video.put("id", encodeId(path));
        video.put("title", name);
        video.put("cover", "");
        video.put("score", "");
        video.put("tags", new JSONArray());
        video.put("description", "");
        video.put("category", parentName(path));
        video.put("episodeCount", 0);
        video.put("badge", "");
        return video;
    }

    /**
     * 一个目录里的可播文件，递归两层。
     *
     * 结果按 id 缓存 —— 用户点进详情、再点某一集播放，走的是两条动作，
     * 不缓存就要把整棵树爬两遍。
     */
    static JSONArray episodes(String base, String path) throws Exception {
        String key = base + "|" + path;
        JSONArray cached = EPISODE_CACHE.get(key);
        if (cached != null) return new JSONArray(cached.toString());

        JSONArray out = new JSONArray();
        int[] budget = { MAX_FILES, MAX_DIRS };
        collect(base, path, out, 0, budget);
        EPISODE_CACHE.put(key, out);
        return new JSONArray(out.toString());
    }

    private static void collect(String base, String dir, JSONArray out, int depth, int[] budget)
            throws Exception {
        if (depth > 2 || budget[0] <= 0 || budget[1] <= 0) return;

        JSONArray entries = listEntries(base, dir);
        if (entries == null) return;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String name = entry.optString("name", "");
            if (name.length() == 0) continue;
            if (name.startsWith(".") || name.contains("__MACOSX")) continue;

            String full = join(dir, name);
            if (entry.optBoolean("is_dir")) {
                // 只往下钻「像是有音视频」的目录，别把 イラスト / 画像 也爬一遍
                if (depth < 2 && budget[1]-- > 0) collect(base, full, out, depth + 1, budget);
                continue;
            }
            long size = entry.optLong("size", 0);
            boolean disguised = false;
            if (!isMedia(name)) {
                // 伪装成 .jpg / .woff2 的视频（见 DISGUISE_EXT 上的注释）
                if (!isDisguisedMedia(name, size)) continue;
                disguised = true;
            }
            if (budget[0]-- <= 0) return;

            JSONObject ep = new JSONObject();
            ep.put("ep_num", out.length() + 1);
            ep.put("title", stripExt(name) + (disguised ? "（伪装视频）" : ""));
            ep.put("duration", "");
            ep.put("disguised", disguised);
            ep.put("path", full);      // 内部用：播放时换直链
            out.put(ep);
        }
    }

    // ── 原始请求 ──────────────────────────────────────────────────────

    /** fs/list → content 数组。目录不存在 / 要密码都返回 null，不抛。 */
    static JSONArray listEntries(String base, String path) {
        try {
            JSONObject body = new JSONObject();
            body.put("path", path);
            body.put("password", "");
            body.put("page", 1);
            body.put("per_page", 200);
            body.put("refresh", false);
            JSONObject data = postData(base + "/api/fs/list", body);
            return data.optJSONArray("content");
        } catch (Throwable e) {
            log("openlist 列目录失败 " + path + "：" + e);
            return null;
        }
    }

    /** fs/get → raw_url。 */
    static String rawUrl(String base, String path) throws Exception {
        JSONObject body = new JSONObject();
        body.put("path", path);
        body.put("password", "");
        JSONObject data = postData(base + "/api/fs/get", body);
        String raw = data.optString("raw_url", "");
        return raw == null ? "" : raw;
    }

    /**
     * 这个目录下有多少个目录（递归）。用来在探测阶段剔掉空大类 ——
     * 实测 asmr3 / asmr5 是空的（站点公告写着「ASMR3 维护中」），
     * 给它们留一个 Tab 就是让用户点进去看一片空白。
     */
    static int dirTotal(String base, String path) {
        try {
            JSONObject body = new JSONObject();
            body.put("parent", path);
            body.put("keywords", "");
            body.put("scope", 1);
            body.put("page", 1);
            body.put("per_page", 1);
            body.put("password", "");
            return postData(base + "/api/fs/search", body).optInt("total", 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 站点根下的目录名。探测和标签页都要用。 */
    static JSONArray rootDirs(String base) {
        JSONArray out = new JSONArray();
        JSONArray entries = listEntries(base, "/");
        if (entries == null) return out;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !entry.optBoolean("is_dir")) continue;
            String name = entry.optString("name", "");
            if (name.length() > 0) out.put(name);
        }
        return out;
    }

    /** 一次性 POST 并校验 AList 的 {code,message,data} 信封。 */
    private static JSONObject postData(String url, JSONObject body) throws Exception {
        byte[] raw = AiiHttp.postJsonRaw(url, body.toString(), SiteCfg.browserHeaders(), TIMEOUT);
        String text = new String(raw, StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(text);
        int code = json.optInt("code", 0);
        if (code != 200) {
            throw new Exception("站点返回 code=" + code + " " + json.optString("message", ""));
        }
        JSONObject data = json.optJSONObject("data");
        if (data == null) throw new Exception("响应里没有 data：" + text.substring(0, Math.min(160, text.length())));
        return data;
    }

    /** 目录的 readme 当简介。Markdown 剥掉，截 300 字。 */
    private static String readmeOf(String base, String path) {
        try {
            JSONObject body = new JSONObject();
            body.put("path", path);
            body.put("password", "");
            body.put("page", 1);
            body.put("per_page", 1);
            body.put("refresh", false);
            String readme = postData(base + "/api/fs/list", body).optString("readme", "");
            if (readme == null || readme.trim().length() == 0) return "";
            String text = readme.replaceAll("(?m)^#+\\s*", "")
                    .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                    .replaceAll("[*_`>]", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (text.length() > 300) text = text.substring(0, 300) + "…";
            return text;
        } catch (Throwable e) {
            return "";
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    /** id 里放的是路径，用 URL-safe base64 裹一层，免得斜杠/中文把路径参数弄坏。 */
    static String encodeId(String path) {
        byte[] bytes = (path == null ? "" : path).getBytes(StandardCharsets.UTF_8);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String decodeId(String id) {
        if (id == null || id.length() == 0) return "";
        try {
            return new String(java.util.Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return id;      // 不是 base64 就当成裸路径（调试时手敲的）
        }
    }

    static boolean isVideo(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        for (int i = 0; i < VIDEO_EXT.length; i++) if (lower.endsWith(VIDEO_EXT[i])) return true;
        return false;
    }

    /** 后缀本身像图片/字体 —— 但这个站上它们多半是视频。 */
    static boolean isDisguised(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        for (int i = 0; i < DISGUISE_EXT.length; i++) if (lower.endsWith(DISGUISE_EXT[i])) return true;
        return false;
    }

    /** 伪装成图片/字体、但体积又像视频的那种。 */
    static boolean isDisguisedMedia(String name, long size) {
        return isDisguised(name) && size >= DISGUISE_MIN_SIZE;
    }

    static boolean isMedia(String name) {
        if (isVideo(name)) return true;
        String lower = name == null ? "" : name.toLowerCase();
        for (int i = 0; i < AUDIO_EXT.length; i++) if (lower.endsWith(AUDIO_EXT[i])) return true;
        return false;
    }

    static String stripExt(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : (name == null ? "" : name);
    }

    static String join(String dir, String name) {
        String base = dir == null ? "" : dir.trim();
        if (base.length() == 0 || "/".equals(base)) return "/" + name;
        if (base.endsWith("/")) return base + name;
        return base + "/" + name;
    }

    /** 路径有几段（"/asmr2/CV/x" = 3）。 */
    static int depth(String path) {
        if (path == null) return 0;
        int n = 0;
        for (String seg : path.split("/")) if (seg.trim().length() > 0) n++;
        return n;
    }

    static String leaf(String path) {
        if (path == null) return "";
        String p = path.replaceAll("/+$", "");
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }

    static String parentName(String path) {
        if (path == null) return "";
        String p = path.replaceAll("/+$", "");
        int slash = p.lastIndexOf('/');
        if (slash <= 0) return "";
        String parent = p.substring(0, slash);
        return leaf(parent);
    }


}
