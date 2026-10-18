package com.huangguo.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AIIvideo —— 91porn（KVS 模板）单站适配器。
 *
 * ── 为什么是「单站」而不是「族」 ──────────────────────────────────────
 * 按项目里的规矩（CmsAdapter 覆盖苹果CMS 族、AgedmAdapter 覆盖 AGE动漫），
 * 一个适配器覆盖一个族的前提是「族里其他站也长这样」。这个站不是：
 *
 *   数据源   服务端渲染的 HTML（KVS 模板，`/api/*` 全 404，无 JSON 接口）
 * 标识符    viewkey 是**不透明串**，两种形态混用（见下）
 *   播放     页面内联 `document.write(strencode2("…"))`，URL 编码后再吐出 <source>
 *
 * 这三条没有第二条同族站能共享。KVS 有一堆镜像站，但每家的模板改过、
 * 播放壳不一样，套过去只会得到「首页一片空白」而不是明确报错 —— 所以不认镜像域。
 *
 * ── 接口形状（2026-10-18 实测，全部裸请求可拉，无 Cookie 无鉴权）──────
 *
 *   GET /v.php                                   最新 24 条（147579 条总量）
 *   GET /v.php?category=<code>&page=N            分类列表，每页 24
 *   GET /view_video.php?viewkey=<vk>             详情 + 播放地址
 *   GET /search_result.php?search_id=<q>&search_type=search_videos&page=N
 *
 * 实测分类 code（十个，全部 200 且有数据）：
 *
 *   ori     91 原创          15573
 *   hot     热门             60
 *   top     本周排行         899
 *   long    10 分钟以上      17450
 *   longer  20 分钟以上      5827
 *   tf      收藏             5008
 *   rf      精选             13299
 *   hd      高清             94690
 *   md      最近讨论         5008
 *   mf      最多收藏         147579
 *
 * ── 坑 1：首页有年龄门，`/` 不是内容页 ────────────────────────────────
 * `GET /` 返回 **1616 字节的确认页**（"我已年满18岁…"），里面零张卡。
 * 真正的内容入口是 `/index.php`（24 条）和 `/v.php`（24 条）。所以
 * home() 打的是 `/v.php`，探针也是。拿 `/` 当探针会得到「站点没内容」的假结论。
 *
 * ── 坑 2：卡片会重复，而且重复的那份带不同的 c 参数 ────────────────────
 * 一页 24 条卡片，`viewkey=` 出现 **38 次**：站点把同一条视频在
 * `col-lg-3` 和 `col-lg-8` 两种栅格里各画一遍（`c=uyklk` / `c=auyklk`）。
 * 不去重的话首页会看到重影。去重键用 viewkey，**不是**封面 URL ——
 * 实测同一张封面图会被不同视频复用（`1252400.jpg` 同时挂在两条上）。
 *
 * ── 坑 3：viewkey 有两种形态，不能假设是纯数字 ────────────────────────
 * 实测同时存在：
 *   1555330457              纯数字 10 位（老 id，等于内部 VID）
 *   51dc03e8e27a694f2fc7    20 位小写十六进制（新 id）
 * 所以正则必须是 `[0-9a-zA-Z]+`，写成 `\d+` 会把一半片子漏掉 ——
 * 而且漏得没声音（首页少一半卡，看起来像「这个源就这些内容」）。
 *
 * ── 坑 4：播放地址在 document.write 里，且页面里有一个**诱饵** ──────────
 *
 * 页面 `<video>` 里躺着两条 source：
 *
 *   <!-- <source src="https://ccm.91p52.com/358999.mp4?st=…"> -->     ← 被注释掉的诱饵
 *   <script>document.write(strencode2("%3c%73%6f%75%72%63%65…"))</script>  ← 真的
 *
 * 诱饵那条**全站固定**（每个详情页都是同一个 358999.mp4），谁按
 * `<source src=` 抠就会把所有片子都播成同一部。所以解析顺序必须是：
 * 先解 strencode2，解不出来才退回裸 <source>，而且退回时要跳过注释段。
 *
 * strencode2 本身只是 `unescape`（百分号解码），不是加密：
 *   "%3c%73%6f%75%72%63%65%20%73%72%63%3d%27…%27%3e" → "<source src='…'>"
 * 定义在 /js/m2.js 里（jsjiami 混淆过），行为等价于 decodeURIComponent。
 *
 * ── 坑 5：签名 TTL 约 22 分钟 ────────────────────────────────────────
 * URL 里的 `e=1792294934` 是**过期时间戳**，实测签发到过期 = 1343 秒。
 * 所以：
 *   ★ 播放地址零缓存。每次点播都重新抓详情页。★
 *   ★ 详情元数据可以缓存，play() 不许读它。★（和 Hanime1Adapter 同一条规矩）
 *
 * 媒体域是 `la.btc620.com`（nginx），裸客户端带签名就 206，**不校验 Referer**
 * （带不带都 206，实测）。所以播放不需要额外 header。
 *
 * ── 坑 6：搜索没有 videopaging 那个 div ──────────────────────────────
 * 分类页有 `<div class="videopaging">Videos 1 - 24 of 15573</div>`，
 * 搜索页**没有**，总数在 `<h4>` 里："(Result Videos 1 - 24 of 121)"。
 * 两处都要能读，否则搜索永远只出一页。
 */
public final class Porn91Adapter {

    private static final int TIMEOUT = 20;
    /**
     * 兜底域名。**正常路径不用它** —— 真正用的地址从 profile.origin 取。
     *
     * 为什么必须这样：这类站的镜像域换得比版本还快（901.26702.app 只是
     * 当前这一个），写死一个域名等于把源绑在一次性地址上。origin 由
     * UserSource 在识别时从用户填的地址里取，适配器只负责往上拼路径。
     */
    private static final String DEFAULT_HOST = "https://901.26702.app";

