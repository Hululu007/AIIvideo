package com.huangguo.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AIIvideo —— hanime1.me 适配器（单站）。
 *
 * ── 为什么它是「单站适配器」而不是「族适配器」 ──────────────────────────
 * 按项目里那条规矩（CmsAdapter 是苹果CMS 族、AgedmAdapter 是 AGE动漫），
 * 一个适配器覆盖**一个族**的前提是「族里其他站也长这样」。hanime1 不是：
 *
 *   数据源   服务端渲染的 HTML（不是 JSON，没有任何 API）
 *   元数据   OG meta + DOM 选择器
 *   播放     页面内联 <source> + **服务端签发的 ?secure= 签名**
 *
 * 这三条没有第二条同族站能共享。所以它是一个站一份，别拿它去套别的站。
 *
 * ── 为什么必须走 HTML 解析 ─────────────────────────────────────────────
 * 这个站没有 JSON 接口，实测：
 *
 *   /api/            → 404  {"message":""}
 *   /api/search      → 404  {"message":""}
 *   /sitemap.xml     → 404  {"message":""}
 *
 * 它是 Laravel + jQuery 的纯 SSR 站。我们这轮改造已经把「抓 HTML」那套从
 * SiteRepository 里整块删了（3055 行），但那是**通用正则爬站**——用一个只有
 * 一个目标站的适配器把 HTML 解析收回来，和那套不是一回事：这里有确定的选择器表，
 * 而且每一处都配了真机实测的判据（见下面各方法的注释）。
 *
 * ⚠️ 这个适配器对应的**逆向笔记不在仓库里**。理由和 fixture 一样：那份笔记记的
 *    是目标站的真实内容（站点结构、页面样本、字段样例），而那个站是成人内容站 ——
 *    代码值得留，内容不值得进公开仓库。写笔记时按下面这套自己记一份即可，
 *    所有结论都能从代码注释和 fixtures/refresh.py 抓到的页面上复现。
 *
 * ── 播放：签名不能算，只能现取 ────────────────────────────────────────
 * 详情页内联三档 mp4：
 *
 *   <source src="…/408706-720p.mp4?secure=k55MpA1GNS6yC_CEOCsuMg==,1792206059">
 *
 * 签名绑**路径**和**时间戳**。实测四种改法：
 *
 *   原样重放                          → 206 video/mp4  ✅
 *   去掉 ?secure                      → 403            ❌
 *   secure 段换成等长假 base64        → 403            ❌
 *   真签名 + 时间戳前移 100000 秒      → 403            ❌
 *
 * 所以 secure 是服务端下发的，客户端算不出来，**也不需要算**——它就在页面里。
 *
 * TTL 实测约 12 小时（页面给的 ts 减当前时间 = 42083 秒）。而同一批列表页的
 * 封面签名 TTL 是 30 天 ~ 97 年。这说明签名是**按资源类型分档**的，不是全局
 * 过期时间。工程结论只有一条：
 *
 * ★ 播放地址零缓存。详情元数据可以缓存，播放地址必须每次穿透。★
 *
 * 这一条和 AgedmAdapter 的 DETAIL_CACHE 正好相反，不要在 play() 上复用它。
 *
 * ── ep 参数是画质，不是集数 ──────────────────────────────────────────
 * 这是单集站（一个 id 就是一部完整的片），没有「第几集」。我们的路径里那个
 * `{episode}` 占位符在这里被借用来表示**画质档位**：
 *
 *   ep=480 / 720 / 1080   → 对应页面的三档 <source>
 *   ep 缺省                → 720p（站点自己的 Plyr 默认值 quality.default=720）
 *
 * 站点固定三档，实测五个 id（408706 / 408704 / 408316 / 408315 / 406534）全是
 * 480+720+1080，没缺过。但**不要按 id-画质 拼 URL**，要从 DOM 里读——万一哪天
 * 站点给某部片少一档，拼出来的地址是 404 而不是降级。
 *
 * ── 接口形状（2026-10-16 实测，全部裸请求可拉，无 Cookie 无鉴权）────────
 *   GET /                                  144 张卡（10 个横排区）
 *   GET /search?genre=裏番&page=1           41 张（40 真 + 1 广告）
 *   GET /search?query=RD&page=1             搜索
 *   GET /search?sort=最新上傳&page=1        59 张
 *   GET /watch?v=408706                     详情 + 三档直链
 *
 *   翻页越界返回 **200 + 空列表**，不是 404。判空看条目数，不看状态码。
 *
 * ══════════════════════════════════════════════════════════════════════
 * ★★ 落地前必读：站点在 Cloudflare 后面，闸门卡在 TLS 指纹上 ★★
 * ══════════════════════════════════════════════════════════════════════
 *
 * 这是这个源**唯一没验证完的一环**，而且它决定整个源能不能用。
 *
 * 现象（2026-10-16 实测）：
 *
 *   curl / OpenSSL，裸请求                        → 403 Attention Required
 *   curl / OpenSSL，补全全部浏览器头（sec-ch-ua*、
 *   Sec-Fetch-*、Accept-Language、UA 全套）       → **仍然 403**
 *   curl_cffi impersonate=chrome99/110/131        → 200，144 张卡
 *   curl_cffi impersonate=safari17/firefox/edge   → 200，144 张卡
 *
 * 六种浏览器指纹全放行，只有 OpenSSL 那一族被拦。**而且判据不是 Cookie** ——
 * 浏览器里那份完好的会话只有 _ga 和 XSRF-TOKEN（Laravel 自己的 CSRF），
 * 没有任何 cf_clearance。所以它查的是 TLS ClientHello 与 HTTP/2 的指纹。
 *
 * JA3：curl 375c6162a492dfbf2795909110ce8424（拦）
 *      Chrome131 7e0c5f8498c43574d76173e7871417c0（放）
 *
 * ── 所以：本机 curl 试出来 403 **不能**得出「这站封了」；反过来，
 *    浏览器里能跑**也**不能得出「安卓能连」。两套 TLS 栈，两个结论。
 *
 * 安卓侧走 AiiHttp.getRaw() → OkHttp，TLS 是**平台实现**（Android 上是
 * Conscrypt，也就是 BoringSSL，和 Chrome 同一族；不是 curl 那个 OpenSSL）。
 * **大概率能过，但这是推断不是实测**：Conscrypt 的扩展顺序、曲线支持、
 * HTTP/2 SETTINGS 与桌面 Chrome 并不完全等价（它默认不发 GREASE），
 * 而 CF 是加权判定，同族不等于同指纹。
 *
 * ── 实测结果（2026-10-17，设备 7f8f5254）─────────────────────────────
 * **过了。** 首页 / 分类 / 详情 / 播放四层全通，logcat 里没有一次
 * `Attention Required`。所以「Conscrypt 与 Chrome 同族」这个推断成立 ——
 * 但注意它是**被验证过的推断**，不是定理：CF 改判定策略、或者 Android 换掉
 * TLS 实现，这条都可能失效。真遇到 403 时先看 logcat 里是不是挑战页，
 * 那说明是这一层的问题，不是解析坏了。
 *
 * ── 媒体域是另一条链路，没有这道闸 ─────────────────────────────────
 * vdownload.hembed.com 是 CDN77 的对象存储（响应里 x-rgw-object-type /
 * x-77-cache，没有 cf-ray），裸客户端带签名就 206，还带
 * access-control-allow-origin: *。所以「页面拉不到」和「片子能不能播」
 * 是两个独立问题，别把前者当成后者的结论。
 */
