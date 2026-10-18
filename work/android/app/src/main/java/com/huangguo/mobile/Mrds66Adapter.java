package com.huangguo.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AIIvideo —— 每日大赛（mrds66.com）单站适配器。
 *
 * ── 为什么是「单站」而不是「族」 ──────────────────────────────────────
 * 按项目里的规矩（CmsAdapter 覆盖苹果CMS 族、AgedmAdapter 覆盖 AGE动漫），
 * 一个适配器覆盖一个族的前提是「族里其他站也长这样」。这个站不是：
 *
 *   数据源   Typecho + Mirages 主题，服务端渲染的 HTML（`/api/*` 全 404）
 * 标识符   文章 id 是数字（196475），但它出现在 `/archives/{id}/` 里，
 *          和 KVS 那种 viewkey、苹果CMS 那种 vod_id 都不是一回事
 *   播放     页面里一个 `<div class="dplayer" data-config='{…}'>`，
 *          视频地址是**带 auth_key 签名的 m3u8**（AES-128 加密分片）
 *
 * 这三条没有第二条同族站能共享。所以它是一个站一份，别拿它去套别的 Typecho 站 ——
 * Mirages 主题的 DOM 结构（post-card / post-card-title / post-content）是这份代码
 * 的全部依据，换个主题就全错。
 *
 * ── 为什么必须走 HTML 解析 ─────────────────────────────────────────────
 * 实测（2026-10-18）：
 *
 *   /api/                 → 404 text/html
 *   /api/home             → 404 text/html
 *   /action/api.php       → 404 text/html
 *   /feed/                → 200 application/rss+xml（只有最新 10 条，没有分类/搜索）
 *
 * 它是纯 SSR 站。RSS 那条路看着诱人（结构化、好解析），但它**只给最新 10 条**，
 * 没有分类、没有搜索、没有分页，拿它做首页都不够一行。所以老老实实解析 HTML。
 *
 * ── 接口形状（2026-10-18 实测，全部裸请求可拉，无 Cookie 无鉴权）────────
 *
 *   GET /                              首页，45 个 <article>（其中 15 个是广告）
 *   GET /page/N/                       首页第 N 页，30 条
 *   GET /category/{slug}/              分类第 1 页，30 条
 *   GET /category/{slug}/N/            分类第 N 页
 *   GET /search/{词}/                  搜索第 1 页
 *   GET /search/{词}/N/                搜索第 N 页
 *   GET /tag/{词}/                     标签第 1 页
 *   GET /tag/{词}/N/                   标签第 N 页
 *   GET /archives/{id}/                详情 + 播放地址
 *   GET /archives.html                 往期（**不是**文章列表，是导航页，零张卡）
 *
 * 三个坑，都是实测撞出来的：
 *
 *   ① **第 1 页不带数字。** `/category/mrds/1/` 回 301（跳到 `/category/mrds/`），
 *      `/page/1/` 也是 301。所以拼 URL 时 page==1 要走短形式，别硬拼数字。
 *      301 本身 OkHttp 会跟，但多一跳、而且某些中间层会把 301 变成 200 + 空页，
 *      所以适配器自己绕开。
 *
 *   ② **越界页回 404，不是 200 + 空列表。** `/category/mrds/9999/` 是 404。
 *      这和 hanime1（200 + 空）相反 —— 所以「翻到底」的判断不能靠状态码兜，
 *      要读页面里的 `<span class="page-info">1/1889</span>`。
 *
 *   ③ **搜索无结果回 302 到 `/search-empty/`。** 跟过去是一张卡都没有的提示页。
 *      适配器拿到的是空数组，前端画「没搜到」，这是对的，别当异常抛。
 *
 * ── 播放：签名在页面里，不用算，但不能缓存 ────────────────────────────
 *
 * 详情页的播放器配置长这样（单引号属性，内容是 JSON）：
 *
 *   <div class="dplayer" data-video_id="196475001" data-config='{"live":false,…,
 *     "video":{"url":"https://hls.tpbbwcc.cn/videos5/<hash>/<hash>.m3u8?auth_key=…","type":"hls"},
 *     "video_h265":{"url":"https://hls.tpbbwcc.cn/m3m/<长hash>.m3u8?auth_key=…"}}'>
 *
 * auth_key 是 `{过期时间戳}-{随机段}-0-{md5}`。实测四种改法：
 *
 *   原样重放                            → 200 ✅
 *   去掉 ?auth_key                      → 400 ❌
 *   md5 段换成等长假值                   → 400 ❌
 *   时间戳前移 1 天 / 后移 30 天          → 400 ❌
 *
 * 所以签名绑时间戳和路径，客户端算不出来，**也不需要算** —— 它就在页面里。
 *
 * ★ 播放地址零缓存。详情元数据可以缓存，play() 必须每次穿透。★
 *   （和 Hanime1Adapter / Porn91Adapter 同一条规矩，别在 play() 上读 META_CACHE）
 *
 * ── m3u8 是 AES-128 加密的，key 单独一个 URL ─────────────────────────
 *
 *   #EXT-X-KEY:METHOD=AES-128,URI="https://ts.zhixunkeji.xyz/…/crypt.key?auth_key=…",IV=0x…
 *
 * key 和分片都在 ts.zhixunkeji.xyz，裸客户端能拉（200 / 16 字节 / 1.8MB 分片）。
 * 播放器是 libVLC，HLS + AES-128 是它原生能力，不需要我们解密 ——
 * 把 m3u8 地址交给它就行。**不要**在这里自己拼 key 请求。
 *
 * ── 单集站 ────────────────────────────────────────────────────────────
 * 一个 id 就是一部完整的片（哪怕标题里写着「1-3集」，页面也只给一个播放器）。
 * 所以 episodes 恒为 1 条，`{episode}` 占位符只为接住模板参数。
 *
 * ── 封面 ──────────────────────────────────────────────────────────────
 * 全站一个域名：`pic.jtzatly.cn`（对象存储，实测不校验 Referer、不校验 UA，
 * 带不带都 200）。封面地址在卡片的 `loadBannerDirect('…')` 里，
 * 详情页在 `<meta itemprop="image">` 里。
 */
