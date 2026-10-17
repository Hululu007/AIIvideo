package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Hanime1Adapter 的离线解析回归。
 *
 * 拿真实抓下来的 HTML（fixtures，用 Chrome 的 TLS 指纹抓的）喂给适配器的**真实解析逻辑**，
 * 验证首页 / 分类 / 搜索 / 越界页 / 详情 meta / 标签面板 / 播放直链 / 相关推荐。
 *
 * ── 它不测什么 ──
 * **不发任何网络请求**。所以它证明的是「HTML 解析对不对」，不是「安卓端能不能连上站点」。
 *
 * ── fixture 从哪来 ──
 * 那些 HTML 是被测站点的真实页面，**不进仓库**（成人内容站，见 .gitignore）。
 * 第一次跑之前执行 fixtures/refresh.py 现场抓一份。缺 fixture 时这些用例会被
 * Assume 跳过并标成 skipped，不会红一片 FileNotFound。
 *
 * ── 为什么用反射 ──
 * parseCards / parseSources / meta / parseTags / parseTagPanel / parseRelated / qualityOf
 * 都是 private，而它们正是要测的东西。改成包可见也行，但那是为了让测试好写去动生产
 * 代码 —— 反射调用一次性的代价更小。
 */
public class Hanime1AdapterTest {

    private static final String BASE = "https://hanime1.me/";
    // fixtures 在 app/src/test/resources/ 下，走 classpath 读，不依赖工作目录。
    private static final String FIXTURE_PREFIX = "";

    private static boolean fixturesReady;
    private static Class<?> adapter;

    @BeforeClass
    public static void loadAdapter() throws Exception {
        adapter = Class.forName("com.huangguo.mobile.Hanime1Adapter");
        fixturesReady = Hanime1AdapterTest.class.getClassLoader().getResource("home.html") != null
                && Hanime1AdapterTest.class.getClassLoader().getResource("watch-408706.html") != null;
    }

    // ── 反射调用的小工具 ────────────────────────────────────────

