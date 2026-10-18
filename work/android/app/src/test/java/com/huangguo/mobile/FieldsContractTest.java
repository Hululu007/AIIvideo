package com.huangguo.mobile;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * 别名表守门 —— 「适配器返回的键名，web.fields 里必须有同名候选」。
 *
 * ── 为什么值得单独一个测试类 ────────────────────────────────────────
 * 这条契约以前只写在注释里（`MissavSite` 写了两遍「漏了就全是 undefined」），
 * 靠人记。2026-10-18 接樱花动漫时还是漏了：
 *
 * <pre>
 *   适配器返回 episodes:[{episodeNumber:1, title:"第01集"}]
 *   默认别名表里 episodeNumber 只有 ['ep_num']
 *   前端 pick(entry,'episodeNumber') → undefined
 *   界面：选集那一排三个格子，每个都写着 undefined
 * </pre>
 *
 * 接口 200、日志干净、数据全在，只有界面是空的 —— 这类 bug 读代码发现不了，
 * 真机上要点到详情页才看得见，而且第一反应一定怀疑「解析错了」。
 *
 * ── 这个测试在验什么 ────────────────────────────────────────────────
 * 用**真实的解析代码**产出响应（`parseCards` / `detailFrom` / `playFrom`，
 * 全部吃离线 fixture），然后照前端 `pick()` 的规则走一遍别名表：
 *
 * <pre>
 *   pick(obj, role) = fields[role] 里第一个非空值
 *   fields 来自 web.fields（配置），缺省回落到 site-default.json
 * </pre>
 *
 * 断言两件事：
 *   1. 前端会读的每个角色，在 fields 里都声明了（不然 pick 返回 undefined）
 *   2. 声明了之后，在真实响应上真的取得到值（不然声明了个不存在的键）
 *
 * 第二条才是关键 —— 只查第一条的话，「候选键写错一个字母」照样过。
 */
public class FieldsContractTest {

    private static String HOME, LIST, DETAIL;
    private static JSONArray PLAYS;

    @BeforeClass
    public static void load() throws Exception {
        HOME = fixture("home.html");
        LIST = fixture("list.html");
        DETAIL = fixture("detail-2026476948.html");
        JSONObject plays = new JSONObject(fixture("plays.json"));
        PLAYS = plays.optJSONArray("video_plays");
    }

    private static String fixture(String name) throws Exception {
        InputStream in = FieldsContractTest.class.getResourceAsStream("/yhdm/" + name);
        assertNotNull("缺 fixture：yhdm/" + name, in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    // ── 前端 pick() 的等价实现 ────────────────────────────────────────
    //
    // 刻意保持极简：别名表是**数据**，取值规则是**常量**（第一个非空）。
    // 规则本身要是复杂到需要复刻，那它就不该叫别名表了。

    /** 角色 → 第一个有值的候选。全空返回 null（= 前端 undefined）。 */
    private static String pick(JSONObject fields, JSONObject item, String role) {
        JSONArray names = fields.optJSONArray(role);
        if (names == null) return null;
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i, "");
            if (name.length() == 0) continue;
            Object value = atPath(item, name);
            if (value == null) continue;
            String text = String.valueOf(value);
            if (text.length() > 0 && !"null".equals(text)) return text;
        }
        return null;
    }

    /** 支持 `a.b` 形式的候选键（详情偶尔把主体裹在壳里）。 */
    private static Object atPath(JSONObject item, String path) {
        if (item == null) return null;
        if (path.indexOf('.') < 0) return item.opt(path);
        String[] parts = path.split("\\.");
        Object node = item;
        for (String part : parts) {
            if (!(node instanceof JSONObject)) return null;
            node = ((JSONObject) node).opt(part);
        }
        return node;
    }