public final class Mrds66Adapter {

    private static final int TIMEOUT = 20;

    /**
     * 兜底域名。**正常路径不用它** —— 真正用的地址从 profile.origin 取。
     *
     * 这类站的域名换得比版本还快（发布页上常年挂着三四个备用域），
     * 写死一个等于把源绑在一次性地址上。origin 由 UserSource 在识别时
     * 从用户填的地址里取，适配器只负责往上拼路径。
     */
    private static final String DEFAULT_HOST = "https://www.mrds66.com";

    /** 首页一次给 45 个 article（含 15 个广告），真卡 30 条。够两行。 */
    private static final int HOME_LIMIT = 30;
    /** 首页第一行「精选推荐」放几张。和 hanime1 取同一个数，前端那行正好填满。 */
    private static final int FEATURED_LIMIT = 8;
    /** 相关推荐条数。详情页只有上一篇/下一篇，取 2 条。 */
    private static final int RELATED_LIMIT = 2;

    /**
     * 详情元数据缓存。**只给 detail() 用，play() 不许读它** —— 签名会过期。
     * 存解析好的 JSON，不存 HTML。
     */
    private static final Map<String, JSONObject> META_CACHE = new HashMap<String, JSONObject>();
    private static final int CACHE_LIMIT = 16;

    /**
     * 站点的分类表。**slug 和显示名都是站点自己的**，一个没改。
     *
     * 顺序按站点导航栏的顺序（不是按数量），因为用户在设置里看到的那一列
     * 应该和网页上一致 —— 按数量排会让「每日大赛」这种主分类跑到中间去。
     *
     * 这 20 个是从首页导航栏直接抄的，每一个都实测 200 且有内容。
     * 站点加分类时这里要跟着加，漏了的表现是「分类页里少一个入口」，
     * 不会报错 —— 因为它是白名单，不是解析结果。
     */
    static final String[][] CATEGORIES = {
        { "mrds", "每日大赛" },
        { "ztds", "主题大赛" },
        { "rstt", "热搜吃瓜" },
        { "xazd", "校园学生" },
        { "blyp", "必撸大赛" },
        { "fctg", "反差泄密" },
        { "mhds", "网红黑料" },
        { "lqdp", "猎奇重口" },
        { "jdsj", "AV看片" },
        { "mxwh", "明星大赛" },
        { "smdh", "动漫之家" },
        { "dypd", "影视国漫" },
        { "mtds", "cos写真" },
        { "ysds", "声控ASMR" },
        { "czds", "寸止挑战" },
        { "hjds", "混剪PMV" },
        { "tgds", "原创投稿" },
        { "omjp", "欧美精品" },
        { "qwcs", "全网参赛" },
        { "aijc", "AI剧场" },
    };

    private Mrds66Adapter() {}

    // ── 对外入口 ──────────────────────────────────────────────────────

    /**
     * 按内部路径词汇表取数据，返回**我们形状**的 JSON 字节（外面套 {"data":…}）。
     * 由 AiiHttp.get() 在 adapter == "mrds66" 时分流过来。
     */
    public static byte[] fetch(String path, SiteProfile profile) throws Exception {
        String base = base(profile);
        JSONObject result;
        // 顺序不能换：/api/tags/{slug} 是 /api/tags 的长形式，前缀判断必须先精确后模糊。
        if (path.startsWith("/api/home")) {
            result = home(base);
        } else if (path.startsWith("/api/tags/")) {
            result = tagList(base, tagSlug(path), page(path));
        } else if (path.startsWith("/api/tags")) {
            result = tags();
        } else if (path.startsWith("/api/search")) {
            result = search(base, param(path, "q"), page(path));
        } else if (path.startsWith("/api/videos")) {
            // 三个动作共用 /api/videos 前缀，**判据用参数名而不是路径形状**
            // （Hanime1Adapter 在这上面踩过：按「有没有 /play」分流，分类列表
            //  掉进了 detail，videoId() 解出空串）。
            String cate = param(path, "cate_id");
            if (cate.length() > 0) {
                result = listing(base, cate, page(path));
            } else if (path.indexOf("/play") > 0) {
                result = play(base, videoId(path));
            } else {
                result = detail(base, videoId(path));
            }
        } else {
            throw new Exception("mrds66 适配器不认识的路径：" + path);
        }

        JSONObject envelope = new JSONObject();
        envelope.put("data", result);
        return envelope.toString().getBytes("UTF-8");
    }

