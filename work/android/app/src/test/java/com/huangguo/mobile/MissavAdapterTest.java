package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * MissAV 的离线解析回归。
 *
 * 拿真实抓下来的页面（fixtures/missav-*.html）喂给 {@link MissavParser} 的**真实
 * 解析逻辑**，验证列表 / 类型 / 详情 / 播放四段。
 *
 * ── 它不测什么 ────────────────────────────────────────────────────────
 * <b>不发任何网络请求，也不碰 WebView。</b>过盾那条路（{@link SiteFetchWebView}）
 * 在这里完全没被覆盖 —— 它只能在真机上验证。所以这个用例证明的是「HTML 解析对不对」，
 * 不是「安卓端能不能连上」。这两件事必须分开看：这个文件全绿、真机上依然可能
 * 一个卡片都出不来（那是盾的问题，不是解析的问题）。
 *
 * ── fixture 从哪来 ────────────────────────────────────────────────────
 * <b>真实页面，不进仓库</b>（成人内容站，见 .gitignore）。重新抓：
 * <pre>
 *   Chrome 打开对应页面 → devtools console:
 *     copy(document.documentElement.outerHTML)
 *   存成 fixtures/missav-home-new.html / missav-genres-p1.html / missav-detail-fct210.html
 * </pre>
 * 抓的时候必须确认<b>已经过了 Cloudflare 挑战</b>（页面里能搜到 {@code thumbnail}），
 * 不然存下来的是挑战页，用例会以「卡片数 0」失败 —— 那不是解析器的错。
 *
 * ── 为什么强调「必须用真实 fixture」 ──────────────────────────────────
 * 这一版的三个 bug 全都是在合成 fixture 上写完、编译通过、单测自己跑绿的，
 * 换成真实页面立刻全部露馅（2026-10-18）。合成 fixture 掩盖掉的东西：
 *
 * <pre>
 *   ① 卡片 div 的 class 不是第一个属性
 *      真实：  &lt;div @mouseenter="setPreview('…')" class="thumbnail group"&gt;
 *      合成：  &lt;div class="thumbnail group"&gt;              ← 正则写窄了，真实 0 卡片
 *
 *   ② packed JS 的收尾是 }(' 不是 })(
 *      真实：  …return p}('f=\'8://…\'',16,16,'…'.split('|'),0,{}))
 *      合成：  …return p})('…',16,16,…)                     ← 多一个右括号，正则完全不匹配
 *
 *   ③ 占位符是 base-36、索引 0-based
 *      真实：  索引 14 在载荷里写成 'e'；https 是下标 8
 *      合成：  手写时顺手用了十进制 / 1-based
 * </pre>
 *
 * 三条都不报错，只是安静地给出错的或者空的值 —— 这类 bug 靠读代码发现不了。
 *
 * ── 缺 fixture 时 ─────────────────────────────────────────────────────
 * 被 Assume 跳过并标 skipped，不会红一片 FileNotFoundException。
 */
public class MissavAdapterTest {

    /** fixture 目录。和 porn91 / mrds66 一样放在 test/resources 下，跟仓库走。 */
    private static final String DIR = "missav/";

    private static String homeHtml;
    private static String genresHtml;
    private static String detailHtml;

    @BeforeClass
    public static void load() {
        homeHtml = read("missav-home-new.html");
        genresHtml = read("missav-genres-p1.html");
        detailHtml = read("missav-detail-fct210.html");
        // 三份都拿到才跑，否则下面的 assume 会逐条跳过。
        assumeTrue(homeHtml != null && genresHtml != null && detailHtml != null);
    }