    /** 调试用：列出对象里有哪些键（JSONObject 没有 keySet）。 */
    private static String keysOf(JSONObject o) {
        StringBuilder sb = new StringBuilder("[");
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(it.next());
        }
        return sb.append(']').toString();
    }

    /** 断言：这一组角色在真实响应上都必须能 pick 出值。 */
    private static void assertRoles(JSONObject fields, JSONObject item, List<String> roles,
                                    String where) {
        for (String role : roles) {
            JSONArray names = fields.optJSONArray(role);
            if (names == null || names.length() == 0) {
                fail(where + "：web.fields 里没有声明角色 '" + role
                        + "' —— 前端 pick() 会直接返回 undefined，界面上那一格是空的");
            }
            String value = pick(fields, item, role);
            if (value == null) {
                fail(where + "：角色 '" + role + "' 声明了候选 " + names
                        + "，但在真实响应里一个都取不到值。响应里的键是：" + keysOf(item));
            }
        }
    }

    // ── 用例 ──────────────────────────────────────────────────────────

    /** 列表卡片：首页和列表页两条解析路径都要过。 */
    @Test public void 列表卡片的角色都取得到值() throws Exception {
        JSONObject fields = YhdmSite.buildConfig("https://yhdm.one")
                .getJSONObject("web").getJSONObject("fields");

        // 前端 adaptVideo 真正会读的：id 用来跳详情，title 和 cover 画卡片，
        // badge 是「更至第03集」那个角标。缺 id 就是点了没反应，缺 title 就是白条。
        //
        // 列表页和首页的卡片形状不一样：列表页那几个 <li> 只有标题/封面/分类，
        // **没有更新角标**。所以 badge 只在首页那张卡上断言 —— 在列表页断言
        // 等于要求适配器去编一个站点没给的值。
        List<String> common = Arrays.asList("id", "title", "cover");

        JSONArray home = YhdmAdapter.parseCards(HOME, "https://yhdm.one");
        assertTrue("首页至少应有 40 张卡", home.length() >= 40);
        assertRoles(fields, home.optJSONObject(0), common, "首页卡片");
        assertRoles(fields, home.optJSONObject(0), Arrays.asList("badge"), "首页卡片");

        JSONArray list = YhdmAdapter.parseCards(LIST, "https://yhdm.one");
        assertTrue("列表页至少 15 张卡", list.length() >= 15);
        assertRoles(fields, list.optJSONObject(0), common, "列表页卡片");
    }

    /** 详情：标题、封面、简介，以及**选集每一格**。 */
    @Test public void 详情的角色都取得到值() throws Exception {
        JSONObject config = YhdmSite.buildConfig("https://yhdm.one");
        JSONObject fields = config.getJSONObject("web").getJSONObject("fields");

        JSONObject detail = YhdmAdapter.detailFrom(
                "2026476948", DETAIL, "https://yhdm.one");

        assertRoles(fields, detail, Arrays.asList("id", "title", "cover", "description"),
                "详情");

        // 选集：这一排格子是全 App 最脆的地方 —— 2026-10-18 就是这里全是 undefined。
        JSONArray episodes = detail.optJSONArray("episodes");
        assertNotNull("详情必须有 episodes 数组（单集站也要给一条）", episodes);
        assertTrue("这个剧场版有 3 个剧集锚点", episodes.length() >= 3);
        for (int i = 0; i < episodes.length(); i++) {
            assertRoles(fields, episodes.optJSONObject(i),
                    Arrays.asList("episodeNumber", "title"),
                    "详情第 " + (i + 1) + " 个选集");
        }
    }

    /**
     * 播放响应：`source` 取不到 = 「未取得播放地址」，`episodeNumber` 取不到
     * = 播放器不知道这是第几集（上下滑切集会错位）。
     */
    @Test public void 播放响应的角色都取得到值() throws Exception {
        JSONObject config = YhdmSite.buildConfig("https://yhdm.one");
        JSONObject fields = config.getJSONObject("web").getJSONObject("fields");

        // 探测器传 null = 全部认为可用，不联网
        JSONObject play = YhdmAdapter.playFrom("2026476948", "ep1", PLAYS, null);

        assertRoles(fields, play, Arrays.asList("source", "episodeNumber"), "播放响应");

        // 备选线路也要能取到 url —— 播放器的换线兜底全靠它
        JSONArray lines = play.optJSONArray("lines");
        assertNotNull(lines);
        assertTrue(lines.length() >= 2);
        for (int i = 0; i < lines.length(); i++) {
            String url = lines.optJSONObject(i).optString("url", "");
            assertTrue("第 " + (i + 1) + " 条线路没有 url", url.startsWith("http"));
        }
    }

    /** 标签页：名字取不到就会显示成 slug（一堆看不懂的英文串）。 */
    @Test public void 标签页的角色都取得到值() throws Exception {
        JSONObject config = YhdmSite.buildConfig("https://yhdm.one");
        JSONObject fields = config.getJSONObject("web").getJSONObject("fields");

        // tags() 是纯拼装，不联网也不吃 fixture
        java.lang.reflect.Method m = YhdmAdapter.class.getDeclaredMethod("tags");
        m.setAccessible(true);
        JSONObject tags = (JSONObject) m.invoke(null);

        JSONArray groups = tags.optJSONArray("categories");
        assertNotNull(groups);
        JSONObject first = groups.optJSONObject(0).optJSONArray("tags").optJSONObject(0);
        assertRoles(fields, first, Arrays.asList("slug", "tagName"), "标签页第一个标签");
    }

    /**
     * 反向守门：适配器往响应里塞的键，别名表里至少要有一个角色认它。
     *
     * 这条防的是「适配器新加了一个字段，配置忘了声明」—— 上一类用例查不出来，
     * 因为它只查「已知角色取不取得到」。这里查「未知键有没有人认」。
     */
    @Test public void 响应里的键都被别名表覆盖() throws Exception {
        JSONObject fields = YhdmSite.buildConfig("https://yhdm.one")
                .getJSONObject("web").getJSONObject("fields");

        java.util.Set<String> known = new java.util.HashSet<String>();
        for (java.util.Iterator<String> it = fields.keys(); it.hasNext(); ) {
            JSONArray names = fields.optJSONArray(it.next());
            if (names == null) continue;
            for (int i = 0; i < names.length(); i++) known.add(names.optString(i, ""));
        }

        // 响应结构本身用的键（items / pagination / featured…）不是「条目字段」，
        // 它们由 adapters.js 的形状逻辑读，不走别名表。
        java.util.Set<String> structural = new java.util.HashSet<String>(Arrays.asList(
                "items", "pagination", "page", "pages", "size", "featured", "latest", "cate_rec",
                "categories", "tags", "code", "lines", "extra", "episodes", "related",
                "episodeIds", "episodeCount", "quality", "group", "hasMore"));

        JSONObject detail = YhdmAdapter.detailFrom("2026476948", DETAIL, "https://yhdm.one");
        JSONObject item = detail.getJSONArray("episodes").getJSONObject(0);
        for (java.util.Iterator<String> it = item.keys(); it.hasNext(); ) {
            String key = it.next();
            assertTrue("选集条目里的键 '" + key + "' 在 web.fields 里没有任何角色认它"
                            + " —— 前端读不到，等于白传",
                    structural.contains(key) || known.contains(key));
        }
    }
}