    /**
     * 站点根。**从 profile.origin 取**，只有它空着才退回 DEFAULT_HOST。
     *
     * origin 已经在 SiteProfile 构造时校验过（裸 https、无路径无查询串），
     * 所以这里不用再校验一遍 —— 多一道只会把「origin 为空」这种合法情形
     * 误判成错误。
     */
    static String base(SiteProfile profile) {
        String origin = profile == null ? "" : profile.origin;
        if (origin == null) origin = "";
        origin = origin.replaceAll("/+$", "");
        return origin.length() == 0 ? DEFAULT_HOST : origin;
    }

    // ── 各个动作 ──────────────────────────────────────────────────────

    /**
     * 首页。打 `/`，一次请求。
     *
     * 分区怎么来：站点首页只有一列卡片流（没有「最新 / 热门 / 推荐」那种独立区块），
     * 所以按位置切：前 8 条进 featured（首页第一行），其余进 latest。
     *
     * **不额外请求一次分类来做精选。** porn91 那边多打一次是因为它首页只有 24 条、
     * 而「精选」是个真分类；这个站首页本来就有 30 条，再打一次分类只会让首屏慢
     * 一倍，换来的还是同一批内容。去重靠 id，保证两行不重影。
     */
    private static JSONObject home(String base) throws Exception {
        JSONArray all = parseCards(get(base + "/"), base);

        JSONArray featured = new JSONArray();
        JSONArray latest = new JSONArray();
        Set<String> seen = new HashSet<String>();

        for (int i = 0; i < all.length() && i < HOME_LIMIT; i++) {
            JSONObject v = all.optJSONObject(i);
            if (v == null) continue;
            String id = v.optString("id", "");
            if (id.length() == 0 || !seen.add(id)) continue;
            if (featured.length() < FEATURED_LIMIT) featured.put(v);
            else latest.put(v);
        }

        JSONObject out = new JSONObject();
        out.put("featured", featured);
        out.put("latest", latest);
        // adaptHome 读 cate_rec 做去重拼接。这个站没有分区接口，留空数组 ——
        // 那它就走 featured 那份，正好。
        out.put("cate_rec", new JSONArray());
        return out;
    }

    /**
     * 标签页。站点有真标签（详情页尾部一串 `/tag/{词}/`），但**没有标签索引页** ——
     * sitemap 里 0 个 tag 条目，也没有 `/tags` 这种路由。
     *
     * 所以这里不编一份假的标签表出来，只给分类：分类是站点唯一枚举得出来的维度。
     * 前端 renderTags 拿到一组 category 也能画（它按 group 分区，code 不在映射表里
     * 的折进 other），总比空页面好，也比「把 20 个分类伪装成 20 个标签」诚实。
     *
     * 标签的**落地页**仍然可用（走 tag-list 动作 → `/tag/{词}/`），
     * 只是入口得从详情页点进去，首页「全部类型」那一屏不会列出几千个标签。
     */
    private static JSONObject tags() throws Exception {
        JSONObject out = new JSONObject();
        JSONArray groups = new JSONArray();

        JSONObject group = new JSONObject();
        group.put("code", "mrds");
        group.put("name", "分类");
        JSONArray items = new JSONArray();
        for (int i = 0; i < CATEGORIES.length; i++) {
            JSONObject tag = new JSONObject();
            tag.put("slug", CATEGORIES[i][0]);
            tag.put("name", CATEGORIES[i][1]);
            tag.put("group", "mrds");
            items.put(tag);
        }
        group.put("tags", items);
        groups.put(group);

        out.put("categories", groups);
        return out;
    }

    /**
     * 分类列表。
     *
     * cateId 就是站点的分类 slug（mrds / ztds / …），由 wrapMrds66() 生成进
     * categories 表。不认识的 slug 站点回 **404**（不是「默认列表」），
     * 所以校验放在配置侧：只放实测过的那 20 个进去。
     */
    private static JSONObject listing(String base, String cate, int page) throws Exception {
        String html = get(pageUrl(base + "/category/" + enc(cate) + "/", page));
        JSONArray items = parseCards(html, base);

        JSONObject out = new JSONObject();
        out.put("items", items);
        out.put("pagination", pagination(html, page, items.length()));
        return out;
    }

    /**
     * 搜索。站点路由是 `/search/{词}/`，第 N 页 `/search/{词}/N/`。
     *
     * 空关键词**不发请求**：`/search//` 会跳到首页那份列表，看起来像
     * 「搜索成功了但结果全是热门」，比失败更糟。前端 apiPathFor 已经拦了一次，
     * 这里再拦一次是防御 —— 适配器也可能被别处直接调。
     *
     * 无结果时站点回 302 → `/search-empty/`，跟过去是 0 张卡。返回空 items，
     * 让前端画「没搜到」，不抛异常。
     */
    private static JSONObject search(String base, String keyword, int page) throws Exception {
        if (keyword == null || keyword.trim().length() == 0) {
            throw new Exception("搜索词为空，不发请求");
        }
        String html = get(pageUrl(base + "/search/" + enc(keyword.trim()) + "/", page));
        JSONArray items = parseCards(html, base);

        JSONObject out = new JSONObject();
        out.put("items", items);
        out.put("pagination", pagination(html, page, items.length()));
        return out;
    }

