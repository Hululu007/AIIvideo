package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * UserSource.validate() 的单元测试。
 *
 * validate() 是纯逻辑（不碰 Context / Uri / Looper），所以能在 JVM 上直接跑。
 * 它守的是「用户填的源配置能不能用」这条线 —— 报错文案直接显示给用户，
 * 所以这里不只断言通过/拒绝，也断言拒绝的理由，免得改校验时把提示改成看不懂的话。
 *
 * 内置模板那一条是刻意的：「用户自带源」改完之后 site-default.json 是一份**模板**，
 * origin 被清空了（装完不自带任何站）。所以它按用户配置规则**应该被拒**。
 * 这条测试守着那个产品决定 —— 谁往模板里塞回 origin，这里会红。
 */
public class UserSourceTest {

    /** 一份最小可用的 REST 配置骨架。各用例在它基础上增删字段。 */
    private static JSONObject rest(String origin) throws Exception {
        JSONObject config = new JSONObject();
        if (origin != null) {
            config.put("origin", origin);
        }
        JSONObject web = new JSONObject();
        JSONObject paths = new JSONObject();
        paths.put("home", "/api/home");
        web.put("paths", paths);
        JSONObject categories = new JSONObject();
        categories.put("x", 1);
        web.put("categories", categories);
        config.put("web", web);
        return config;
    }

    private static JSONObject rest() throws Exception {
        return rest("https://a.com");
    }

    // ── 应被拒绝的 ──────────────────────────────────────────

    @Test
    public void origin缺失被拒() throws Exception {
        assertNotNull(UserSource.validate(rest(null)));
    }

    @Test
    public void origin是http被拒() throws Exception {
        assertNotNull(UserSource.validate(rest("http://a.com")));
    }

    @Test
    public void origin带路径被拒() throws Exception {
        assertNotNull(UserSource.validate(rest("https://a.com/sub")));
    }

    @Test
    public void origin带查询参数被拒() throws Exception {
        assertNotNull(UserSource.validate(rest("https://a.com?x=1")));
    }

    @Test
    public void 缺web被拒() throws Exception {
        JSONObject config = new JSONObject();
        config.put("origin", "https://a.com");
        assertNotNull(UserSource.validate(config));
    }

    @Test
    public void paths为空被拒() throws Exception {
        JSONObject config = rest();
        config.getJSONObject("web").put("paths", new JSONObject());
        assertNotNull(UserSource.validate(config));
    }

    @Test
    public void 缺paths_home被拒() throws Exception {
        JSONObject config = rest();
        JSONObject paths = new JSONObject();
        paths.put("detail", "/d");
        config.getJSONObject("web").put("paths", paths);
        assertNotNull(UserSource.validate(config));
    }

    @Test
    public void categories为空被拒() throws Exception {
        JSONObject config = rest();
        config.getJSONObject("web").put("categories", new JSONObject());
        assertNotNull(UserSource.validate(config));
    }

    @Test
    public void cate_id为0被拒() throws Exception {
        JSONObject config = rest();
        config.getJSONObject("web").getJSONObject("categories").put("x", 0);
        assertNotNull(UserSource.validate(config));
    }

    @Test
    public void fields别名列表为空被拒() throws Exception {
        JSONObject config = rest();
        JSONObject fields = new JSONObject();
        fields.put("title", new org.json.JSONArray());
        config.getJSONObject("web").put("fields", fields);
        assertNotNull(UserSource.validate(config));
    }

    @Test
    public void cover缺key被拒() throws Exception {
        JSONObject config = rest();
        JSONObject cover = new JSONObject();
        cover.put("mode", "aes-cbc");
        cover.put("iv", "x");
        config.put("cover", cover);
        assertNotNull(UserSource.validate(config));
    }

    @Test
    public void 配置为null被拒() throws Exception {
        assertNotNull(UserSource.validate(null));
    }

    // ── 应被接受的 ──────────────────────────────────────────

    @Test
    public void 最小可用REST配置通过() throws Exception {
        assertNull(UserSource.validate(rest()));
    }

    // ── 守着「模板不带源」这个产品决定 ──────────────────────

    @Test
    public void 内置模板应被拒_因为装完不自带源() throws Exception {
        String raw = readResource("site-default.json");
        assertNotNull("内置模板不该能当用户配置直接用（它没有 origin）",
                UserSource.validate(new JSONObject(raw)));
    }

    // ── 报错文案要能看懂 ────────────────────────────────────

    @Test
    public void 报错文案包含可读提示() throws Exception {
        String message = UserSource.validate(rest(null));
        assertNotNull(message);
        assertTrue("拒绝理由不该是空的", message.trim().length() > 0);
        assertTrue("拒绝理由不该是 null 字符串", !message.contains("null"));
    }

    private static String readResource(String name) throws Exception {
        try (java.io.InputStream in = UserSourceTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("测试资源缺失: " + name);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