    /** 首页取多少条。站点每页 24，正好一页。 */
    private static final int HOME_LIMIT = 24;
    /** 精选推荐取前几条。首页轮播一行放 4~8 张就够。 */
    private static final int FEATURED_LIMIT = 8;

    /**
     * 详情元数据缓存。**只给 detail() 用，play() 不许读它** —— 签名会过期。
     * 存解析好的 JSON，不存 HTML。
     */
    private static final Map<String, JSONObject> META_CACHE =
            java.util.Collections.synchronizedMap(new HashMap<String, JSONObject>());
    /**
     * 缓存条数。**必须比一页卡片多** —— 列表校正会把整页的详情都拉一遍（24 条），
     * 旧的 16 条会让最早的那批在下一次校正时又被重拉，等于每次进首页都全量请求。
     */
    private static final int CACHE_LIMIT = 64;

    /**
     * 站点的游客标记（cookie 名就叫 `ga`）。**没有它，详情页每次渲染换一部片。**
     *
     * ── 2026-10-18 查出来的真身 ──────────────────────────────────────
     * 症状是「点进去，片子跟标题封面对不上」，而且不是偶发 —— 几乎每条都不对。
     *
     * 实测同一个 `viewkey=1816005455` 连请求五次，什么都不带：
     *
     *     #1 mp4=855282  #2 mp4=878350  #3 mp4=864307
     *     #4 mp4=874177  #5 mp4=876121
     *
     * 五次五个文件，标题十次都是「新疆黑丝混血校花被体育生颜射」。把两个封面
     * 下下来看，一个是金发白人在车里，一个是黑发在酒店 —— 根本不是同一部片。
     *
     * 带上 `Cookie: ga=<任意值>`（我用 `ga=1` 验过）之后：
     *
     *     1816005455 → 1252139    208573139 → 1252176
     *     911802319  → 1252166    9e50cbb7…  → 1252103
     *
     * 这四个数**正好是首页列表那四张卡片的封面缩略图**（`playvthumb_<id>`），
     * 标题、封面、视频终于是一条片了。
     *
     * 所以机制是：标题按 viewkey 取（永远对），媒体和封面按会话取。站点在
     * 没有游客标记时走「随便给一部」那条渲染路径 —— 大概率是给爬虫/新访客的
     * 试看池。列表页不受影响（实测连拉两次，24 条的 thumb 和 viewkey 一个不差），
     * 所以症状只在点进详情之后出现。
     *
     * ── 为什么不是硬编码一个值 ────────────────────────────────────────
     * 站点在首次响应里会下发真的 `ga`（`Max-Age=100000000`，到 2029 年）。
     * 能拿到就用它的，拿不到（比如站点哪天改成必须验签）再退回 `1` —— 两条路
     * 都试过，当前都通。取值流程见 {@link #get(String)}。
     */
    private static volatile String guest;
    private static final Object GUEST_LOCK = new Object();

    /**
     * 站点没给标记时的兜底值。
     *
     * 实测 `ga=1` 一样管用 —— 站点只判断「这个访客带没带标记」，不校验内容
     * （我拿 `ga=1`、`ga=abc`、真标记三种都比过，同一批 viewkey 出来的都是同一部片）。
     * 留着这条是为了防「哪天 Set-Cookie 被中间设备剥掉」—— 那种情况下至少还能看。
     */
    static final String FALLBACK_GUEST = "1";

    private Porn91Adapter() {}

    // ── 对外入口 ──────────────────────────────────────────────────────