public final class Hanime1Adapter {

    private static final int TIMEOUT = 20;
    private static final String HOST = "https://hanime1.me";
    /** 媒体和封面同域。列表页、详情页、视频全在这一个域上。 */
    private static final String MEDIA_HOST = "vdownload.hembed.com";

    /** 首页取前多少个区。首页有 10 个横排区、144 张卡，全塞进去首屏太重。 */
    private static final int HOME_LIMIT = 48;
    /** 相关推荐条数。站点 allIds 给了 96 个，前 12 个才是真相关的。 */
    private static final int RELATED_LIMIT = 12;

    /**
     * 详情元数据缓存。**只给 detail() 用，play() 不许读它** —— 签名会过期。
     * 存的是解析好的 JSON（不是 HTML），TTL 靠简单 LRU 控量。
     */
    private static final Map<String, JSONObject> META_CACHE = new HashMap<String, JSONObject>();
    private static final int CACHE_LIMIT = 16;

    private Hanime1Adapter() {}

    // ── 对外入口 ──────────────────────────────────────────────────────

    /**
     * 按内部路径词汇表取数据，返回**我们形状**的 JSON 字节（外面套 {"data":…}）。
     * 由 AiiHttp.get() 在 adapter == "hanime1" 时分流过来。
     */
    public static byte[] fetch(String path, SiteProfile profile) throws Exception {
        JSONObject result;
        if (path.startsWith("/api/home")) {
            result = home();
        } else if (path.startsWith("/api/tags")) {
            result = tags();
        } else if (path.startsWith("/api/search")) {
            result = search(HtmlKit.param(path, "q"), HtmlKit.page(path));
        } else if (path.startsWith("/api/videos")) {
            // ── 三个动作共用 /api/videos 前缀，顺序不能换（2026-10-17 真机踩到）──
            //
            //   /api/videos?cate_id=X&page=N     分类列表
            //   /api/videos/{id}                 详情
            //   /api/videos/{id}/play?ep=N       播放
            //
            // 原来的写法是「有 /play 就 play，否则一律 detail」——于是**分类列表
            // 掉进了 detail**：videoId() 从一个没有 id 段的路径里解出空串，
            // 拼成 hanime1.me/watch?v= ，站点回 403。
            //
            // 真机表现是点底部「裏番」进去一片空白 + 一句
            // `HTTP 403 @ https://hanime1.me/watch?v=`，而 id 是空的 ——
            // 那个空 id 就是这里的锅，不是前端的。
            //
            // 判据用 cate_id 而不是「没有 /play」：cate_id 是分类接口独有的参数，
            // 拿它当标志位不会跟详情/播放撞车。
            //
            // 2026-10-17 加：240 个细标签走**另一条参数**。站点那边 genre= 和
            // tags[]= 是两回事（前者是 10 个大方向，后者是标签面板上那些多选框），
            // 所以这里按参数名分流，而不是按路径 —— 两条路共用 /api/videos。
            String tag = HtmlKit.param(path, "tag");
            String cate = HtmlKit.param(path, "cate_id");
            if (tag.length() > 0) {
                result = listingByTag(tag, HtmlKit.param(path, "genre"), HtmlKit.page(path));
            } else if (cate.length() > 0) {
                result = listingByGenre(cate, HtmlKit.page(path));
            } else if (path.indexOf("/play") > 0) {
                result = play(videoId(path), episode(path));
            } else {
                result = detail(videoId(path));
            }
        } else {
            throw new Exception("hanime1 适配器不认识的路径：" + path);
        }

        JSONObject envelope = new JSONObject();
        envelope.put("data", result);
        return envelope.toString().getBytes("UTF-8");
    }