    /**
     * 按标签取列表。路由 `/tag/{词}/`，第 N 页 `/tag/{词}/N/`。
     *
     * 和搜索是**两条不同的路**：搜索是全文匹配（`/search/吃瓜/` 能搜到正文里提到
     * 吃瓜的文章），标签是文章自己挂的 tag（`/tag/吃瓜/` 只给打了这个 tag 的）。
     * 实测同一关键词两边条数不同，所以别拿搜索代替标签。
     */
    private static JSONObject tagList(String base, String slug, int page) throws Exception {
        if (slug == null || slug.length() == 0) throw new Exception("标签为空，不发请求");
        String html = get(pageUrl(base + "/tag/" + enc(slug) + "/", page));
        JSONArray items = parseCards(html, base);

        JSONObject out = new JSONObject();
        out.put("items", items);
        out.put("pagination", pagination(html, page, items.length()));
        return out;
    }

    /**
     * 详情。元数据在 article 的 itemprop 与 DOM 里。
     *
     * 能拿到的：
     *   标题      <h1 class="post-title" itemprop="name headline">…</h1>
     *   封面      <meta itemprop="image" content="https://pic.jtzatly.cn/…">
     *   发布时间  <meta itemprop="datePublished" content="2026-10-11T19:58:00+07:00">
     *   分类      breadcrumb 里的 /category/{slug}/
     *   标签      /tag/{词}/ 链接
     *   简介      .post-content 里播放器之前那几段（剥掉导航链接与广告）
     *   播放器    <div class="dplayer" data-config='{…}'>
     *
     * 单集站：一个 id 就是一部完整的片，episodes 恒 1 条 —— 前端拿它决定
     * 「点播放走哪条路」。
     */
    private static JSONObject detail(String base, String id) throws Exception {
        JSONObject cached = META_CACHE.get(id);
        if (cached != null) return new JSONObject(cached.toString());

        String html = get(base + "/archives/" + enc(id) + "/");

        String title = metaContent(html, "headline");
        if (title.length() == 0) title = h1Title(html);
        if (title.length() == 0) {
            throw new Exception("详情页里没找到标题（id=" + id
                    + "）。可能：文章已删除、或站点改版。");
        }

        String cover = metaContent(html, "image");
        String published = metaContent(html, "datePublished");

        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("title", title);
        out.put("cover", cover);
        out.put("description", description(html));
        out.put("duration", "");
        // 站点没有评分也没有播放量，别硬塞 —— 空着比给错的值好。
        out.put("score", "");
        out.put("category", categoryOf(html));

        JSONObject extra = new JSONObject();
        if (published.length() > 0) extra.put("published", published);
        out.put("extra", extra);

        out.put("tags", tagNames(html));

        JSONArray episodes = new JSONArray();
        JSONObject ep = new JSONObject();
        ep.put("ep_num", 1);
        ep.put("title", title);
        ep.put("duration", "");
        episodes.put(ep);
        out.put("episodes", episodes);

        out.put("related", related(html, id));

        cache(id, out);
        return new JSONObject(out.toString());
    }

    // ── 播放 ──────────────────────────────────────────────────────────

    /**
     * 播放地址。**每次实时抓详情页，零缓存。**
     *
     * auth_key 绑时间戳，缓存等于「过一会儿点开就是 400」。也正因如此，
     * 这里**不读 detail() 的 META_CACHE**。
     *
     * 页面给两档：
     *   video        H.264 m3u8（兼容性最好，默认给这个）
     *   video_h265   H.265 m3u8（体积小，但不是所有设备都硬解）
     *
     * 两档都进 lines，默认 source 用 H.264 —— 拿 H.265 当默认，在老设备上
     * 表现是「有画面没声音」或者直接黑屏，那比多耗点流量糟得多。
     *
     * @return source 是 m3u8 直链，libVLC 的 HLS 播放器直接吃（AES-128 由它解）。
     */
    private static JSONObject play(String base, String id) throws Exception {
        String html = get(base + "/archives/" + enc(id) + "/");
        JSONObject config = playerConfig(html);
        if (config == null) {
            throw new Exception("这个页面里没有播放器配置（id=" + id
                    + "）。可能：文章是纯图文、已下架、或站点换了播放器插件。");
        }

        String h264 = videoUrl(config, "video");
        String h265 = videoUrl(config, "video_h265");
        if (h264.length() == 0 && h265.length() == 0) {
            throw new Exception("播放器配置里没有可用地址（id=" + id + "）");
        }
        // 只有 H.265 时也得能播 —— 总比报错强。
        String source = h264.length() > 0 ? h264 : h265;

        JSONArray lines = new JSONArray();
        if (h264.length() > 0) {
            JSONObject line = new JSONObject();
            line.put("quality", 0);
            line.put("label", "原画");
            line.put("url", h264);
            lines.put(line);
        }
        if (h265.length() > 0) {
            JSONObject line = new JSONObject();
            line.put("quality", 1);
            line.put("label", "H.265");
            line.put("url", h265);
            lines.put(line);
        }

        JSONObject out = new JSONObject();
        out.put("source", source);
        out.put("quality", 0);
        out.put("lines", lines);
        out.put("cover", metaContent(html, "image"));
        return out;
    }

