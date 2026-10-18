package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 加源配置（probe 的产物）整份钉住。
 *
 * ── 为什么值得单独一个测试类 ──────────────────────────────────────────
 * 这份配置是「适配器契约」的另一半，和响应形状一样是硬要求。它错了的表现
 * 从来不是报错，而是**接口 200、界面空**：
 *
 *   · `paths.tag-list` 指错 → 点分类「已显示 0 部作品」
 *   · `fields.tagName` 漏声明 → 「全部类型」每个胶囊写成 `dm96/高清`
 *   · `fields.episodeNumber` 漏声明 → 「无法拉到集数」
 *   · `ui.categoryIds` 少一项 → 那个子分类点了没反应
 *
 * 这些在真机上都要一轮装包 + 点进去才看得出来。{@link MissavSite#buildConfig}
 * 是纯函数（不联网），所以这里直接调它，把形状钉死。
 *
 * 顺带把生成结果写到 `build/missav-config.json` —— 开发期要往手机上推配置时
 * 不用重新走一遍加源（见 docs/2026-10-18-missav-适配器.md「改了配置就要重新加源」）。
 */
public class MissavConfigTest {

    private static final String BASE = "https://missav.live";
    private static String homeHtml;

    @BeforeClass
    public static void loadFixture() throws Exception {
        InputStream in = MissavConfigTest.class.getClassLoader()
                .getResourceAsStream("missav/missav-home-new.html");
        assertNotNull("缺少 fixture missav/missav-home-new.html", in);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        homeHtml = new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static JSONObject config() throws Exception {
        return MissavSite.buildConfig(BASE, homeHtml);
    }

    private static boolean has(JSONArray arr, String value) {
        for (int i = 0; i < arr.length(); i++) {
            if (value.equals(arr.optString(i))) return true;
        }
        return false;
    }

    private static JSONObject web() throws Exception {
        return config().getJSONObject("web");
    }

    /** 顺手把配置落盘，给「推配置到手机」用。 */
    @Test
    public void 生成配置并落盘() throws Exception {
        JSONObject cfg = config();
        File dir = new File("build");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("建不了 build 目录");
        File out = new File(dir, "missav-config.json");
        FileOutputStream fos = new FileOutputStream(out);
        fos.write(cfg.toString().getBytes(StandardCharsets.UTF_8));
        fos.close();

        assertEquals("missav", cfg.getString("adapter"));
        assertEquals(BASE, cfg.getString("origin"));
        assertTrue("没有 web 配置就不是一份能用的源", cfg.has("web"));
    }

    /**
     * 底部导航：5 个 tab + 更多，且「无码影片」真的挂了二级分类。
     *
     * 站点自己的导航就是两级的（无码影片 → 无码流出 / FC2 / HEYZO / 东京热 /
     * 一本道 / Caribbeancom / Gachinco / XXX-AV / 人妻斩 / 顽皮…）。
     * 一个 tab 只给一个总列表等于把站点最好的那层结构扔了。
     */
    @Test
    public void 无码影片挂着十二个二级分类() throws Exception {
        JSONObject ui = web().getJSONObject("ui");
        JSONArray tabs = ui.getJSONArray("tabs");
        assertEquals("5 个 tab，没有「更多」（索引页不进导航）", 5, tabs.length());

        JSONObject ids = ui.getJSONObject("categoryIds");
        JSONObject labels = ui.getJSONObject("categoryLabels");
        JSONArray unc = ids.getJSONArray("uncensored");
        JSONArray uncLabels = labels.getJSONArray("uncensored");
        assertEquals("无码影片下面 12 个站", 12, unc.length());
        assertEquals("ids 和 labels 必须等长（前端按下标同时取）",
                unc.length(), uncLabels.length());
        assertEquals("dm817/uncensored-leak", unc.getString(0));
        assertEquals("无码流出", uncLabels.getString(0));
        assertEquals("东京热排在第四", "dm42/tokyohot", unc.getString(3));
        assertEquals("人妻斩在末尾倒数第三", "dm37/marriedslash", unc.getString(9));

        JSONArray amateur = ids.getJSONArray("amateur");
        assertEquals("素人 5 个站", 5, amateur.length());
        assertEquals("dm3600557/pacopacomama", amateur.getString(0));
    }

    /**
     * {@code web.categories} 必须覆盖每一个 slug。
     *
     * 前端 {@code cateIdOf(slug) = SITE.categories[slug]}，而
     * {@code apiPathFor} 里是 {@code if (!cateId) return null} —— 查不到就
     * **静默不请求**。症状是「点了那个子分类，页面还是上一页」。
     */
    @Test
    public void 每个子分类slug都在categories表里() throws Exception {
        JSONObject w = web();
        JSONObject categories = w.getJSONObject("categories");
        JSONObject ids = w.getJSONObject("ui").getJSONObject("categoryIds");
        for (java.util.Iterator<String> it = ids.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONArray subs = ids.getJSONArray(key);
            for (int i = 0; i < subs.length(); i++) {
                String slug = subs.getString(i);
                assertTrue("categories 里缺 " + slug + "（tab " + key + "）",
                        categories.has(slug));
                assertEquals("自映射，值必须就是 slug", slug, categories.getString(slug));
            }
        }
    }

    /**
     * paths：tag-list 必须是分类列表，不是分类索引。
     *
     * 第一版写成 {@code /api/genres?page={page}}（「类型页也分页」），前端把
     * 分类索引的分组信封当作品列表，点任何分类都是空。
     */
    @Test
    public void 路径模板() throws Exception {
        JSONObject paths = web().getJSONObject("paths");
        assertEquals("/api/home", paths.getString("home"));
        assertEquals("/api/detail/{id}", paths.getString("detail"));
        assertEquals("/api/play/{id}", paths.getString("episode"));
        assertEquals("/api/genres", paths.getString("tags"));
        assertEquals("点分类看作品，必须落到 category",
                "/api/category?slug={slug}&page={page}", paths.getString("tag-list"));
    }

    /**
     * fields 别名表：适配器返回什么键名，这里就必须有同名候选。
     *
     * 漏掉的表现全是「数据都在，界面空」—— 而且一个都不报错。
     */
    @Test
    public void 别名表覆盖适配器返回的键() throws Exception {
        JSONObject fields = web().getJSONObject("fields");
        assertTrue("episodeNumber 不在候选里 → 无法拉到集数",
                has(fields.getJSONArray("episodeNumber"), "episodeNumber"));
        assertTrue("source 不在候选里 → 未取得播放地址",
                has(fields.getJSONArray("source"), "source"));
        assertTrue("tagName 不在候选里 → 类型胶囊显示成 dm96/高清",
                has(fields.getJSONArray("tagName"), "tagName"));
        assertEquals("slug", fields.getJSONArray("slug").getString(0));
    }

    /** 封面域白名单必须从首页里现抓（CDN 换域名是常事），且不能只留站点自己。 */
    @Test
    public void 封面白名单() throws Exception {
        JSONObject cover = config().getJSONObject("cover");
        assertEquals("plain", cover.getString("mode"));
        JSONArray hosts = cover.getJSONArray("allowedHosts");
        assertTrue("白名单至少要有站点和 CDN 两家", hosts.length() >= 2);
        boolean hasCdn = false;
        for (int i = 0; i < hosts.length(); i++) {
            if (hosts.getString(i).contains("fourhoi")) hasCdn = true;
        }
        assertTrue("封面在 fourhoi.com，漏了就是首页全灰底", hasCdn);
    }
}