    // ── 各个动作 ──────────────────────────────────────────────────────

    /**
     * 首页。整个页面是 SSR 的，一次 GET / 就能把 144 张卡全拿到。
     *
     * 分区信息（`.home-rows-title` 那种区标题）在首页 DOM 里不好稳定抠——它是
     * 布局 div 而不是语义 section，所以这里不按区拆，改成：
     *
     *   featured = 前 8 张（首页第一区，最新上架）
     *   latest   = 后面的（HOME_LIMIT 以内）
     *
     * 去重靠 id：同一个作品可能在多个区里重复出现，不去重首页会看到重影。
     */
    private static JSONObject home() throws Exception {
        String html = AdapterIo.get(HOST + "/");
        JSONArray all = parseCards(html, HOST + "/");

        JSONArray featured = new JSONArray();
        JSONArray latest = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();

        for (int i = 0; i < all.length() && i < HOME_LIMIT; i++) {
            JSONObject v = all.optJSONObject(i);
            if (v == null) continue;
            String id = v.optString("id", "");
            if (id.length() == 0 || !seen.add(id)) continue;
            if (featured.length() < 8) featured.put(v);
            else latest.put(v);
        }

        JSONObject out = new JSONObject();
        out.put("featured", featured);
        out.put("latest", latest);
        // adaptHome 读 cate_rec 做去重拼接。这个站没有独立的分区接口，留空数组。
        out.put("cate_rec", new JSONArray());
        return out;
    }

    /**
     * 列表 / 分类 / 搜索。三种请求形状合在一个动作里 —— 站点用的是同一个页面，
     * 只是 query string 不同：
     *
     *   ?genre=<分类>&page=N     分类
     *   ?query=<关键词>&page=N   搜索
     *   ?sort=最新上傳&page=N    最新
     *
     * 空关键词 + 空分类 = 首页那份（放到 latest 去）。
     */
    private static JSONObject search(String keyword, int page) throws Exception {
        String url;
        if (keyword != null && keyword.length() > 0) {
            url = HOST + "/search?query=" + HtmlKit.enc(keyword) + "&page=" + page;
        } else {
            url = HOST + "/search?sort=" + HtmlKit.enc("最新上傳") + "&page=" + page;
        }
        String html = AdapterIo.get(url);
        JSONArray items = parseCards(html, HOST + "/");

        JSONObject out = new JSONObject();
        out.put("items", items);
        out.put("pagination", pagination(page, items.length()));
        return out;
    }

    /**
     * 分类列表。cateId 就是站点的 genre 中文词（裏番 / 泡麵番 / 3DCG …），
     * 由 wrapHanime1() 生成进 categories 表。
     *
     * ⚠️ 它**只管那 10 个 genre**，不接标签。240 个细标签走 listingByTag()——
     *    两条路在站点那边是不同的参数（genre= vs tags[]=），别混。
     */
    public static JSONObject listingByGenre(String genre, int page) throws Exception {
        String url = HOST + "/search?genre=" + HtmlKit.enc(genre) + "&page=" + page;
        String html = AdapterIo.get(url);
        JSONArray items = parseCards(html, HOST + "/");
        JSONObject out = new JSONObject();
        out.put("items", items);
        out.put("pagination", pagination(page, items.length()));
        return out;
    }

    /**
     * 按标签查列表。这是 240 个细标签的落地方式。
     *
     * 站点的标签不是 URL 路径、也不是 genre，它是**表单里的多选 checkbox**：
     *
     *   <input name="tags[]" type="checkbox" value="巨乳"/>
     *
     * 提交成 GET 就长这样：
     *
     *   /search?genre=<分区>&tags[]=巨乳&page=2
     *
     * ── 两个坑（都实测过）───────────────────────────────────────────
     *
     * ① **`tags[]` 的方括号必须编码成 `%5B%5D`。** 直接写 `tags[]=` 会被
     *    某些中间层吃掉，表现是「点了标签出来的还是默认列表、看着像没过滤」。
     *
     * ② **不能只给 tags[]、不给 genre。** 实测 `/search?tags[]=巨乳`（无 genre）
     *    返回的是默认全站列表，标签像没生效；带上 `genre=裏番` 才对 ——
     *    `拳交` 那条从 41 张掉到 35 张，是过滤真的起作用的证据。
     *    所以这里默认挂一个 genre，允许调用方指定。
     *
     * seed 传空时用 GENRES[0]（裏番，站点最大的分类）。这个值是**兜底不是筛选
     * 意图** —— 站点要求一个 genre 才会认真过滤标签。
     */
    public static JSONObject listingByTag(String tag, String seed, int page) throws Exception {
        String genre = (seed == null || seed.length() == 0) ? GENRES[0] : seed;
        String url = HOST + "/search?genre=" + HtmlKit.enc(genre)
                + "&tags%5B%5D=" + HtmlKit.enc(tag)
                + "&page=" + page;
        String html = AdapterIo.get(url);
        JSONArray items = parseCards(html, HOST + "/");
        JSONObject out = new JSONObject();
        out.put("items", items);
        out.put("pagination", pagination(page, items.length()));
        return out;
    }