    /**
     * 按内部路径词汇表取数据，返回**我们形状**的 JSON 字节（外面套 {"data":…}）。
     * 由 AiiHttp.get() 在 adapter == "porn91" 时分流过来。
     */
    public static byte[] fetch(String path, SiteProfile profile) throws Exception {
        String base = base(profile);
        JSONObject result;
        if (path.startsWith("/api/reconcile")) {
            result = reconcile(base, param(path, "ids"));
        } else if (path.startsWith("/api/home")) {
            result = home(base);
        } else if (path.startsWith("/api/tags")) {
            result = tags(base);
        } else if (path.startsWith("/api/search")) {
            result = search(base, param(path, "q"), page(path));
        } else if (path.startsWith("/api/videos")) {
            // 三个动作共用 /api/videos 前缀，**判据用参数名而不是路径形状**
            // （Hanime1Adapter 在这上面踩过：按「有没有 /play」分流，分类列表
            //  掉进了 detail，videoId() 解出空串，站点回 403）。
            String cate = param(path, "cate_id");
            if (cate.startsWith("cat:")) cate = cate.substring(4);
            if (cate.length() > 0) {
                result = listing(base, cate, page(path));
            } else if (path.indexOf("/play") > 0) {
                result = play(base, videoId(path), episode(path));
            } else {
                result = detail(base, videoId(path));
            }
        } else {
            throw new Exception("porn91 适配器不认识的路径：" + path);
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
     * 首页。打 `/v.php`（不是 `/`，那个是年龄门）。
     *
     * 分区怎么来：站点首页只有一列「Latest Videos」，没有独立的分区接口。
     * 所以这里做两次请求拼出首页要的两行：
     *
     *   featured = 精选（category=rf）前 8 条
     *   latest   = 最新（/v.php）24 条
     *
     * 两次请求是值得的：只给 latest 的话首页「精选推荐」那一行是空的，
     * 而精选和最新在这个站上重合度很低（rf 13299 条 / 全站 147579 条）。
     */
    private static JSONObject home(String base) throws Exception {
        JSONArray latest = parseCards(get(base + "/v.php"), base);

        JSONArray featured = new JSONArray();
        try {
            JSONArray picks = parseCards(get(base + "/v.php?category=rf&viewtype=basic"), base);
            Set<String> seen = new HashSet<String>();
            for (int i = 0; i < picks.length() && featured.length() < FEATURED_LIMIT; i++) {
                JSONObject v = picks.optJSONObject(i);
                if (v == null) continue;
                if (!seen.add(v.optString("id", ""))) continue;
                featured.put(v);
            }
        } catch (Throwable e) {
            // 精选拉不到不算失败 —— 首页还有 latest 那一行。但日志要留痕，
            // 免得「精选那行一直空着」变成没人查的哑bug。
            log("porn91 精选（category=rf）拉取失败，首页只剩最新：" + e);
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
     * 标签页。**返回空表，不是解析失败。**
     *
     * 这个站没有标签体系 —— 它只有十个固定分类（见 wrapPorn91 里那张表），
     * 详情页上也没有 tag/topic 的 DOM。硬凑一份「把分类当标签」交上去的话，
     * 「全部类型」页会显示十个和底部导航重复的入口，用户点了发现是同一份数据 ——
     * 那比空页面更糟（这个项目在「剧场 / AI 两格点进去同一份数据」上踩过）。
     *
     * 所以这里返回 `{categories: []}`，配置里也不声明 ui.tagGroups。
     * 前端 renderTags 拿到空数组就画不出东西，那是**站点的形状**。
     */
    /**
     * 「全部类型」页。这个站没有话题标签，但**顶部那排分类链接就是它的标签栏**：
     *
     *   91原創 / 當前最熱 / 本月最熱 / 10分鐘以上 / 20分鐘以上 / 本月收藏
     *   最近加精 / 高清版 / 上月最熱 / 本月討論 / 收藏最多
     *
     * 以前这里返回空数组，理由是「没有标签体系」—— 结果是「全部类型」页空白，
     * 首页那排胶囊点下去也没反应（它们走 tag-list，而 tag-list 压根没配）。
     *
     * 现在直接从站点导航里抠：**标签名和 category 码都取站点自己的**，不写死一份
     * 表。多一个少一个分类不用改代码。点了走 tag-list → 分类列表。
     */
    private static JSONObject tags(String base) throws Exception {
        JSONArray items = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        String html = get(base + "/v.php");
        Matcher m = Pattern.compile(
                "<a[^>]+href=[\"'](?:https?://[^\"']*)?v\\.php\\?category=([a-z0-9_]+)[^\"']*[\"'][^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String code = m.group(1);
            String label = unescape(stripHtml(m.group(2))).replaceAll("\\s+", " ").trim();
            if (code.length() == 0 || label.length() == 0) continue;
            if (label.length() > 24) continue;                 // 正文里的链接不算
            if (!seen.add(code)) continue;
            JSONObject tag = new JSONObject();
            tag.put("slug", code);
            tag.put("name", label);
            tag.put("group", "category");                      // 最终组名，前端直接认
            items.put(tag);
        }
        if (items.length() == 0) {
            // 导航改版了也不至于让整页空白：退回写死的那十个，至少能点。
            for (int i = 0; i < Porn91Site.categoryCodes().length; i++) {
                JSONObject tag = new JSONObject();
                tag.put("slug", Porn91Site.categoryCodes()[i][0]);
                tag.put("name", Porn91Site.categoryCodes()[i][1]);
                tag.put("group", "category");
                items.put(tag);
            }
        }
        JSONObject group = new JSONObject();
        group.put("code", "category");
        group.put("tags", items);

        JSONObject out = new JSONObject();
        out.put("categories", new JSONArray().put(group));
        return out;
    }

    /**
     * 分类列表。
     *
     * cateId 就是站点的 category code（ori / hot / top / …），由
     * wrapPorn91() 生成进 categories 表。不认识的 code 会被站点当成
     * 默认列表（回 200 + 全站最新），**不会报错** —— 所以校验放在配置侧：
     * 只放那十个实测过的 code 进去。
     */
    private static JSONObject listing(String base, String cate, int page) throws Exception {
        String url = base + "/v.php?category=" + enc(cate) + "&viewtype=basic&page=" + page;
        String html = get(url);
        JSONArray items = parseCards(html, base);

        JSONObject out = new JSONObject();
        out.put("items", items);
        out.put("pagination", pagination(html, page, items.length()));
        return out;
    }

    /**
     * 搜索。站点用 `search_result.php?search_id=<词>&search_type=search_videos&page=N`。
     *
     * 空关键词**不发请求**：站点对空 search_id 会回首页那份列表，看起来像
     * 「搜索成功了但结果全是热门」，比失败更糟。前端那层 apiPathFor 已经拦了
     * 一次，这里再拦一次是防御 —— 适配器也可能被别处直接调。
     */
    private static JSONObject search(String base, String keyword, int page) throws Exception {
        if (keyword == null || keyword.trim().length() == 0) {
            throw new Exception("搜索词为空，不发请求");
        }
        String url = base + "/search_result.php?search_id=" + enc(keyword.trim())
                + "&search_type=search_videos&page=" + page;
        String html = get(url);
        JSONArray items = parseCards(html, base);

        JSONObject out = new JSONObject();
        out.put("items", items);
        out.put("pagination", pagination(html, page, items.length()));
        return out;
    }

    // ── 列表校正：拿详情页的真值盖掉网格页的假数据 ────────────────────

    /**
     * 一页最多校正多少条。站点一页就是 24，留点余量。
     */
    private static final int RECONCILE_LIMIT = 26;

    /** 并发度。**别调大** —— 见下面那段注释。 */
    private static final int RECONCILE_THREADS = 4;

    /** 整轮的时间预算。到点就用手上已有的，不把首页拖死。 */
    private static final long RECONCILE_BUDGET_MS = 12000;

    private static final java.util.concurrent.ExecutorService RECONCILE_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(RECONCILE_THREADS, r -> {
                Thread t = new Thread(r, "porn91-reconcile");
                t.setDaemon(true);
                return t;
            });

    /**
     * 用详情页的数据校正列表卡片。
     *
     * ── 为什么需要它 ─────────────────────────────────────────────────
     * 2026-10-18 真机 + 浏览器两边都确认过：91porn 的**网格页在乱写**。同一页里
     * 一个 viewkey 能出现 2~4 次，每次配不同的封面和标题；点进去（详情页）才是
     * 真的。实测一页 40 张卡里 12 个 viewkey 重复，`/v.php` 两次渲染有 10/24 条
     * 的封面会变，而我们照着卡片上的 href、封面、标题原样搬过来，于是「点缩略图
     * 是一个，点进去封面换了一个」。
     *
     * 搜索页反而干净（两次渲染 0 变化、3/3 与详情一致），说明站点做得到，
     * 只是首页/分类页故意不给 —— 那是给爬虫的迷魂阵，不是我们解析错了。
     *
     * 既然网格页不可信，就用详情页盖一遍：每张卡发一次详情请求，把标题、封面、
     * 时长换成真值。
     *
     * ── 代价 ─────────────────────────────────────────────────────────
     * 一页 24 条 = 24 次详情请求，约 900KB，4 线程约 1~1.5 秒。第二次进同一页
     * 全部命中 META_CACHE，零请求（这也是 CACHE_LIMIT 必须 ≥ 一页的原因）。
     *
     * **并发度压到 4**：这个站对「非浏览器流量」有静默处理（UA 不对直接回 200 +
     * 0 字节），一口气打 24 个并发请求有被限流的风险。4 条一轮，单条失败就跳过、
     * 保留卡片原值 —— 校正失败最多是回到「站点怎么写我们就怎么显示」，不能让
     * 一个坏条目把整页拖垮。
     */
    static JSONObject reconcile(String base, String idList) throws Exception {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        String[] parts = idList == null ? new String[0] : idList.split(",");
        for (int i = 0; i < parts.length && ids.size() < RECONCILE_LIMIT; i++) {
            String id = parts[i].trim();
            if (id.length() > 0 && !ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) throw new Exception("没有提供 id");

        java.util.List<java.util.concurrent.Future<JSONObject>> jobs =
                new java.util.ArrayList<java.util.concurrent.Future<JSONObject>>();
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.get(i);
            jobs.add(RECONCILE_POOL.submit(() -> {
                try {
                    JSONObject real = detail(base, id);
                    JSONObject one = new JSONObject();
                    one.put("id", id);
                    one.put("title", real.optString("title", ""));
                    one.put("cover", real.optString("cover", ""));
                    one.put("badge", real.optString("duration", ""));
                    return one;
                } catch (Throwable e) {
                    return null;      // 单条失败：前端保留原值
                }
            }));
        }

        JSONArray items = new JSONArray();
        long deadline = System.currentTimeMillis() + RECONCILE_BUDGET_MS;
        for (int i = 0; i < jobs.size(); i++) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                log("porn91 校正超时，剩 " + (jobs.size() - i) + " 条没等");
                break;
            }
            try {
                JSONObject one = jobs.get(i).get(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (one != null && one.optString("cover", "").startsWith("http")) items.put(one);
            } catch (Throwable ignored) {
                // 超时/异常只影响这一条
            }
        }

        JSONObject out = new JSONObject();
        out.put("items", items);
        return out;
    }

    /**
     * 详情。元数据全在 DOM 里，没有 OG meta（这个站没写）。
     *
     * 能拿到的：
     *   标题      <h4 class="login_register_header">…</h4>（第一个，后面还有两个同 class）
     *   时长      <span class="info">Runtime: <span class="video-info-span">00:15:50</span>
     *   播放数    <span class="info"> Views: <span class="video-info-span">26 </span>
     *   收藏数    Favorites
     *   上传日期  <span class="info">Added: </span><span class="title-yakov">2026-10-17</span>
     *   上传者    <a href="…/uprofile.php?UID=…"><span class="title">王良明</span></a>
     *   简介      <div id=v_desc class="more title" >…</div>
     *   封面      <video … poster="https://…/thumb/NNN.jpg">
     *
     * ★ **拿不到 tags**：这个站没有标签体系，只有十个固定分类。
     *   所以 tags 返回空数组，配置里也不声明 tagGroups —— 前端画标签页
     *   会得到空列表，那是站点的形状，不是解析失败。
     *
     * 单集站：一个 viewkey 就是一部完整的片，没有分集。episodes 仍要给一条 ——
     * 前端拿它决定「点播放走哪条路」。
     */
    private static JSONObject detail(String base, String id) throws Exception {
        JSONObject cached = META_CACHE.get(id);
        if (cached != null) return new JSONObject(cached.toString());

        String html = get(base + "/view_video.php?viewkey=" + enc(id));

        String title = firstH4(html);
        if (title.length() == 0) title = stripHtml(text(html, "<title>", "</title>")).trim();
        if (title.length() == 0) {
            throw new Exception("详情页里没找到标题（viewkey=" + id
                    + "）。可能：片子已删除、或站点改版。");
        }

        String duration = infoSpan(html, "Runtime");
        String poster = attr(html, "poster");

        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("title", title);
        out.put("cover", poster);
        out.put("description", description(html));
        out.put("duration", duration);
        // 站点的「点数」不是评分，别硬塞给 score —— 空着比给错的值好。
        out.put("score", "");

        String views = infoSpan(html, "Views");
        String favs = infoSpan(html, "Favorites");
        String added = addedDate(html);
        String uploader = uploader(html);
        JSONObject extra = new JSONObject();
        if (views.length() > 0) extra.put("views", views.trim());
        if (favs.length() > 0) extra.put("favorites", favs.trim());
        if (added.length() > 0) extra.put("added", added);
        if (uploader.length() > 0) extra.put("uploader", uploader);
        out.put("extra", extra);

        // 站点的十个分类没有「这条属于哪个」的标记（详情页不写 category），
        // 所以 category 留空，前端不画那个角标。
        out.put("category", "");
        out.put("tags", new JSONArray());

        JSONArray episodes = new JSONArray();
        JSONObject ep = new JSONObject();
        ep.put("ep_num", 1);
        ep.put("title", title);
        ep.put("duration", duration);
        episodes.put(ep);
        out.put("episodes", episodes);

        // 相关推荐：详情页尾部那一片 view_video.php 链接（跳过自己）
        out.put("related", parseRelated(html, id));

        cache(id, out);
        return new JSONObject(out.toString());
    }

    // ── 播放 ──────────────────────────────────────────────────────────

    /**
     * 播放地址。**每次实时抓详情页，零缓存。**
     *
     * 签名 `e=` 实测 22 分钟就过期，缓存等于「过一会儿点开就是 403」。
     * 也正因如此，这里**不读 detail() 的 META_CACHE**。
     *
     * ep 参数在这个单集站上没有「集」的含义，借来表示**要不要拿 HD**：
     *   站点一个 viewkey 只给一条 source，没有多档。所以 ep 忽略，
     *   但保留参数位，免得将来站点加了画质档还要改路径模板。
     *
     * @return source 是**直链 mp4**，WebView 的 HLS/mp4 播放器直接吃，
     *         不需要解析器或嗅探。
     */
    private static JSONObject play(String base, String id, int ignoredQuality) throws Exception {
        String html = get(base + "/view_video.php?viewkey=" + enc(id));

        String source = playSource(html);
        if (source.length() == 0) {
            throw new Exception("这个页面里没有可播的地址（viewkey=" + id
                    + "）。可能是：片子已下架、签名段被站点改了、或者会员片。");
        }

        JSONObject out = new JSONObject();
        out.put("source", source);
        out.put("quality", 0);

        JSONArray lines = new JSONArray();
        JSONObject line = new JSONObject();
        line.put("quality", 0);
        line.put("label", "原画");
        line.put("url", source);
        lines.put(line);
        out.put("lines", lines);

        out.put("cover", attr(html, "poster"));
        return out;
    }

    /**
     * 从详情页 HTML 里抠播放直链。**顺序不能换**：
     *
     *   ① strencode2 解出来的那份（真的）
     *   ② 裸 <source src=（可能只抠到诱饵）
     *
     * 诱饵长这样，全站固定、被 HTML 注释包着：
     *   <!-- <source src="https://ccm.91p52.com/358999.mp4?st=…" type='video/mp4'> -->
     * 它和真地址的差别只有「在注释里」和「st 参数不同」——按 <source 抠必然中招，
     * 表现是全站所有片子都播同一部。所以 ② 必须**先剥注释**再抠。
     */
    static String playSource(String html) {
        String decoded = strencode(html);
        if (decoded.length() > 0) {
            Matcher m = SOURCE_SRC.matcher(decoded);
            if (m.find()) {
                String url = normalize(m.group(1));
                if (url.length() > 0) return url;
            }
        }
        // 退回：剥掉 HTML 注释再抠裸 <source>
        String stripped = COMMENT.matcher(html).replaceAll("");
        Matcher m = SOURCE_SRC.matcher(stripped);
        while (m.find()) {
            String url = normalize(m.group(1));
            if (url.length() > 0 && !url.contains("358999")) return url;
        }
        return "";
    }

    /**
     * `document.write(strencode2("%3c%73%6f%75%72%63%65…"))` → `<source src='…'>`
     *
     * strencode2 定义在 /js/m2.js（jsjiami.com.v5 混淆过），行为是百分号解码。
     * 这里不复刻那个混淆函数 —— 只做它最终效果的那一步：
     * `%XX` 还原成字符。**不用 URLDecoder.decode()**，那个会把 `+` 解成空格，
     * 而签名里的 `f=` 段实测带 `+` 和 `/`（base64 风格），解错了地址就 403。
     */
    static String strencode(String html) {
        Matcher m = STRENCODE_RE.matcher(html);
        if (!m.find()) return "";
        return percentDecode(m.group(1));
    }

    /** 只解 %XX，不动 `+`。非法转义原样保留，不抛。 */
    /**
     * 百分号解码。**必须按字节收、按 UTF-8 解。**
     *
     * 老实现是逐字节 `(char) byte` 拼字符串 —— 对纯 ASCII 没问题，对中文就是
     * 把一个 UTF-8 字符的三个字节拆成三个 Latin-1 字符（"自" → "è‡ª"）。
     * 这个串再被 enc() 编码回去，就成了另一串字节，站点按那个串搜，结果 0 条。
     * 表现是「搜索永远没结果，但首页、详情、播放全正常」—— 最难查的那种。
     */
    static String percentDecode(String raw) {
        if (raw == null || raw.length() == 0) return "";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            byte[] bytes = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
            i++;
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── HTML 解析 ─────────────────────────────────────────────────────

    /**
     * 从列表页抠卡片。
     *
     * **不按容器 class 找**，而是按 `viewkey=` 出现的位置切块 —— 首页和分类页
     * 是两套栅格（`col-lg-3` / `col-lg-8`），按 class 找会漏掉一半。
     *
     * 切法：以每个 `<a href="…view_video.php?viewkey=…">` 为起点，往后截到
     * 下一个 `<div class="col-xs-12` 为止。这样一条卡片的封面、时长、标题、
     * 播放数全在块里，且不会把邻卡的数据吃进来。
     *
     * 去重靠 viewkey（坑 2：同一条会被画两遍）。
     * 顺序保持页面顺序 —— 站点那 24 条本来就是按时间/热度排的。
     */
    static JSONArray parseCards(String html, String base) {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<String>();

        Matcher m = CARD_RE.matcher(html);
        while (m.find()) {
            String id = m.group(1);
            if (id == null || id.length() == 0) continue;
            if (!seen.add(id)) continue;

            int start = m.start();
            int nextCard = html.indexOf("col-xs-12", m.end());
            int end = nextCard > 0 ? Math.min(nextCard, start + 4000)
                                   : Math.min(html.length(), start + 4000);
            String chunk = html.substring(start, end);

            JSONObject v = new JSONObject();
            try {
                v.put("id", id);

                String title = cardTitle(chunk);
                v.put("title", title);

                String cover = cardCover(chunk);
                v.put("cover", cover);

                // badge 放时长（00:05:11），前端画在封面右下角。
                String dur = textOfClass(chunk, "duration");
                if (dur.length() > 0) v.put("badge", dur.trim());

                String views = cardInfo(chunk, "Views");
                if (views.length() > 0) v.put("score", views + " 次播放");
            } catch (Exception ignored) {}
            out.put(v);
        }
        return out;
    }

    /**
     * 相关推荐。详情页尾部有一整片「Hot videos」卡片，跳过自己取前 12 条。
     * 抠不到就返回空数组 —— 相关推荐缺了不影响播放，不值得抛异常。
     */
    static JSONArray parseRelated(String html, String selfId) {
        JSONArray out = new JSONArray();
        try {
            JSONArray cards = parseCards(html, "");
            for (int i = 0; i < cards.length() && out.length() < 12; i++) {
                JSONObject v = cards.optJSONObject(i);
                if (v == null) continue;
                if (selfId != null && selfId.equals(v.optString("id", ""))) continue;
                out.put(v);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    /**
     * 一次 GET。走 getRaw：朴素客户端、不继承站点 headers 模板、不套 DoH。
     *
     * UA 给一个安卓 Chrome —— 站点给移动 UA 和桌面 UA 的模板不一样（都有内容，
     * 但移动那份的栅格是 `col-xs-12`，切块更整齐）。这里**不用 iPhone UA**：
     * 实测它和安卓的 DOM 一致，但安卓那串是项目里其它适配器在用的，统一。
     */
    private static String get(String url) throws Exception {
        Map<String, String> jar = new LinkedHashMap<String, String>();
        byte[] body = request(url, jar);

        // 第一次请求没带游客标记，站点会顺手在响应头里塞一个 —— 但**这一份正文
        // 是随机片**，不能拿它去解析标题封面，必须带着新标记重取一次。
        // 每个进程只会走到这里一次（拿到之后 guest 就定住了）。
        if (guest == null) {
            String found = pickGuest(jar);
            String chosen = chooseGuest(jar);
            synchronized (GUEST_LOCK) { if (guest == null) guest = chosen; }
            log("porn91 拿到游客标记 ga=" + chosen.substring(0, Math.min(12, chosen.length()))
                    + (found.length() > 0 ? "" : "（站点没下发，用兜底值）") + "，重取这一页");
            // **无论标记是拣来的还是兜底的，这一份正文都要重取** —— 没带标记的
            // 那次渲染出来的是随机片，拿它解析出来的标题封面全是错的。
            jar.clear();
            body = request(url, jar);
        }

        if (body == null || body.length == 0) {
            // ★ 这个站对不认识的 UA 回 **HTTP 200 + 0 字节**，不报错。
            // 不在这里拦的话，上层拿到空串会一路解析成「0 条卡片」，看起来像
            // 「这个站没内容」—— 而真实原因是 UA。所以空响应必须显式抛。
            throw new Exception("站点返回了空响应（HTTP 200 但 0 字节）。"
                    + "这个站会按 User-Agent 分流：非浏览器 UA 直接回空。"
                    + "检查 HEADERS 里的 User-Agent 是否被改掉了。");
        }
        return new String(body, "UTF-8");
    }

    /** 一次 GET，把响应头里的 Set-Cookie 收进 jar。 */
    private static byte[] request(String url, Map<String, String> jar) throws Exception {
        return AiiHttp.getRaw(url, requestHeaders(guest), TIMEOUT, jar);
    }

    /**
     * 真正的请求头：浏览器头 + 游客标记。
     *
     * 没有标记就先不带 `Cookie`（第一次请求正是靠这个让站点下发一个）。
     * 包级可见，单测直接钉「带不带这个头」。
     */
    static Map<String, String> requestHeaders(String guestToken) {
        Map<String, String> headers = new LinkedHashMap<String, String>(HEADERS);
        if (guestToken != null && guestToken.length() > 0) {
            headers.put("Cookie", "ga=" + guestToken);
        }
        return headers;
    }

    /** 从 Set-Cookie 收来的 jar 里取 `ga`。没有返回空串。 */
    static String pickGuest(Map<String, String> jar) {
        if (jar == null) return "";
        String value = jar.get("ga");
        return value == null ? "" : value.trim();
    }

    /** 拣到的用拣到的，拣不到用兜底值。包级可见，单测直接钉。 */
    static String chooseGuest(Map<String, String> jar) {
        String found = pickGuest(jar);
        return found.length() > 0 ? found : FALLBACK_GUEST;
    }

    /**
     * 单测和排障用：清掉游客标记**和**元数据缓存。
     *
     * 标记错着的时候解析出来的那一份详情也是错的（封面/简介属于别的片），
     * 所以两样必须一起清 —— 只清标记，缓存还会把错的那份吐回来。
     */
    static void forgetGuest() {
        synchronized (GUEST_LOCK) { guest = null; }
        synchronized (META_CACHE) { META_CACHE.clear(); }
    }

    /**
     * 请求头。**User-Agent 是必须的，不是装饰。**
     *
     * 2026-10-18 实测（同一台机器、同一条网络）：
     *
     *   UA = okhttp/4.10.0        → HTTP 200, **0 字节**（v.php 和 view_video.php 都是）
     *   UA = 不带                 → 200, 72KB，内容正常
     *   UA = 安卓 Chrome          → 200, 72KB，内容正常
     *
     * 也就是说 OkHttp 那个默认 UA 被挡了，而且挡法是「静默返回空」而不是 403 ——
     * 这个坑最难查，因为所有状态码都是 200。
     *
     * Referer 不需要：媒体域（la.btc620.com）实测带不带都 206。
     * 这里给 Referer 只是让请求看起来更像正常浏览器，不给也不影响。
     */
    private static final java.util.Map<String, String> HEADERS = SiteCfg.browserHeaders();

    /**
     * 第一个 `<h4 class="login_register_header">` 就是片名。
     * 后面还有两个同 class 的（"Video Information" / "Comment On This Video"），
     * 所以只取第一个。
     */
    static String firstH4(String html) {
        Matcher m = Pattern.compile(
                "<h4[^>]*class=[\"'][^\"']*login_register_header[^\"']*[\"'][^>]*>(.*?)</h4>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (!m.find()) return "";
        return unescape(stripHtml(m.group(1))).replaceAll("\\s+", " ").trim();
    }

    /**
     * `<span class="info">Runtime: <span class="video-info-span">00:15:50</span></span>`
     * → 00:15:50。用 label 定位，因为值在嵌套的第二个 span 里，按 class 取会撞车
     * （Views / Comments / Favorites / Point 全是同一个 class）。
     */
    static String infoSpan(String html, String label) {
        Matcher m = Pattern.compile(
                Pattern.quote(label) + ":\\s*<span[^>]*>([^<]{0,40})</span>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? unescape(m.group(1)).trim() : "";
    }

    /** 列表卡里的 `<span class="info">Views:</span> 1951&nbsp;` */
    static String cardInfo(String chunk, String label) {
        Matcher m = Pattern.compile(
                Pattern.quote(label) + ":\\s*</span>\\s*([0-9][0-9,&nbsp;\\s]{0,20})",
                Pattern.CASE_INSENSITIVE).matcher(chunk);
        if (!m.find()) return "";
        return unescape(m.group(1)).replaceAll("[^0-9]", "");
    }

    /** `<span class="info">Added: </span> <span class="title-yakov">2026-10-17</span>` */
    static String addedDate(String html) {
        Matcher m = Pattern.compile(
                "Added:\\s*</span>\\s*<span[^>]*>\\s*([0-9]{4}-[0-9]{2}-[0-9]{2})",
                Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? m.group(1) : "";
    }

    /** `<a href="…/uprofile.php?UID=…"><span class="title">王良明</span></a>` */
    static String uploader(String html) {
        Matcher m = Pattern.compile(
                "uprofile\\.php\\?UID=[^\"']*\"[^>]*>\\s*<span[^>]*class=[\"'][^\"']*title[^\"']*[\"'][^>]*>([^<]{1,60})</span>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? unescape(m.group(1)).trim() : "";
    }

    /** `<div id=v_desc class="more title" > … </div>` */
    static String description(String html) {
        Matcher m = Pattern.compile(
                "<div[^>]*id=[\"']?v_desc[\"']?[^>]*>(.*?)</div>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (!m.find()) return "";
        String s = stripHtml(m.group(1));
        return unescape(s).replaceAll("\\s+", " ").trim();
    }

    /** 卡片标题：`<span class="video-title title-truncate m-t-5">…</span>` */
    static String cardTitle(String chunk) {
        Matcher m = Pattern.compile(
                "<span[^>]*class=[\"'][^\"']*video-title[^\"']*[\"'][^>]*>(.*?)</span>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(chunk);
        if (!m.find()) return "";
        // 标题里可能带 &#39; / &amp; 这类实体，而且实测有双重编码（&amp;#39;）
        return unescape(unescape(stripHtml(m.group(1)))).replaceAll("\\s+", " ").trim();
    }

    /** 卡片封面：卡片里第一个 `<img class="img-responsive" src=…>` */
    static String cardCover(String chunk) {
        Matcher m = Pattern.compile(
                "<img[^>]*class=[\"'][^\"']*img-responsive[^\"']*[\"'][^>]*\\bsrc=[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(chunk);
        if (m.find()) return normalize(m.group(1));
        // 兜底：任意 img 的 src，但跳过站点自己的图标和广告 gif
        Matcher any = Pattern.compile("<img[^>]*\\bsrc=[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(chunk);
        while (any.find()) {
            String url = normalize(any.group(1));
            if (url.length() == 0) continue;
            if (url.contains("/images/")) continue;       // logo、arrowright.gif
            if (url.contains("fans.91selfie.com")) continue;  // 广告位 gif
            if (url.contains("cryptosign") || url.contains("jhgfdsaqpwoeiru")) continue;
            return url;
        }
        return "";
    }

    private static String textOfClass(String chunk, String cls) {
        Matcher m = Pattern.compile(
                "class=[\"'][^\"']*\\b" + Pattern.quote(cls) + "\\b[^\"']*[\"'][^>]*>([^<]{0,60})",
                Pattern.CASE_INSENSITIVE).matcher(chunk);
        return m.find() ? unescape(m.group(1)).trim() : "";
    }

    /** 取第一个匹配属性名的值（如 poster）。 */
    private static String attr(String html, String name) {
        Matcher m = Pattern.compile("<[a-z]+[^>]*\\b" + Pattern.quote(name) + "=[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? normalize(unescape(m.group(1))) : "";
    }

    private static String normalize(String url) {
        if (url == null) return "";
        String s = url.trim();
        if (s.startsWith("//")) s = "https:" + s;
        if (s.startsWith("http://")) s = "https://" + s.substring(7);
        if (!s.startsWith("https://")) return "";
        return s;
    }

    private static String text(String html, String open, String close) {
        int a = html.indexOf(open);
        if (a < 0) return "";
        int b = html.indexOf(close, a + open.length());
        return b < 0 ? "" : html.substring(a + open.length(), b);
    }

    private static String stripHtml(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", " ");
    }

    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("\\u0026", "&").replace("\\/", "/");
    }

    private static void cache(String id, JSONObject meta) {
        synchronized (META_CACHE) {
            if (META_CACHE.size() >= CACHE_LIMIT) {
                java.util.Iterator<String> it = META_CACHE.keySet().iterator();
                int drop = CACHE_LIMIT / 4;
                while (it.hasNext() && drop-- > 0) { it.next(); it.remove(); }
            }
            META_CACHE.put(id, meta);
        }
    }

    // ── 分页 ──────────────────────────────────────────────────────────

    /**
     * 页数从页面那句 "Videos 1 - 24 of 15573" 算。
     *
     * 两个来源都要认（坑 6）：
     *   分类页  <div class="videopaging">Videos 1 - 24 of 15573</div>
     *   搜索页  <h4>… (Result Videos 1 - 24 of 121)</h4>   ← 没有 videopaging
     *
     * 算不出来时**不猜**：pages 给当前页，让前端停在原地 ——
     * 这比给一个假的总页数、让用户翻到空页要好。
     */
    static JSONObject pagination(String html, int page, int got) {
        JSONObject p = new JSONObject();
        try {
            p.put("page", page);
            p.put("size", got);
            int total = totalCount(html);
            if (total > 0 && got > 0) {
                int pages = (int) Math.ceil(total / (double) got);
                p.put("total", total);
                p.put("pages", Math.max(page, pages));
            } else {
                // 拿不到总数：这一页没满就当作到底了
                p.put("pages", got > 0 ? page + 1 : page);
            }
        } catch (Exception ignored) {}
        return p;
    }

    /** 从 "Videos 1 - 24 of 15573" 里取 15573。 */
    static int totalCount(String html) {
        Matcher m = TOTAL_RE.matcher(html);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return 0;
    }

    // ── 正则 ──────────────────────────────────────────────────────────

    /**
     * 卡片链接。**三种形态都得认**：
     *
     *   view_video.php?viewkey=…       常规
     *   view_video_hd.php?viewkey=…    高清分类（category=hd）整页都是这个
     *   viewkey 本身两种写法：纯数字 / 20 位十六进制（坑 3）
     *
     * 2026-10-18 踩到：`高清` 那个 tab 一直「已显示 0 部作品」。原因就是这一页
     * 的卡片全走 `view_video_hd.php`，而我们只认 `view_video.php` —— 一条都匹配
     * 不上。**详情/播放仍然用 view_video.php**（实测它对 hd 的 id 一样给标题、
     * 封面和直链；`view_video_hd.php` 那页反而没有 poster 也没有 source）。
     *
     * 原来那条注释：
     *   1555330457              纯数字
     *   51dc03e8e27a694f2fc7    20 位十六进制
     * 写成 \d+ 会漏掉一半，而且漏得没声音。
     *
     * href 可能是绝对地址（`https://901.26702.app/view_video.php?...`）
     * 也可能是相对（`view_video.php?viewkey=…`，搜索页就是相对）。
     */
    private static final Pattern CARD_RE = Pattern.compile(
            "<a[^>]+href=[\"'](?:https?://[^\"']*)?view_video(?:_hd)?\\.php\\?viewkey=([0-9a-zA-Z]+)",
            Pattern.CASE_INSENSITIVE);

    /** `<source src='…'>` / `<source src="…">`，跨行、属性顺序不固定。 */
    private static final Pattern SOURCE_SRC = Pattern.compile(
            "<source[^>]*\\bsrc=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /** `document.write(strencode2("…"))`，取那一整串百分号编码。 */
    private static final Pattern STRENCODE_RE = Pattern.compile(
            "strencode2\\(\\s*[\"']([^\"']+)[\"']\\s*\\)", Pattern.CASE_INSENSITIVE);

    /** HTML 注释。剥它才能避开那条全站固定的诱饵 source。 */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** `Videos 1 - 24 of 15573` / `(Result Videos 1 - 24 of 121)` */
    private static final Pattern TOTAL_RE = Pattern.compile(
            "Videos\\s+[0-9,\\s]+-\\s*[0-9,\\s]+of\\s+([0-9,]+)", Pattern.CASE_INSENSITIVE);

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

    /**
     * `{episode}` 占位符。单集站没有集号，这里只用来接住模板里的参数，
     * 值不参与逻辑（站点一个 viewkey 只给一条 source）。
     */
    static int episode(String path) {
        String ep = param(path, "ep");
        if (ep.length() == 0) ep = param(path, "episode");
        try { return ep.length() == 0 ? 0 : Integer.parseInt(ep); }
        catch (Exception e) { return 0; }
    }

    /** 只对 `{` `}` 这些字符做百分号编码，别把 viewkey 的十六进制字母动掉。 */
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

    private static void log(String message) {
        AiiHttp.log(message);
    }
}