    private static Object call(String method, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method m = adapter.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static JSONArray parseCards(String html) throws Exception {
        return (JSONArray) call("parseCards", new Class<?>[]{String.class, String.class}, html, BASE);
    }

    private static String meta(String html, String attr, String key) throws Exception {
        return (String) call("meta", new Class<?>[]{String.class, String.class, String.class}, html, attr, key);
    }

    private static String fixture(String name) throws Exception {
        InputStream in = Hanime1AdapterTest.class.getClassLoader().getResourceAsStream(FIXTURE_PREFIX + name);
        assumeTrue("缺 fixture: " + name + "（跑 java-test/fixtures/refresh.py 抓一份）", in != null);
        try (InputStream stream = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void requireFixtures() {
        assumeTrue("缺 fixture（先跑 test/fixtures/refresh.py）", fixturesReady);
    }

    // ── ① 首页 ────────────────────────────────────────────────

    @Test
    public void 首页抠得出至少100张卡() throws Exception {
        requireFixtures();
        JSONArray cards = parseCards(fixture("home.html"));
        assertTrue("实际 " + cards.length(), cards.length() >= 100);
    }

    @Test
    public void 首页首条字段齐全() throws Exception {
        requireFixtures();
        JSONObject first = parseCards(fixture("home.html")).optJSONObject(0);
        assertNotNull(first);
        assertTrue("id 为空", first.optString("id").length() > 0);
        assertTrue("title 为空", first.optString("title").length() > 0);
        assertTrue("cover 不在 hembed: " + first.optString("cover"),
                first.optString("cover").contains("vdownload.hembed.com"));
        assertTrue("badge 不是 mm:ss: " + first.optString("badge"),
                first.optString("badge").contains(":"));
        assertTrue("score 不是百分比: " + first.optString("score"),
                first.optString("score").contains("%"));
    }

    @Test
    public void 首页id无重复() throws Exception {
        requireFixtures();
        JSONArray cards = parseCards(fixture("home.html"));
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < cards.length(); i++) {
            assertTrue("重复 id: " + cards.optJSONObject(i).optString("id"),
                    ids.add(cards.optJSONObject(i).optString("id")));
        }
    }

    // ── ② 分类页：广告必须被过滤 ──────────────────────────────

    @Test
    public void 分类页数量正常() throws Exception {
        requireFixtures();
        int n = parseCards(fixture("genre-1.html")).length();
        assertTrue("实际 " + n, n >= 30 && n <= 45);
    }

    @Test
    public void 分类页没有广告卡混入() throws Exception {
        requireFixtures();
        JSONArray cards = parseCards(fixture("genre-1.html"));
        for (int i = 0; i < cards.length(); i++) {
            String id = cards.optJSONObject(i).optString("id");
            assertTrue("混进了非数字 id: " + id, id.matches("\\d+"));
        }
    }

    @Test
    public void 分类页没有广告位图集当封面() throws Exception {
        requireFixtures();
        JSONArray cards = parseCards(fixture("genre-1.html"));
        for (int i = 0; i < cards.length(); i++) {
            String cover = cards.optJSONObject(i).optString("cover");
            assertFalse("混进了广告位图集: " + cover, cover.contains("/image/icon/"));
        }
    }

    // ── ③ 搜索页 ──────────────────────────────────────────────

    @Test
    public void 搜索页抠得出至少30张() throws Exception {
        requireFixtures();
        int n = parseCards(fixture("query-1.html")).length();
        assertTrue("实际 " + n, n >= 30);
    }

    // ── ④ 越界页必须抠成 0 条 ─────────────────────────────────

    @Test
    public void 越界页抠出0条() throws Exception {
        requireFixtures();
        assertEquals("越界页应当判空", 0, parseCards(fixture("empty-page.html")).length());
    }

    // ── ⑤ 详情 meta ───────────────────────────────────────────

    @Test
    public void 详情页meta全部取到() throws Exception {
        requireFixtures();
        String watch = fixture("watch-408706.html");
        String ogTitle = meta(watch, "property", "og:title");
        String ogImage = meta(watch, "property", "og:image");
        String ogDuration = meta(watch, "property", "og:video:duration");
        assertTrue("og:title 为空", ogTitle.length() > 0);
        assertTrue("og:image 不在 hembed: " + ogImage, ogImage.contains("vdownload.hembed.com"));
        assertTrue("og:video:duration 不是数字: " + ogDuration, ogDuration.matches("\\d+"));
    }

    @Test
    public void 标签剥得出来且不含画质词() throws Exception {
        requireFixtures();
        String watch = fixture("watch-408706.html");
        String ogTitle = meta(watch, "property", "og:title");
        String desc = meta(watch, "name", "twitter:description");
        String title = ogTitle.replace(" - Hanime1.me", "");

        JSONArray tags = (JSONArray) call("parseTags",
                new Class<?>[]{String.class, String.class}, desc, title);
        assertTrue("标签数 " + tags.length() + "，应当 ≥5", tags.length() >= 5);
        for (int i = 0; i < tags.length(); i++) {
            assertFalse("画质词混进了标签: " + tags.optString(i),
                    tags.optString(i).matches("\\d{3,4}[pP]"));
        }
    }

    // ── ⑤b 标签面板 ───────────────────────────────────────────

    @Test
    public void 标签面板分区与总数() throws Exception {
        requireFixtures();
        JSONArray panel = (JSONArray) call("parseTagPanel", new Class<?>[]{String.class},
                fixture("tagpanel.html"));

        assertEquals("分区数不对", 7, panel.length());

        int total = 0;
        for (int i = 0; i < panel.length(); i++) {
            JSONArray tags = panel.optJSONObject(i).optJSONArray("tags");
            total += tags == null ? 0 : tags.length();
        }
        assertTrue("标签总数 " + total + "，应当 ≥200", total >= 200);
    }

    @Test
    public void 分区名基本都认得出来() throws Exception {
        requireFixtures();
        JSONArray panel = (JSONArray) call("parseTagPanel", new Class<?>[]{String.class},
                fixture("tagpanel.html"));
        int other = 0;
        for (int i = 0; i < panel.length(); i++) {
            if ("other".equals(panel.optJSONObject(i).optString("code"))) {
                other++;
            }
        }
        assertTrue("落进 other 的分区数 " + other + "，应当 ≤1", other <= 1);
    }

    @Test
    public void 抽查标签归属正确() throws Exception {
        requireFixtures();
        JSONArray panel = (JSONArray) call("parseTagPanel", new Class<?>[]{String.class},
                fixture("tagpanel.html"));
        assertTrue("「巨乳」不在 style", hasTagIn(panel, "style", "巨乳"));
        assertTrue("「NTR」不在 plot", hasTagIn(panel, "plot", "NTR"));
        assertTrue("「中文字幕」不在 hanime-attr", hasTagIn(panel, "hanime-attr", "中文字幕"));
        assertTrue("「校園」不在 setting", hasTagIn(panel, "setting", "校園"));
    }

    @Test
    public void 每个标签都带slug() throws Exception {
        requireFixtures();
        JSONArray panel = (JSONArray) call("parseTagPanel", new Class<?>[]{String.class},
                fixture("tagpanel.html"));
        for (int i = 0; i < panel.length(); i++) {
            JSONArray tags = panel.optJSONObject(i).optJSONArray("tags");
            if (tags == null) {
                continue;
            }
            for (int j = 0; j < tags.length(); j++) {
                String slug = tags.optJSONObject(j).optString("slug");
                assertTrue("标签缺 slug: " + tags.optJSONObject(j).optString("name"),
                        slug != null && slug.length() > 0);
            }
        }
    }

    // ── 相关推荐 ──────────────────────────────────────────────

    @Test
    public void 相关推荐截到12条且不含自己() throws Exception {
        requireFixtures();
        JSONArray related = (JSONArray) call("parseRelated",
                new Class<?>[]{String.class, String.class}, fixture("watch-408706.html"), "408706");
        assertTrue("数量 " + related.length(), related.length() > 0 && related.length() <= 12);
        for (int i = 0; i < related.length(); i++) {
            assertFalse("自己混进了相关推荐",
                    "408706".equals(related.optJSONObject(i).optString("id")));
        }
    }

    // ── ⑥ 播放直链 ────────────────────────────────────────────

    @Test
    public void 抠到三档直链且都带签名() throws Exception {
        requireFixtures();
        JSONArray sources = (JSONArray) call("parseSources", new Class<?>[]{String.class},
                fixture("watch-408706.html"));
        assertEquals("档数不对", 3, sources.length());

        TreeSet<Integer> qualities = new TreeSet<>();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            qualities.add(source.optInt("quality"));
            assertTrue("有裸链（缺 ?secure=）: " + source.optString("url"),
                    source.optString("url").contains("?secure="));
        }
        assertEquals("[480, 720, 1080]", qualities.toString());
    }

    @Test
    public void 从URL认得出画质() throws Exception {
        Integer quality = (Integer) call("qualityOf", new Class<?>[]{String.class},
                "https://vdownload.hembed.com/408706-720p.mp4?secure=abc");
        assertEquals(Integer.valueOf(720), quality);
    }

    // ── 工具 ──────────────────────────────────────────────────

    private static boolean hasTagIn(JSONArray groups, String code, String name) throws Exception {
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null || !code.equals(group.optString("code"))) {
                continue;
            }
            JSONArray tags = group.optJSONArray("tags");
            if (tags == null) {
                return false;
            }
            for (int j = 0; j < tags.length(); j++) {
                if (name.equals(tags.optJSONObject(j).optString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