    /**
     * 详情。元数据基本全在 OG meta 里，比抠 DOM 稳。
     *
     * 注意 og:title 带 " - Hanime1.me" 后缀，og:description 是「标题 - 简介」，
     * twitter:description 是「标题 - 标签串 - Hanime1」，三条都要剥。
     */
    private static JSONObject detail(String id) throws Exception {
        JSONObject cached = META_CACHE.get(id);
        if (cached != null) return new JSONObject(cached.toString());

        String html = AdapterIo.get(HOST + "/watch?v=" + HtmlKit.enc(id));

        String ogTitle = meta(html, "property", "og:title");
        String title = stripSuffix(ogTitle, " - Hanime1.me");
        if (title.length() == 0) title = HtmlKit.stripTags(text(html, "<title>", "</title>"));

        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("title", title);
        out.put("cover", meta(html, "property", "og:image"));
        out.put("description", stripPrefix(meta(html, "property", "og:description"), title + " - "));

        // twitter:description = 「标题 - tag1, tag2, … - Hanime1」，剥两头取中间
        String tagsRaw = meta(html, "name", "twitter:description");
        out.put("tags", parseTags(tagsRaw, title));

        String duration = meta(html, "property", "og:video:duration");
        if (duration.length() > 0) out.put("duration", duration);

        // 单集站：一集，标题就是片名。界面上不会出现分集列表，但 episodes 不能缺 ——
        // 前端拿它决定「点播放走哪条路」。
        JSONArray episodes = new JSONArray();
        JSONObject ep = new JSONObject();
        ep.put("ep_num", 1);
        ep.put("title", title);
        ep.put("duration", duration);
        episodes.put(ep);
        out.put("episodes", episodes);

        // 相关推荐：页面里 12 个 watch 链接，跳过自己
        out.put("related", parseRelated(html, id));

        cache(id, out);
        return new JSONObject(out.toString());
    }

    // ── 播放 ──────────────────────────────────────────────────────────

    /**
     * 播放地址。**每次实时抓详情页，零缓存。**
     *
     * 这是签名机制决定的，不是性能取舍：页面给的 secure 约 12 小时过期，
     * 且改一位就 403。缓存等于「今天能看的片，明天用户点开是 403」。
     *
     * 也正因为如此，这里**不复用 detail() 的 META_CACHE**——那个缓存里的
     * episodes 是可以复用的，play 不行。
     */
    private static JSONObject play(String id, int quality) throws Exception {
        String html = AdapterIo.get(HOST + "/watch?v=" + HtmlKit.enc(id));
        JSONArray sources = parseSources(html);
        if (sources.length() == 0) {
            throw new Exception("这个页面里没有可播的 <source>（id=" + id
                    + "）。可能是：片子已下架、或者站点改了播放器结构。");
        }

        int want = quality <= 0 ? 720 : quality;
        String picked = null;
        JSONArray lines = new JSONArray();

        // 三档全交出去，让前端能切画质；同时按 want 挑一条当默认。
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s == null) continue;
            String url = s.optString("url", "");
            int q = s.optInt("quality", 0);
            if (url.length() == 0) continue;

            JSONObject line = new JSONObject();
            line.put("quality", q);
            line.put("label", q + "p");
            line.put("url", url);
            lines.put(line);