    /**
     * 从详情页抠播放器配置。返回 null 表示页面上没有 dplayer。
     *
     * `data-config` 是**单引号属性包着一段 JSON**，所以不能按双引号截 ——
     * JSON 里全是双引号，按 `"` 切会当场断在第一个键上。用单引号配对切。
     *
     * 实体转义：实测当前页面里没有 `&quot;` / `&#`，但 Typecho 的插件输出
     * 换个版本就可能转义。先 unescape 再 parse，成本一次字符串替换。
     */
    static JSONObject playerConfig(String html) {
        Matcher m = DPLAYER_RE.matcher(html);
        if (!m.find()) return null;
        String raw = m.group(1);
        if (raw == null || raw.length() == 0) return null;
        try {
            return new JSONObject(unescape(raw));
        } catch (Exception e) {
            log("dplayer 的 data-config 不是合法 JSON：" + e);
            return null;
        }
    }

    /** `{"video":{"url":"…","type":"hls"}}` → url。取不到给空串，不抛。 */
    static String videoUrl(JSONObject config, String key) {
        if (config == null) return "";
        JSONObject v = config.optJSONObject(key);
        if (v == null) return "";
        String url = v.optString("url", "");
        if (url.length() == 0) return "";
        // 站点给的是绝对地址。万一哪天变成相对路径，补上协议头 —— 不补的话
        // 播放器会当成文件路径，表现是「点了没反应」。
        if (url.startsWith("//")) return "https:" + url;
        return url;
    }

    // ── HTML 解析 ─────────────────────────────────────────────────────

    /**
     * 从列表页抠卡片。
     *
     * 切法：以 `<article` 为界切块，**跳过两类**：
     *
     *   ① `class="ad-item"` 的广告位。首页 45 个 article 里 15 个是这种，
     *      里面是外链 banner，没有 `/archives/`。不跳的话它们会被当成
     *      「解析失败的空卡片」，首页出现一堆空白格子。
     *
     *   ② **只有封面、没有标题的会员卡片。** 实测首页 30 张真卡里有 3 张、
     *      分类页 30 张里有 2 张是这种：`post-card-container` 里只有一个空的
     *      `post-card-info`，h2 整段不存在。封面和 id 都在，就是没标题。
     *      顺着 id 打开看，那几篇是纯图文（招聘帖、公告），**没有 dplayer** ——
     *      点进去是「有封面、有标题（其实空的）、点了转圈」的死卡片。
     *      所以直接丢：宁可一行少两张，也别让用户点到一个播不了的空页。
     *
     * 每块里取：
     *   id      `href="/archives/196475/"`
     *   标题    `post-card-title` 的文本（尾部还挂着「热搜 HOT」角标，要剥）
     *   封面    `loadBannerDirect('https://pic.jtzatly.cn/…'`
     *   日期    `datePublished" content="…"`
     *   分类    `<span>每日大赛, 热搜吃瓜</span>`
     *
     * 去重靠 id：站点会在「热门」和「最新」两个位置把同一篇画两遍。
     */
    static JSONArray parseCards(String html, String base) {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<String>();

        Matcher m = ARTICLE_RE.matcher(html);
        while (m.find()) {
            String chunk = m.group(0);
            // 广告位：`<article class="ad-item">`。判据用开头 80 字符，
            // 别在整块里找 "ad-item" —— 正文里也可能出现这个词。
            String head = chunk.length() > 80 ? chunk.substring(0, 80) : chunk;
            if (head.indexOf("ad-item") >= 0) continue;

            Matcher idm = ARCHIVE_ID_RE.matcher(chunk);
            if (!idm.find()) continue;
            String id = idm.group(1);
            if (id == null || id.length() == 0) continue;

            // 标题为空 = 会员卡片，没有播放器。见上面 ②。
            String title = cardTitle(chunk);
            if (title.length() == 0) continue;

            if (!seen.add(id)) continue;

            JSONObject v = new JSONObject();
            try {
                v.put("id", id);
                v.put("title", title);
                v.put("cover", cardCover(chunk));

                String date = dateOf(chunk);
                if (date.length() > 0) v.put("badge", date);

                String cate = cardCategory(chunk);
                if (cate.length() > 0) v.put("category", cate);
            } catch (Exception ignored) {}
            out.put(v);
        }
        return out;
    }

    /**
     * 卡片标题。`<h2 class="post-card-title" itemprop="headline">…<div class="wrap">
     * <span class="wraps">热搜 HOT</span></div></h2>`
     *
     * 标题文本和角标在同一个 h2 里，所以取完文本要把角标那段切掉 ——
     * 不切的话首页每张卡的标题后面都跟着「热搜 HOT」。
     */
    static String cardTitle(String chunk) {
        Matcher m = CARD_TITLE_RE.matcher(chunk);
        if (!m.find()) return "";
        String inner = m.group(1);
        // 角标在 <div class="wrap"> 里，整段剥掉
        inner = WRAP_BADGE_RE.matcher(inner).replaceAll(" ");
        String s = stripHtml(inner);
        return unescape(s).replaceAll("\\s+", " ").trim();
    }