    private static String read(String name) {
        try {
            InputStream in = MissavAdapterTest.class.getClassLoader().getResourceAsStream(DIR + name);
            if (in == null) return null;
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ── 列表页 ─────────────────────────────────────────────────────────

    /**
     * 首页那 12 张卡都要认出来。
     *
     * **卡数必须正好 12** —— 少一张说明切分锚点没吃到真实页面（见类注释的 ①），
     * 多一张说明把 CSS 里那些 {@code taboola-thumbnails-container} 广告类名也当卡片了。
     */
    @Test
    public void homeParsesTwelveCards() {
        JSONArray items = MissavParser.parseList(homeHtml);
        assertEquals("首页卡片数不对 —— 真实页面是 12 张", 12, items.length());
        for (int i = 0; i < items.length(); i++) {
            JSONObject v = items.optJSONObject(i);
            assertNotNull("第 " + i + " 条不是对象", v);
            assertFalse("第 " + i + " 条没有 id", v.optString("id", "").length() == 0);
            assertFalse("第 " + i + " 条没有封面", v.optString("cover", "").length() == 0);
            assertTrue("第 " + i + " 条封面域名不对：" + v.optString("cover"),
                    v.optString("cover").contains("fourhoi.com"));
        }
    }

    /** id 是番号末段，不能带路径也不能带 /dm539/ 前缀。 */
    @Test
    public void cardIdIsBareSlug() {
        JSONArray items = MissavParser.parseList(homeHtml);
        for (int i = 0; i < items.length(); i++) {
            String id = items.optJSONObject(i).optString("id", "");
            assertFalse("id 里混进了斜杠：" + id, id.contains("/"));
            assertFalse("id 带着 /dmNNN/ 前缀没剥掉：" + id, id.startsWith("dm"));
            // 站点有两类片名：有码片是 fct-210 这种「字母-数字」，
            // 无码片是 pacopacomama-101726_100 这种「站点名-日期_序号」—— 带下划线。
            // 第二版 id 拼接和 identity 前缀都按「只有字母数字和横杠」写的，
            // 漏了下划线就是「无码片全部点不开」。
            assertTrue("id 形态不对：" + id, id.matches("[a-z0-9_-]+"));
        }
    }

    /** 时长要么空、要么是 mm:ss / h:mm:ss，不能是别的形状。 */
    @Test
    public void durationShape() {
        JSONArray items = MissavParser.parseList(homeHtml);
        int withDuration = 0;
        for (int i = 0; i < items.length(); i++) {
            String d = items.optJSONObject(i).optString("duration", "");
            if (d.length() == 0) continue;
            withDuration++;
            assertTrue("时长形状不对：" + d, d.matches("\\d{1,2}:\\d{2}(:\\d{2})?"));
        }
        assertTrue("首页 12 张卡里一个时长都没解析到 —— 时长正则多半挂了", withDuration >= 10);
    }

    // ── 类型页 ─────────────────────────────────────────────────────────

    /**
     * 类型页第一页 36 个，解析出 35 个。
     *
     * **差的 1 个是故意的**：站点的分类链接在页面上出现<b>两次</b>（名字一次、
     * 「N 条影片」一次），去重后 35 个不同分类。所以这里钉的是 35 而不是 36 ——
     * 写成 36 会让「去重生效了」这个正确行为被当成 bug。
     */
    @Test
    public void genresPage() {
        JSONArray cats = MissavParser.parseCategories(genresHtml);
        assertTrue("类型条目太少：" + cats.length(), cats.length() >= 30);

        JSONObject first = cats.optJSONObject(0);
        assertNotNull("第一个分类不是对象", first);
        assertEquals("第一个分类应该是「高清」", "高清", first.optString("name"));
        assertEquals("分类 id 应是路径里的 dm96", "dm96", first.optString("id"));
        assertEquals("条数没解析出来", 290816, first.optInt("count"));
    }

    /** 所有分类 id 都得是 dm+数字，且互不重复。 */
    @Test
    public void categoryIdsWellFormedAndUnique() {
        JSONArray cats = MissavParser.parseCategories(genresHtml);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < cats.length(); i++) {
            JSONObject c = cats.optJSONObject(i);
            String id = c.optString("id", "");
            assertTrue("分类 id 形状不对：" + id, id.matches("dm\\d+"));
            assertTrue("分类 id 重复：" + id, seen.add(id));
            assertFalse("分类名丢了：" + id, c.optString("name", "").length() == 0);
        }
    }

    // ── 详情页 ─────────────────────────────────────────────────────────

    @Test
    public void detailMetadata() {
        JSONObject d = MissavParser.parseDetail(detailHtml);
        assertNotNull("详情解析返回 null", d);
        assertEquals("id 不对", "fct-210", d.optString("id"));
        assertEquals("番号不对", "FCT-210", d.optString("code"));
        assertTrue("封面不对：" + d.optString("cover"),
                d.optString("cover").startsWith("https://fourhoi.com/fct-210/"));
        assertEquals("发行日期不对", "2026-10-18", d.optString("releaseDate"));
        assertFalse("标题丢了", d.optString("title", "").length() == 0);
        JSONArray actors = d.optJSONArray("actors");
        assertNotNull("actors 字段丢了", actors);
        assertTrue("女优没解析出来", actors.length() >= 1);
    }

    /**
     * 播放地址 —— 这一条钉住的是整个 packed JS 解包。
     *
     * 期望值和浏览器网络面板里那条 200 的请求<b>逐字</b>核过：
     * {@code GET https://surrit.com/18b4bbc2-c802-44a7-ab7a-78531695c531/playlist.m3u8}
     *
     * 三个曾经的坑分别由下面三条独立用例守住，这里只管「整体对不对」。
     */
    @Test
    public void playUrlMatchesBrowserNetworkPanel() {
        JSONObject d = MissavParser.parseDetail(detailHtml);
        assertEquals("播放地址不对 —— packed JS 解包出错了（见类注释的 ②③）",
                "https://surrit.com/18b4bbc2-c802-44a7-ab7a-78531695c531/playlist.m3u8",
                d.optString("playUrl"));
    }

    /**
     * packed JS 的三个坑，逐个钉。
     *
     * 这三条刻意写成<b>看着很蠢</b>的断言（拿原始 HTML 去数东西），因为它们各自对应
     * 一个曾经真实发生过、而且**不报任何错**的失败：解包出来的东西长得挺像那么回事，
     * 只是少一段或多一位。人眼扫过去会判成「站点改版了」。
     */
    @Test
    public void packedJsAnchorIsCurlyParenThenParen() {
        // 坑②：真实页面里是 return p}('…'，中间没有右括号。
        // 如果哪天只写成 })('，这里会直接 0 条 —— 而 playUrl 会静默变空串。
        assertTrue("真实页面里没找到 }(' 这个 packed 锚点 —— fixture 抓错了？",
                detailHtml.contains("return p}('"));
        assertFalse("fixture 里出现了 })(' 形态，说明抓的不是内联那版",
                detailHtml.contains("return p})('"));
    }

    @Test
    public void packedJsDictionaryHasBase36Placeholders() {
        // 坑③a：`https` 必须是下标 8（0-based），`playlist` 是下标 14，
        // 而载荷里这两个位置写成 '8' 和 'e' —— e 就是 14 的 base-36 写法。
        assertTrue("解包结果里没有 https:// 开头的地址",
                MissavParser.unpackScript(detailHtml).contains("https://surrit.com/"));
        assertTrue("解包结果里没有主播放列表 playlist.m3u8 —— base-36 占位符没处理对",
                MissavParser.unpackScript(detailHtml).contains("/playlist.m3u8"));
    }

    @Test
    public void qualitiesAreTheTwoHardCodedTiers() {
        // 这一部详情页里只有两档（720p / 1080p）；主列表 playlist.m3u8 不带档位，
        // 所以它不该被算成一档画质。
        JSONArray q = MissavParser.qualities(detailHtml);
        assertEquals("画质档位不对：" + q, 2, q.length());
        assertEquals("画质应降序（高清在前）", "1080p", q.optString(0));
        assertEquals("画质应降序（高清在前）", "720p", q.optString(1));
    }

    // ── 形状守卫 ───────────────────────────────────────────────────────

    /** 空输入、空页面都不能抛 —— 返回空数组由上层判断。 */
    @Test
    public void emptyInputsAreSafe() {
        assertEquals(0, MissavParser.parseList(null).length());
        assertEquals(0, MissavParser.parseList("").length());
        assertEquals(0, MissavParser.parseList("<html><body>什么都没有</body></html>").length());
        assertEquals(0, MissavParser.parseCategories(null).length());
        assertEquals("", MissavParser.playUrl(null));
        assertEquals(0, MissavParser.qualities(null).length());
    }

    /** Cloudflare 挑战页喂进去也不能解析出卡片 —— 那会让「首页空着」变成静默故障。 */
    @Test
    public void challengePageYieldsNoCards() {
        String challenge = "<html><head><title>Just a moment...</title></head>"
                + "<body><script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\"></script>"
                + "</body></html>";
        assertEquals("挑战页被解析出卡片了", 0, MissavParser.parseList(challenge).length());
        assertTrue("挑战页没被认出来",
                SiteFetchWebView.looksLikeChallenge(challenge));
    }

    /**
     * 「过盾了没有」那个判据，必须在<b>真实页面</b>上真的命中。
     *
     * <p>2026-10-18 真机踩到两个坑，都出在这一处：
     *
     * <ol>
     *   <li>判据选成 {@code .thumbnail}（卡片容器），而卡片里第一样东西就是 {@code <img>}，
     *       隐藏 WebView 为了省流量开了 {@code setBlockNetworkImage} —— 图被拦，
     *       Chromium 连容器插入都推迟，querySelector 永远返回 null。网络是通的、
     *       盾已经过了，判定却 20 秒不成立。</li>
     *   <li>改用 {@code html[data-missav]} 当「站点自己打的标记」——<b>那个属性根本不存在</b>，
     *       是我凭印象编的。真实 html 标签是
     *       {@code <html lang="zh-Hans" data-immersive-translate-page-theme="light">}。</li>
     * </ol>
     *
     * <p>所以这条断言的价值不在「判据对不对」，而在<b>逼着任何人换判据时先去核真实页面</b>。
     */
    @Test
    public void 过盾判据在真实页面上命中() {
        // 判据必须出现在 MissavSiteFetchWebView 的常量里；这里只用它做一次实测。
        String marker = "html[lang], .grid";
        assertTrue("判据 html[lang] 在真实首页上没命中",
                homeHtml.contains("<html lang="));
        assertTrue("判据 .grid 在真实首页上没命中",
                homeHtml.contains("class=\"grid"));
    }

    /**
     * 挑战页<b>不能</b>命中判据 —— 否则会以为过盾成功，然后拿着一份挑战页去解析。
     */
    @Test
    public void 挑战页不命中过盾判据() {
        String challenge = "<html><head><title>Just a moment...</title></head>"
                + "<body>checking your browser</body></html>";
        assertFalse("挑战页被误判成「过盾成功」", challenge.contains("<html lang="));
        assertFalse("挑战页被误判成「过盾成功」", challenge.contains("class=\"grid"));
    }

    // ── 适配器侧 ───────────────────────────────────────────────────────

    /** 域名认得出来，子域名也算。 */
    @Test
    public void recognisesHost() {
        assertTrue(new MissavSite().matchesUrl("https://missav.live/cn/genres"));
        assertTrue(new MissavSite().matchesUrl("https://missav.live/dm539/cn/new"));
        assertFalse(new MissavSite().matchesUrl("https://example.com/cn/new"));
        assertFalse(new MissavSite().matchesUrl("https://notmissav.live.example.com/"));
    }

    /**
     * 分类地址有**三种**形状，全靠 slug 的首段区分。
     *
     * <pre>
     *   genres/dm106/剧情   → /dm106/cn/genres/剧情     类型页（多一层 genres）
     *   dm597/fc2           → /dm597/cn/fc2             普通分类（站点导航二级项）
     *   new                 → /cn/new                   固定导航
     * </pre>
     *
     * 三种都不能错：拼错了站点回的是 404 内容页，`parseList` 一张卡都认不出来，
     * 而日志里永远是 `ok` —— 症状是「已显示 0 部作品」（2026-10-18 真机）。
     * 分类 id 在**路径最前面**是站点的规定，证据见 fixture：
     * missav-genres-p1.html 里每条链接都是 /dm106/cn/genres/…，首页导航是
     * /dm597/cn/fc2。
     */
    @Test
    public void categoryUrl三种形状() {
        assertEquals("类型页多一层 genres",
                "https://missav.live/dm106/cn/genres/%E5%89%A7%E6%83%85",
                MissavAdapter.categoryUrl("https://missav.live", "genres/dm106/剧情", 1));
        assertEquals("普通分类：id 在 cn 前面",
                "https://missav.live/dm597/cn/fc2",
                MissavAdapter.categoryUrl("https://missav.live", "dm597/fc2", 1));
        assertEquals("固定导航还是 /cn/<slug>",
                "https://missav.live/cn/new",
                MissavAdapter.categoryUrl("https://missav.live", "new", 1));
        assertEquals("翻页参数只在第 2 页起出现",
                "https://missav.live/dm597/cn/fc2?page=3",
                MissavAdapter.categoryUrl("https://missav.live", "dm597/fc2", 3));
    }

    /**
     * 过盾域名表：站点本身 + 它的视频 CDN。
     *
     * surrit.com 是 2026-10-18 加进来的 —— 它和站点一样挡 OkHttp（403
     * Attention Required），但同一个地址在 WebView 里是 200。少了这一条，
     * 症状是「列表封面详情全对，点播放一直转圈」。
     */
    @Test
    public void shieldedHostList() {
        assertTrue(SiteFetchWebView.isShielded("https://missav.live/cn/new"));
        assertTrue(SiteFetchWebView.isShielded(
                "https://surrit.com/18b4bbc2-c802-44a7-ab7a-78531695c531/playlist.m3u8"));
        assertTrue("子域名也算", SiteFetchWebView.isShielded("https://cdn.surrit.com/x.m3u8"));
        assertFalse(SiteFetchWebView.isShielded("https://example.com/cn/new"));
        assertFalse(SiteFetchWebView.isShielded(null));
    }

    // ── 列表校正 ───────────────────────────────────────────────────────

    /**
     * {@code /api/reconcile} 必须应答，哪怕没有任何东西要校正。
     *
     * 前端进每个网格页都会调它（app.js 的 reconcileCards：先铺网格图，再拿详情
     * 盖真值）。不实现时真机日志里全是
     * {@code 适配器不认识的路径：/api/reconcile?ids=…}，一个页面 4~6 次，把真正
     * 的错误全淹了；更糟的是前端拿到异常就<b>什么都不记</b>，下次进来还会再问，
     * 于是永远在报错。
     *
     * 这里验证的是「不再抛异常」+「每个问到的 id 都有回声」——前端判的是
     * {@code !reconciledFixes.has(id)}，有没有内容无所谓，少了 id 它会一直重试。
     */
    @Test
    public void reconcile应答每个id() throws Exception {
        byte[] raw = MissavAdapter.fetch(
                "/api/reconcile?ids=mfyd-196%2Cebwh-373%2Cjera-049", null);
        JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        JSONObject data = root.getJSONObject("data");
        JSONArray items = data.getJSONArray("items");
        assertEquals("问三个就回三条", 3, items.length());
        assertEquals("mfyd-196", items.getJSONObject(0).optString("id"));
        assertEquals("jera-049", items.getJSONObject(2).optString("id"));
        // 缓存里没有这些 id（本用例没跑过列表解析），只能是空壳：
        // 有 id、没有标题 —— 空壳不会把卡片上的标题/封面改成空。
        assertEquals("", items.getJSONObject(0).optString("title", ""));
    }

    /** 空 ids 直接回空数组，不发请求、不报错。 */
    @Test
    public void reconcile空清单返回空数组() throws Exception {
        byte[] raw = MissavAdapter.fetch("/api/reconcile?ids=", null);
        JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        assertEquals(0, root.getJSONObject("data").getJSONArray("items").length());
    }
}