            if (q == want) picked = url;
        }
        // 精确档位没有（比如站点给了 1080+720+576），退到最接近的：优先不低于 want 的最小档
        if (picked == null) picked = closest(sources, want);

        JSONObject out = new JSONObject();
        out.put("source", picked);
        out.put("quality", want);
        out.put("lines", lines);
        out.put("cover", meta(html, "property", "og:image"));
        return out;
    }

    /** 从候选里挑最接近目标画质的。优先 ≥ want 的最小的，没有就取最大的。 */
    private static String closest(JSONArray sources, int want) {
        String best = null, fallback = null;
        int bestGap = Integer.MAX_VALUE, maxQ = -1;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s == null) continue;
            int q = s.optInt("quality", 0);
            String url = s.optString("url", "");
            if (url.length() == 0) continue;
            if (q >= want && q - want < bestGap) { bestGap = q - want; best = url; }
            if (q > maxQ) { maxQ = q; fallback = url; }
        }
        return best != null ? best : fallback;
    }

    /**
     * 抠 `<video><source>`。
     *
     * 页面里 source 是**不带闭合、也不带引号以外的属性**的裸标签：
     *   <source src="…" type="video/mp4">
     * 而且可能跨行，所以正则要容忍空白。
     *
     * 画质从文件名里读（`408706-720p.mp4`）——不从 DOM 顺序推，因为顺序
     * 不保证（实测是 720/480/1080，不是升序也不是降序）。
     */
    private static JSONArray parseSources(String html) {
        JSONArray out = new JSONArray();
        Matcher m = SOURCE_RE.matcher(html);
        while (m.find()) {
            String url = m.group(1);
            if (url == null || url.length() == 0) continue;
            if (url.startsWith("//")) url = "https:" + url;
            if (!url.startsWith("http")) continue;
            int q = qualityOf(url);
            JSONObject s = new JSONObject();
            try {
                s.put("url", url);
                s.put("quality", q);
            } catch (Exception ignored) {}
            out.put(s);
        }
        return out;
    }

    /** `408706-720p.mp4` → 720。读不出来给 0，后面 closest() 会兜。 */
    private static int qualityOf(String url) {
        Matcher m = QUALITY_RE.matcher(url);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return 0;
    }

    // ── HTML 解析 ─────────────────────────────────────────────────────

    /**
     * 从任意列表页抠卡片。**首页和列表页是两套 DOM 形状**（逆向文档坑 1），
     * 所以这里不按 class 找容器，改成：
     *
     *   找所有 a[href*="/watch?v="] → 校验 id → 在它附近找 img / .title / .duration
     *
     * 这样两套形状都能吃。**广告卡靠 href 过滤**：列表页里混着
     * l.erodalabs.com 的推广位，它跟真卡长一模一样，只有 href 不同。
     *
     * ★ **两套形状给的信息量不一样，别指望字段齐**（实测）：
     *
     *   首页  .video-item-container   有卡宽比（duration）★ 有点赞率（98%）★ 有上传者
     *   列表  .home-rows-videos-div   只有封面 + 标题。**duration/score/uploader 全没有**
     *
     * 所以列表页抠出来的对象里 badge/score/uploader 是空串 —— 这是站点的形状，
     * 不是解析失败。前端对空串应当是「不画那个角标」，而不是画一个空的。
     */
    private static JSONArray parseCards(String html, String baseForRelative) {
        JSONArray out = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        Matcher m = CARD_RE.matcher(html);

        while (m.find()) {
            String id = m.group(1);
            if (id == null || id.length() == 0) continue;
            if (!seen.add(id)) continue;

            // 从这个 <a 标签往后截一段当这条卡片的局部 HTML。截到下一个 <a 为止，
            // 避免把邻卡的标题吃进来。
            int start = m.start();
            int next = html.indexOf("<a ", m.end());
            String chunk = next > 0 ? html.substring(start, Math.min(next, start + 4000))
                                    : html.substring(start, Math.min(html.length(), start + 4000));

            JSONObject v = new JSONObject();
            try {
                v.put("id", id);

                String title = attr(chunk, "title");
                if (title.length() == 0) title = textOfClass(chunk, "title");
                if (title.length() == 0) title = textOfClass(chunk, "home-rows-videos-title");
                if (title.length() == 0) title = attrOfImg(chunk, "alt");
                v.put("title", title.trim());

                String cover = imgSrc(chunk);
                v.put("cover", cover);

                String dur = textOfClass(chunk, "duration");
                if (dur.length() > 0) v.put("badge", dur.trim());
                String stat = statText(chunk);
                if (stat.length() > 0) v.put("score", stat);

                String uploader = uploaderOf(chunk);
                if (uploader.length() > 0) v.put("uploader", uploader);
            } catch (Exception ignored) {}
            out.put(v);
        }
        return out;
    }

    /**
     * 相关推荐。`let allIds = [408706,408614,…]` 这行内联脚本直接给了 id 数组，
     * 比抠 DOM 快也稳。取不到就退回抠 watch 链接。
     *
     * ★ **必须截断。** 实测这个数组有 96 个 id（站点自己把「全部影片」的滑动
     *   列表塞进了一个 allIds，不只是「相关推荐」）。全交出去的话前端会画一条
     *   96 张卡的长龙，而且每张都没有标题、没有封面——只有 id。
     *
     * 数组里前 12 个是真正的相关推荐（和 DOM 里那 12 个 watch 链接一致，
     * 已验证），后面的是历史全量。所以取前 12 个。
     */
    private static JSONArray parseRelated(String html, String selfId) {
        JSONArray out = new JSONArray();
        Matcher m = ALLIDS_RE.matcher(html);
        if (m.find()) {
            String[] parts = m.group(1).split(",");
            for (int i = 0; i < parts.length && out.length() < RELATED_LIMIT; i++) {
                String id = parts[i].trim();
                if (id.length() == 0 || id.equals(selfId)) continue;
                JSONObject v = new JSONObject();
                try { v.put("id", id); } catch (Exception ignored) {}
                out.put(v);
            }
            if (out.length() > 0) return out;
        }
        // 退回：页面里的 watch 链接
        JSONArray cards = parseCards(html, HOST + "/");
        for (int i = 0; i < cards.length() && out.length() < RELATED_LIMIT; i++) {
            JSONObject v = cards.optJSONObject(i);
            if (v == null || selfId.equals(v.optString("id"))) continue;
            out.put(v);
        }
        return out;
    }

    /**
     * `twitter:description` → tags 数组。
     *   "[RD] Anastasya - 原神, 安娜絲塔夏…, POV, 1080p, … - Hanime1"
     * 剥掉头部的标题和尾部的站名，中间按逗号切。**画质词混在里面**（1080p），
     * 过滤掉——它不是标签，是规格。
     */
    private static JSONArray parseTags(String raw, String title) {
        JSONArray out = new JSONArray();
        if (raw == null || raw.length() == 0) return out;
        String s = stripSuffix(raw.trim(), " - Hanime1");
        if (title != null && title.length() > 0 && s.startsWith(title + " - ")) {
            s = s.substring(title.length() + 3);
        }
        String[] parts = s.split(",");
        for (int i = 0; i < parts.length; i++) {
            String tag = parts[i].trim();
            if (tag.length() == 0) continue;
            if (QUALITY_WORD.matcher(tag).matches()) continue;   // 1080p / 720p
            if ("中文字幕".equals(tag)) continue;                 // 规格不是标签
            out.put(tag);
        }
        return out;
    }

    // ── 标签（分类）表 ────────────────────────────────────────────────

    /**
     * 站点的分类就是首页导航那 10 个 genre，值是中文原词（不是代码）。
     * 硬编码是有意的：这个表是导航结构的一部分，不是数据。站点加了新分类
     * 需要改这里——但同理，站点改分类也不会让我们的标签悄悄变样。
     */
    private static final String[] GENRES = {
        "裏番", "泡麵番", "Motion Anime", "3DCG", "2.5D",
        "2D動畫", "AI生成", "MMD", "Cosplay", "新番預告"
    };

    /**
     * 标签面板的七个分区，以及它们折进我们哪一组。
     *
     * ★ 这一层才是这个站的精髓。它的 genre（上面那 10 个）只是**大方向**，
     *   真正决定「找得到想看的东西」的是这 240 个细标签 —— 实测抓下来：
     *
     *     影片屬性   9 个   無碼 / 中文字幕 / ASMR / 1080p / 60FPS …
     *     人物關係   9 个   近親 / 姐 / 妹 / 母 / 女兒 / 師生 / 青梅竹馬 …
     *     角色設定  47 个   JK / 御姐 / 熟女 / 人妻 / 護士 / 修女 / 魔法少女 …
     *     外貌身材  48 个   短髮 / 馬尾 / 巨乳 / 眼鏡娘 / 獸耳 / 異色瞳 …
     *     情境場所  25 个   校園 / 圖書館 / 保健室 / 游泳池 / 公眾場合 …
     *     故事劇情  46 个   純愛 / 後宮 / NTR / 痴漢 / BDSM / 獵奇 …
     *     性交體位  56 个   手交 / 乳交 / 肛交 / 3P / 群交 / 口交 …
     *
     * 七个分区折进我们已有的组名（见 site-default.json 的 ui.tagGroups），
     * 顺序就是站点面板里的顺序 —— 界面上看着一样。
     *
     * ⚠️ 这里**故意不复用 TAG_GROUP_BY_CODE**（那张表是 adapters.js 给接口
     *    返回的 code 用的）。我们的适配器在 Java 侧已经把 group 直接算好了，
     *    交上去的就是最终组名，前端不再折一次 —— 少一层映射少一处能对不上。
     */
    private static final String[][] TAG_SECTIONS = {
        // { 站点分区名,          我们的组 code,  界面上的组标题 }
        { "影片屬性", "hanime-attr",  "影片属性" },
        { "人物關係", "relationship", "人物关系" },
        { "角色設定", "hanime-role",  "角色设定" },
        { "外貌身材", "style",        "外貌身材" },
        { "情境場所", "setting",      "情境场所" },
        { "故事劇情", "plot",         "故事剧情" },
        { "性交體位", "hanime-act",   "性交体位" },
    };

    /**
     * 标签页。返回**站点那 240 个真实标签**，按 7 个分区交上去。
     *
     * 标签表是**从站点实时抓的**，不是硬编码 —— 站点加一个新标签（比如出了
     * 新题材），这边自动就有了。抓的位置是任意一个分类页的标签面板：
     *
     *   <h5>影片屬性</h5>
     *   <label class="hentai-tags-wrapper">
     *     <input name="tags[]" type="checkbox" value="無碼"/>
     *     <span class="checkmark">無碼</span>
     *   </label>
     *
     * 抓不到就退回 genre 那 10 个（至少别让标签页整个空掉）。
     *
     * slug 就是中文词本身 —— 点标签走 `/api/videos?cate_id=<词>`，但那个
     * **不是分类查询**：中文标签必须走 tags[] 参数（见 listingByTag）。
     */
    private static JSONObject tags() throws Exception {
        JSONArray groups = new JSONArray();
        try {
            String html = AdapterIo.get(HOST + "/search?genre=" + HtmlKit.enc(GENRES[0]));
            JSONArray sectionGroups = parseTagPanel(html);
            if (sectionGroups.length() > 0) {
                for (int i = 0; i < sectionGroups.length(); i++) groups.put(sectionGroups.optJSONObject(i));
            }
        } catch (Throwable e) {
            log("hanime1 标签面板抓取失败，退回 genre：" + e);
        }

        // 兜底：站点改了 DOM 或者这次请求挂了，至少给 genre 那一组
        if (groups.length() == 0) {
            JSONArray list = new JSONArray();
            for (int i = 0; i < GENRES.length; i++) {
                JSONObject tag = new JSONObject();
                try {
                    tag.put("name", GENRES[i]);
                    tag.put("slug", GENRES[i]);
                    tag.put("group", "hanime1");
                } catch (Exception ignored) {}
                list.put(tag);
            }
            JSONObject group = new JSONObject();
            group.put("code", "hanime1");
            group.put("tags", list);
            groups.put(group);
        }

        JSONObject out = new JSONObject();
        out.put("categories", groups);
        return out;
    }

    /**
     * 从分类页 HTML 里抠标签面板。
     *
     * 面板结构（2026-10-17 实测）：
     *
     *   <h4 class="modal-title">內容標籤</h4>   ← 面板起点
     *   …
     *   <h5 …>影片屬性</h5>                      ← 分区标题
     *   <label class="hentai-tags-wrapper">
     *     <input name="tags[]" type="checkbox" value="無碼"/>
     *     <span class="checkmark">無碼</span>
     *   </label>
     *   …
     *   <h5 …>人物關係</h5>                      ← 下一个分区
     *
     * 做法：先在 HTML 里找到面板起点，然后**顺序扫描** h5 和 value=，
     * 遇到 h5 就换分区。顺序扫描而不是查 DOM 树，是因为这段是从服务器
     * 拿的原始 HTML，正则比引入一个 HTML 解析器便宜得多。
     */
    private static JSONArray parseTagPanel(String html) {
        JSONArray groups = new JSONArray();
        int panel = html.indexOf("內容標籤");
        if (panel < 0) panel = html.indexOf("内容标签");
        if (panel < 0) return groups;
        // 面板后面还有很长的页面，截到「顯示搜索結果」那一带就够（标签全在它前面）
        int end = html.indexOf("顯示搜索結果", panel);
        String area = html.substring(panel, end > panel ? end : Math.min(html.length(), panel + 60000));

        // 顺序扫 h5 与 checkbox
        Matcher m = TAG_AREA_RE.matcher(area);
        String currentSection = null;
        JSONArray currentTags = null;
        while (m.find()) {
            String h5 = m.group(1);
            String value = m.group(2);
            if (h5 != null) {
                currentSection = cleanText(h5);
                currentTags = new JSONArray();
                continue;
            }
            if (value == null || currentSection == null) continue;
            String tag = cleanText(value);
            if (tag.length() == 0) continue;
            JSONObject one = new JSONObject();
            try {
                one.put("name", tag);
                one.put("slug", tag);
                one.put("group", groupCodeOf(currentSection));
            } catch (Exception ignored) {}
            currentTags.put(one);
            // 这个分区第一次出现时，把组挂上去
            if (currentTags.length() == 1) attachGroup(groups, currentSection, currentTags);
        }
        return groups;
    }

    /** 把某分区的标签列表挂进输出（组只建一次）。 */
    private static void attachGroup(JSONArray groups, String section, JSONArray tags) {
        String code = groupCodeOf(section);
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g != null && code.equals(g.optString("code"))) {
                try {
                    JSONArray existing = g.optJSONArray("tags");
                    for (int j = 0; j < tags.length(); j++) existing.put(tags.opt(j));
                } catch (Exception ignored) {}
                return;
            }
        }
        JSONObject group = new JSONObject();
        try {
            group.put("code", code);
            group.put("tags", tags);
        } catch (Exception ignored) {}
        groups.put(group);
    }

    /**
     * 分区名 → 我们的组 code。
     *
     * 认不出来的一律 `other`（前端会把它们收进「更多标签」那一组，不会丢）。
     * 分区名是繁体，两边都认一次，免得站点哪天改了字形就全落进 other。
     */
    private static String groupCodeOf(String section) {
        if (section == null) return "other";
        for (int i = 0; i < TAG_SECTIONS.length; i++) {
            if (TAG_SECTIONS[i][0].equals(section)) return TAG_SECTIONS[i][1];
        }
        return "other";
    }

    /** 组 code → 界面上的组标题。给 wrapHanime1 生成 ui.tagGroups 用。 */
    public static String[][] tagSections() {
        return TAG_SECTIONS;
    }

    // ── 小工具 ────────────────────────────────────────────────────────


    /** 拿 <meta property="og:title" content="…"> 这类。attr 是 "property" 或 "name"。 */
    private static String meta(String html, String attr, String key) {
        Pattern p = Pattern.compile("<meta[^>]*" + attr + "=[\"']" + Pattern.quote(key)
                + "[\"'][^>]*content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) return HtmlKit.unescape(m.group(1));
        // content 在前、属性在后的写法
        Pattern p2 = Pattern.compile("<meta[^>]*content=[\"']([^\"']*)[\"'][^>]*" + attr + "=[\"']"
                + Pattern.quote(key) + "[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(html);
        return m2.find() ? HtmlKit.unescape(m2.group(1)) : "";
    }

    /** 取 `class="xxx"` 那个元素里的文本。够用就行，不做完整 DOM。 */
    private static String textOfClass(String chunk, String cls) {
        Pattern p = Pattern.compile("class=[\"'][^\"']*\\b" + Pattern.quote(cls)
                + "\\b[^\"']*[\"'][^>]*>([^<]{0,300})", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(chunk);
        return m.find() ? HtmlKit.unescape(m.group(1)).trim() : "";
    }

    /** 卡片里第一个 img 的 src（含懒加载的 data-src）。 */
    private static String imgSrc(String chunk) {
        Matcher m = IMG_RE.matcher(chunk);
        while (m.find()) {
            String src = m.group(1);
            if (src == null || src.length() == 0) continue;
            if (src.contains("/image/icon/")) continue;   // 广告位图集，不是封面
            if (src.startsWith("//")) src = "https:" + src;
            return src;
        }
        return "";
    }

    private static String attrOfImg(String chunk, String name) {
        Matcher m = Pattern.compile("<img[^>]*\\b" + Pattern.quote(name) + "=[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(chunk);
        return m.find() ? HtmlKit.unescape(m.group(1)) : "";
    }

    private static String attr(String chunk, String name) {
        Matcher m = Pattern.compile("<[a-z]+[^>]*\\b" + Pattern.quote(name) + "=[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(chunk);
        return m.find() ? HtmlKit.unescape(m.group(1)) : "";
    }

    /** 列表页那句 `thumb_up 98%`。取出来当 score 用。 */
    private static String statText(String chunk) {
        Matcher m = Pattern.compile("thumb_up</i>\\s*([0-9]{1,3}%)").matcher(chunk);
        if (m.find()) return m.group(1);
        Matcher m2 = Pattern.compile("([0-9]{1,3})%").matcher(chunk);
        return m2.find() ? m2.group(1) + "%" : "";
    }

    private static String uploaderOf(String chunk) {
        Matcher m = Pattern.compile("class=[\"']subtitle[\"'][^>]*>\\s*<a[^>]*>([^<]{1,80})</a>",
                Pattern.CASE_INSENSITIVE).matcher(chunk);
        return m.find() ? HtmlKit.unescape(m.group(1)).trim() : "";
    }

    private static String text(String html, String open, String close) {
        int a = html.indexOf(open);
        if (a < 0) return "";
        int b = html.indexOf(close, a + open.length());
        return b < 0 ? "" : html.substring(a + open.length(), b);
    }


    private static String stripSuffix(String s, String suffix) {
        if (s == null) return "";
        s = s.trim();
        return s.endsWith(suffix) ? s.substring(0, s.length() - suffix.length()).trim() : s;
    }

    private static String stripPrefix(String s, String prefix) {
        if (s == null) return "";
        s = s.trim();
        return s.startsWith(prefix) ? s.substring(prefix.length()).trim() : s;
    }


    /** 详情缓存的 LRU。超量就丢掉最早的一批——不追求精确 LRU，够用。 */
    private static void cache(String id, JSONObject meta) {
        if (META_CACHE.size() >= CACHE_LIMIT) {
            java.util.Iterator<String> it = META_CACHE.keySet().iterator();
            int drop = CACHE_LIMIT / 4;
            while (it.hasNext() && drop-- > 0) { it.next(); it.remove(); }
        }
        META_CACHE.put(id, meta);
    }

    // ── 正则 ──────────────────────────────────────────────────────────

    /** `<source src="…" type="video/mp4">`。跨行、属性顺序不固定。 */
    private static final Pattern SOURCE_RE = Pattern.compile(
            "<source[^>]*\\bsrc=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    /** `-720p.mp4` → 720 */
    private static final Pattern QUALITY_RE = Pattern.compile("-(\\d{3,4})p\\b");
    /** 卡片链接。**只认 watch?v=数字** —— 广告卡 href 指向 l.erodalabs.com，自动掉队。 */
    private static final Pattern CARD_RE = Pattern.compile(
            "<a[^>]+href=[\"'](?:https?://hanime1\\.me)?/watch\\?v=(\\d+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_RE = Pattern.compile(
            "<img[^>]*\\bsrc=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    /** `let allIds = [408706,408614,…]` */
    private static final Pattern ALLIDS_RE = Pattern.compile(
            "allIds\\s*=\\s*\\[([0-9,\\s]{0,2000})\\]");
    /** `1080p` / `720P` 这种画质词 */
    private static final Pattern QUALITY_WORD = Pattern.compile("^\\d{3,4}[pP]$");

    /**
     * 标签面板里的两种东西，**顺序**扫出来：
     *   group(1) = `<h5 …>分区名</h5>`（有值就换分区）
     *   group(2) = `<input name="tags[]" … value="标签名">`（有值就是一个标签）
     *
     * 用一个正则交替匹配两者，是为了保住它们在 HTML 里的**先后顺序** ——
     * 分两次扫（先抓 h5 再抓 tag）会丢掉「哪个标签属于哪个分区」这个信息。
     */
    private static final Pattern TAG_AREA_RE = Pattern.compile(
            "<h5[^>]*>([^<]{1,20})</h5>|<input[^>]*name=[\"']tags\\[\\][\"'][^>]*value=[\"']([^\"']{1,40})[\"']",
            Pattern.CASE_INSENSITIVE);

    // ── 路径参数 ──────────────────────────────────────────────────────



    /** 从 `/api/videos/{id}` 或 `/api/videos/{id}/play` 里取 id。 */
    static String videoId(String path) {
        Matcher m = Pattern.compile("/api/videos/([^/?#]+)").matcher(path);
        return m.find() ? m.group(1) : "";
    }

    /** 借 {episode} 表示画质档位：480 / 720 / 1080。缺省 0 = 用 720。 */
    static int episode(String path) {
        String ep = HtmlKit.param(path, "ep");
        if (ep.length() == 0) ep = HtmlKit.param(path, "episode");
        try { return ep.length() == 0 ? 0 : Integer.parseInt(ep); }
        catch (Exception e) { return 0; }
    }


    /** 日志走 AiiHttp 的 tag，和别的 HTTP 记录在一条流里，方便对照。 */
    private static void log(String message) {
        AiiHttp.log(message);
    }

    /** 去掉标签里可能带的空白和 HTML 实体。 */
    private static String cleanText(String raw) {
        if (raw == null) return "";
        String s = HtmlKit.unescape(raw).replaceAll("<[^>]+>", "");
        return s.replaceAll("[\\s\u3000]+", "").trim().isEmpty() ? "" : s.trim();
    }

    /**
     * 页数没法从站点拿 —— 没有总数、没有 totalPage，也没有 JSON 接口。
     *
     * 判据只能反过来用：**「这一页条目数明显少于常规」就当成最后一页**。
     * 站点实测：分类页 41 张（40 真 + 1 广告，抠完 40）、搜索页 59 张。
     * 两个页面的常规量不一样，所以阈值取保守的 30 —— 低于它才认为到头了。
     *
     * 越界页返回 **200 + 空列表**（实测 page=9999 抠出 0 条），所以「空 = 结束」
     * 这条判据本身是可靠的；这里多给一页容差，让前端能自然地在拿到空页后停下。
     */
    private static JSONObject pagination(int page, int got) {
        JSONObject p = new JSONObject();
        try {
            p.put("page", page);
            p.put("pages", got >= END_THRESHOLD ? page + 1 : page);
        } catch (Exception ignored) {}
        return p;
    }

    /** 少于此数就认为列表到底了。见 pagination() 的说明。 */
    private static final int END_THRESHOLD = 30;
}