    /**
     * 卡片封面。地址在 `loadBannerDirect('https://…', …)` 里。
     *
     * 这是主题的懒加载写法：`<div class="blog-background"></div>` 是空的，
     * 真图由 JS 塞进 background-image。所以按 `<img src>` 找会一张都找不到。
     *
     * 地址里有一个坑：实测见过 `https://pic.jtzatly.cn//upload_01/…`
     * （**双斜杠**）。浏览器和 VLC 都能吃，但我们的封面缓存按 URL 做 key，
     * 双斜杠会让同一张图存两份。所以这里顺手归一化掉。
     */
    static String cardCover(String chunk) {
        Matcher m = BANNER_RE.matcher(chunk);
        if (!m.find()) return "";
        return normalize(m.group(1));
    }

    /** 卡片日期：`<span itemprop="datePublished" content="2026-10-11T19:58:00+00:00">` */
    static String dateOf(String chunk) {
        Matcher m = DATE_RE.matcher(chunk);
        if (!m.find()) return "";
        String s = m.group(1);
        // 只留日期部分：卡上那个角标位置放得下一行日期，放不下 ISO 时间戳
        int t = s.indexOf('T');
        return t > 0 ? s.substring(0, t) : s;
    }

    /** 卡片分类：`<span>每日大赛, 热搜吃瓜</span>` */
    static String cardCategory(String chunk) {
        Matcher m = CARD_CATE_RE.matcher(chunk);
        if (!m.find()) return "";
        return unescape(stripHtml(m.group(1))).replaceAll("\\s+", " ").trim();
    }

    /** `<h1 class="post-title" itemprop="name headline">…</h1>` */
    static String h1Title(String html) {
        Matcher m = H1_RE.matcher(html);
        if (!m.find()) return "";
        return unescape(stripHtml(m.group(1))).replaceAll("\\s+", " ").trim();
    }

    /** `<meta itemprop="headline" content="…">`，属性顺序不固定，所以两个方向都试。 */
    static String metaContent(String html, String prop) {
        Matcher m = Pattern.compile(
                "<meta[^>]*itemprop=[\"'][^\"']*" + Pattern.quote(prop) + "[^\"']*[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (!m.find()) return "";
        Matcher c = Pattern.compile("content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
                .matcher(m.group(0));
        return c.find() ? unescape(c.group(1)).trim() : "";
    }

    /**
     * 正文摘要。`.post-content` 里播放器之前那几段。
     *
     * 为什么要切到播放器为止：播放器后面还有「关键词：#…」和一张分类导航表格，
     * 那些不是简介，塞进详情页会让简介区变成一堆链接。
     *
     * 还要剥 `<a>`：正文开头挂着一长串「在线约炮 / 春药高潮 / 棋牌电子 …」
     * 的广告友链，全是 `<a>`。只剥标签不剥链接的话，简介第一句就是广告。
     *
     * 长度上限 300 字：详情页简介区只显示几行，给全文是浪费 —— 而且正文里
     * 有大量重复的关键词堆砌，读起来像乱码。
     */
    static String description(String html) {
        int at = html.indexOf("class=\"post-content");
        if (at < 0) return "";
        // 从标签的 `<` 开始切，不是从 `class=` 开始 —— 否则开头那半个标签
        // （`class="post-content" itemprop="articleBody">`）会留在正文里，
        // 详情页第一行就是这串属性。
        int lt = html.lastIndexOf('<', at);
        int start = lt >= 0 ? lt : at;
        int player = html.indexOf("<div class=\"dplayer\"", start);
        int end = player > start ? player : Math.min(html.length(), start + 20000);
        String seg = html.substring(start, end);

        seg = SCRIPT_RE.matcher(seg).replaceAll(" ");
        seg = STYLE_RE.matcher(seg).replaceAll(" ");
        seg = COMMENT_RE.matcher(seg).replaceAll(" ");
        seg = LINK_RE.matcher(seg).replaceAll(" ");

        String text = stripHtml(seg).replaceAll("\\s+", " ").trim();
        text = unescape(text);
        if (text.length() > 300) text = text.substring(0, 300) + "…";
        return text;
    }

    /** 详情页的分类：breadcrumb 里第一个 `/category/{slug}/`。 */
    static String categoryOf(String html) {
        Matcher m = CATEGORY_LINK_RE.matcher(html);
        return m.find() ? m.group(1) : "";
    }

    /** 详情页的标签名：`/tag/{词}/` 里的词，URL 解码后返回。 */
    static JSONArray tagNames(String html) {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<String>();
        Matcher m = TAG_LINK_RE.matcher(html);
        while (m.find() && out.length() < 12) {
            String raw = m.group(1);
            if (raw == null || raw.length() == 0) continue;
            String name = percentDecode(raw);
            if (name.length() == 0 || !seen.add(name)) continue;
            out.put(name);
        }
        return out;
    }

    /**
     * 相关推荐：详情页尾部的「上一篇 / 下一篇」。
     *
     * 这个站没有「相关文章」区块，只有 post-near 那两个链接。两条也够 ——
     * 前端那一行是横向滚动，有内容就画得出来。
     */
    static JSONArray related(String html, String selfId) {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<String>();
        Matcher m = NEAR_RE.matcher(html);
        while (m.find() && out.length() < RELATED_LIMIT) {
            String id = m.group(1);
            String title = unescape(stripHtml(m.group(2))).replaceAll("\\s+", " ").trim();
            if (id == null || id.length() == 0) continue;
            if (selfId != null && selfId.equals(id)) continue;
            if (!seen.add(id)) continue;
            JSONObject v = new JSONObject();
            try {
                v.put("id", id);
                v.put("title", title);
            } catch (Exception ignored) {}
            out.put(v);
        }
        return out;
    }

    /**
     * 分页。站点把「当前页/总页数」直接写在页面里：
     *
     *   <span class="page-info"> 1/1889 </span>
     *
     * 这是**唯一可靠的总页数来源** —— 越界页回 404（不是 200 + 空），
     * 所以不能靠「这页有没有内容」判断到底了。
     *
     * 读不到时给 pages = page（让前端停在原地），**不猜**：
     * 给一个假的总页数会让用户翻到 404 页。
     */
    static JSONObject pagination(String html, int page, int got) {
        JSONObject p = new JSONObject();
        try {
            p.put("page", page);
            p.put("size", got);
            int pages = totalPages(html);
            if (pages > 0) {
                p.put("pages", Math.max(page, pages));
            } else {
                // 拿不到总数：这一页没满就当作到底了
                p.put("pages", got > 0 ? page + 1 : page);
            }
        } catch (Exception ignored) {}
        return p;
    }

    /** `1/1889` → 1889。 */
    static int totalPages(String html) {
        Matcher m = PAGE_INFO_RE.matcher(html);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1).trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    // ── 正则 ──────────────────────────────────────────────────────────

    /** 一个 `<article …>…</article>` 块。非贪婪 + DOTALL，跨行。 */
    private static final Pattern ARTICLE_RE = Pattern.compile(
            "<article\\b.*?</article>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** `<a href="/archives/196475/">` / `<meta itemprop="url mainEntityOfPage" content="/archives/196475/">` */
    private static final Pattern ARCHIVE_ID_RE = Pattern.compile(
            "/archives/(\\d+)/");

    /** 卡片标题的 h2 内容（含角标）。 */
    private static final Pattern CARD_TITLE_RE = Pattern.compile(
            "<h2[^>]*class=[\"'][^\"']*post-card-title[^\"']*[\"'][^>]*>(.*?)</h2>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 标题里那段角标：`<div class="wrap"><span class="wraps">热搜 HOT</span></div>` */
    private static final Pattern WRAP_BADGE_RE = Pattern.compile(
            "<div[^>]*class=[\"'][^\"']*\\bwrap\\b[^\"']*[\"'][^>]*>.*?</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** `loadBannerDirect('https://pic.jtzatly.cn/…'` */
    private static final Pattern BANNER_RE = Pattern.compile(
            "loadBannerDirect\\(\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /** `<span itemprop="datePublished" content="2026-10-11T19:58:00+00:00">` */
    private static final Pattern DATE_RE = Pattern.compile(
            "itemprop=[\"']datePublished[\"'][^>]*content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    /** 卡片的分类串：`<div class="post-card-info">…<span>每日大赛, 热搜吃瓜</span>` */
    private static final Pattern CARD_CATE_RE = Pattern.compile(
            "<div[^>]*class=[\"'][^\"']*post-card-info[^\"']*[\"'][^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 详情页 h1。 */
    private static final Pattern H1_RE = Pattern.compile(
            "<h1[^>]*class=[\"'][^\"']*post-title[^\"']*[\"'][^>]*>(.*?)</h1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 详情页里第一个 `/category/{slug}/` 链接。 */
    private static final Pattern CATEGORY_LINK_RE = Pattern.compile(
            "href=[\"']/category/([a-z0-9_-]+)/", Pattern.CASE_INSENSITIVE);

    /** `/tag/{词}/`，词是 URL 编码过的中文。 */
    private static final Pattern TAG_LINK_RE = Pattern.compile(
            "href=[\"']/tag/([^/\"']+)/", Pattern.CASE_INSENSITIVE);

    /** 上一篇/下一篇：`<a href="/archives/196424/" title="…">…</a>` */
    private static final Pattern NEAR_RE = Pattern.compile(
            "<a\\s+href=[\"']/archives/(\\d+)/[\"']\\s+title=[\"']([^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE);

    /** 播放器：`<div class="dplayer" … data-config='{…}'>`，单引号包 JSON。 */
    private static final Pattern DPLAYER_RE = Pattern.compile(
            "<div[^>]*class=[\"'][^\"']*\\bdplayer\\b[^\"']*[\"'][^>]*data-config='([^']*)'",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** `1/1889`。前后可能有空白。 */
    private static final Pattern PAGE_INFO_RE = Pattern.compile(
            "class=[\"']page-info[\"'][^>]*>\\s*\\d+\\s*/\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SCRIPT_RE = Pattern.compile(
            "<script\\b.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STYLE_RE = Pattern.compile(
            "<style\\b.*?</style>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMMENT_RE = Pattern.compile(
            "<!--.*?-->", Pattern.DOTALL);
    private static final Pattern LINK_RE = Pattern.compile(
            "<a\\b.*?</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // ── 路径参数 ──────────────────────────────────────────────────────

    static String param(String path, String key) {
        Matcher m = Pattern.compile("[?&]" + Pattern.quote(key) + "=([^&#]*)").matcher(path);
        if (!m.find()) return "";
        return percentDecode(m.group(1));
    }

    static int page(String path) {
        String p = param(path, "page");
        try { return p.length() == 0 ? 1 : Math.max(1, Integer.parseInt(p)); }
        catch (Exception e) { return 1; }
    }

    /** 从 `/api/videos/{id}` 或 `/api/videos/{id}/play` 里取 id。 */
    static String videoId(String path) {
        Matcher m = Pattern.compile("/api/videos/([^/?#]+)").matcher(path);
        return m.find() ? m.group(1) : "";
    }

    /** 从 `/api/tags/{slug}?page=N` 里取标签词。 */
    static String tagSlug(String path) {
        Matcher m = Pattern.compile("/api/tags/([^/?#]+)").matcher(path);
        return m.find() ? percentDecode(m.group(1)) : "";
    }

    /**
     * 分页 URL。**第 1 页不带数字** —— 站点对 `/category/mrds/1/` 回 301，
     * 对 `/page/1/` 也回 301。所以 page==1 走短形式。
     *
     * @param first 第 1 页的地址，**以 / 结尾**
     */
    static String pageUrl(String first, int page) {
        if (page <= 1) return first;
        return first + page + "/";
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    /**
     * 一次 GET。走 getRaw：朴素客户端、不继承站点 headers 模板、不套 DoH。
     *
     * ── UA 必须伪装，2026-10-18 起 ──────────────────────────────────
     * 这条注释原来写的是「不需要伪装 UA」，那时候确实四种 UA 都 200。现在不是了：
     * CloudFront 加了条规则，`okhttp/*` 一律 403 —— 而且**只挡部分路径**：
     *
     *   GET /                    UA=okhttp → 200   ← 探针因此一直没报错
     *   GET /category/mrds/      UA=okhttp → 403   ← 分类页整块打不开
     *   GET /archives/431273.html UA=okhttp → 403  ← 详情页整块打不开
     *   GET /tag/吃瓜/           UA=okhttp → 403
     *
     * 首页 200 是最坏的那种情况：探针过、配置生成得出来、App 看起来是好的，
     * 点进任何一个分类或详情才 403。用 Dalvik 或安卓 Chrome 的 UA，上面四条全 200。
     */
    private static String get(String url) throws Exception {
        byte[] body = AiiHttp.getRaw(url, SiteCfg.browserHeaders(), TIMEOUT);
        return new String(body, "UTF-8");
    }

    /** 双斜杠归一化：`https://pic.jtzatly.cn//upload_01/…` → `…/upload_01/…`。 */
    static String normalize(String url) {
        if (url == null) return "";
        String s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme < 0) return s;
        int pathStart = scheme + 3;
        // 域名之后那一段可能是一串斜杠
        int i = pathStart;
        while (i < s.length() && s.charAt(i) != '/') i++;
        if (i >= s.length()) return s;
        int j = i;
        while (j < s.length() && s.charAt(j) == '/') j++;
        return s.substring(0, i) + "/" + s.substring(j);
    }

    /** 剥标签。不处理实体 —— 调用方各自 unescape。 */
    static String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", " ");
    }

    /** 常见 HTML 实体。够用就好：这几个是 Typecho 模板真正会吐出来的。 */
    static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }

    /**
     * 只解 %XX，不动 `+`。
     *
     * 搜索词和标签词都是中文，URL 里是 `%E5%90%83%E7%93%9C` 这种。
     * **不用 URLDecoder.decode()** —— 那个会把 `+` 解成空格，而标签词里
     * 出现 `+` 的概率不为零（比如「C++」），解错了就查不到。
     */
    static String percentDecode(String raw) {
        if (raw == null || raw.length() == 0) return "";
        // ★ 必须按**字节**攒，不能按 char 拼。
        //
        // 上一版是 `sb.append((char) ((hi << 4) | lo))` —— 对 ASCII 没事，对中文
        // 直接坏掉：`%E5%90%83` 是 UTF-8 的三个字节，逐个当 char 追加等于把
        // 一个字符拆成三个 U+00E5/U+0090/U+0083，解出来是「å]ç」这种乱码。
        // 标签名和搜索词全是中文，所以这条路一走就全废 —— 单测当场抓到。
        //
        // 现在：`%XX` 解成字节进缓冲，碰见非转义的字符先把缓冲冲成 UTF-8 串；
        // 末尾再冲一次。**仍然不动 `+`** —— 它在这个站上是合法字符，
        // 不是空格（见上面 percentDecode 的说明）。
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(raw.length());
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    buf.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            if (buf.size() > 0) {
                sb.append(new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                buf.reset();
            }
            sb.append(c);
            i++;
        }
        if (buf.size() > 0) {
            sb.append(new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** 只对非安全字符做百分号编码，别把中文原样塞进 URL。 */
    static String enc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append('%').append(String.format("%02X", b & 0xFF));
                }
            }
        }
        return sb.toString();
    }

    /** 详情元数据缓存。满了清空 —— 这个站一页 30 条，16 条够回看，不做 LRU 那套。 */
    private static void cache(String id, JSONObject meta) {
        try {
            if (META_CACHE.size() >= CACHE_LIMIT) META_CACHE.clear();
            META_CACHE.put(id, new JSONObject(meta.toString()));
        } catch (Exception ignored) {}
    }

    private static void log(String message) {
        AiiHttp.log(message);
    }
}
