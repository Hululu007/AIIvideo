package com.huangguo.mobile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * spider 运行时里所有能脱离 Android 测的纯函数。
 *
 * <p>分成三组，共同点是<b>错了都不会崩</b>：
 * <ul>
 *   <li>白名单解析：解析成空表 = 所有 {@code net} 被拒（表现为「这个源取不到数据」，
 *       和站点改版一模一样）；解析成过宽 = 白名单形同不存在。两种都只能靠用例守着。</li>
 *   <li>{@code format} 解析：走错编码的表现是对方回一句「参数缺失」，看起来像源拼错了
 *       参数，而真正的原因是体序列化形式不对。</li>
 *   <li>{@code keep} 解析：丢了名字 = 源静默拿到 {@code undefined} 然后每部片都是随机的
 *       （91porn 那个游客标记正是这个症状）。</li>
 * </ul>
 *
 * <p>故意的边界：这里<b>不</b>碰 DNS、不碰 OkHttp、不 mock 网络。要测的是「参数进来之后
 * 归一化成什么」，那一层的输入输出都是纯数据。
 */
public class SpiderRuntimeTest {

    // ── 白名单 ────────────────────────────────────────────────────────

    /** 正常声明：原样取出来，统一小写、去掉首尾空白。 */
    @Test public void 声明的主机名统一小写并去空白() {
        assertArrayEquals(new String[]{"api.example.com", "cdn.example.com"},
                SpiderRuntime.parseHosts("[\" API.Example.com \",\"cdn.example.com\"]"));
    }

    /** 空条目要丢掉 —— 留着它等于允许访问空主机名，比不放行更糟。 */
    @Test public void 空条目被丢掉() {
        assertArrayEquals(new String[]{"a.com"},
                SpiderRuntime.parseHosts("[\"\",\"  \",\"a.com\"]"));
    }

    /** 没声明就是空表：net 全拒。这个语义不能退化成「不限」。 */
    @Test public void 没声明就是空表() {
        assertEquals(0, SpiderRuntime.parseHosts(null).length);
        assertEquals(0, SpiderRuntime.parseHosts("").length);
        assertEquals(0, SpiderRuntime.parseHosts("null").length);
        assertEquals(0, SpiderRuntime.parseHosts("[]").length);
    }

    /** 回包不是 JSON 时按空白名单算：宁可拒绝，不能放行。 */
    @Test public void 解析失败按空白名单算() {
        assertEquals(0, SpiderRuntime.parseHosts("这不是 JSON").length);
        assertEquals(0, SpiderRuntime.parseHosts("{}").length);
    }

    // ── format ────────────────────────────────────────────────────────

    /** 缺省必须是 form：这是加 format 之前所有源的行为，不能变。 */
    @Test public void format缺省是form() throws Exception {
        assertEquals("form", SpiderRuntime.parseFormat(null));
        assertEquals("form", SpiderRuntime.parseFormat(""));
        assertEquals("form", SpiderRuntime.parseFormat("   "));
        assertEquals("form", SpiderRuntime.parseFormat("form"));
    }

    /** 大小写和空白都由解析器吃下来，源的写法不用精确到字节。 */
    @Test public void format大小写与空白都归一成小写() throws Exception {
        assertEquals("json", SpiderRuntime.parseFormat("JSON"));
        assertEquals("json", SpiderRuntime.parseFormat("  Json  "));
        assertEquals("form", SpiderRuntime.parseFormat("FORM"));
    }

    /**
     * 认不出来的值必须抛，而且文案里要带原值。
     *
     * <p>静默退回 form 的代价：请求发出去了，对方回「path 不能为空」，
     * 而真正的原因是 format 拼错了 —— 这两件事从响应上分不出来。
     */
    @Test public void 不认识的format报错并带原值() {
        try {
            SpiderRuntime.parseFormat("jsonp");
            fail("jsonp 应该被拒绝");
        } catch (IOException e) {
            assertEquals("net 失败：不认识的 format：jsonp（只认 form / json，缺省是 form）",
                    e.getMessage());
        }
        try {
            SpiderRuntime.parseFormat("multipart");
            fail("multipart 应该被拒绝");
        } catch (IOException e) {
            assertTrue(e.getMessage().startsWith("net 失败：不认识的 format："));
            assertTrue(e.getMessage().contains("multipart"));
        }
    }

    /** 附近似值不能靠「包含 json 就算 json」蒙混过去 —— 整个 MIME 串也是不认识的。 */
    @Test public void 只有json一个词才算json() {
        try {
            SpiderRuntime.parseFormat("application/json");
            fail("application/json 不是合法取值，应该被拒绝");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("application/json"));
        }
        try {
            SpiderRuntime.parseFormat("text/json");
            fail("text/json 应该被拒绝");
        } catch (IOException expected) {
            // 文案由上一个用例钉，这里只确认它没被当成 json。
        }
    }

    // ── keep ──────────────────────────────────────────────────────────

    /** 没传 / null / JSON 的 null 都按「不要 cookie」算，返回空表而不是 null。 */
    @Test public void keep缺省是空表() {
        assertEquals(0, SpiderRuntime.parseKeep(null).length);
        assertEquals(0, SpiderRuntime.parseKeep(JSONObject.NULL).length);
        assertEquals(0, SpiderRuntime.parseKeep(new JSONArray()).length);
    }

    /** 数组是标准写法：原样取出来，顺序保持。 */
    @Test public void keep数组原样取出来() {
        JSONArray arr = new JSONArray().put("ga").put("sid");
        assertArrayEquals(new String[]{"ga", "sid"}, SpiderRuntime.parseKeep(arr));
    }

    /** 单个字符串也能用 —— 这种写法太自然，拒绝它只会换来「源作者以为是别的错」。 */
    @Test public void keep单个字符串当成一项() {
        assertArrayEquals(new String[]{"ga"}, SpiderRuntime.parseKeep("ga"));
        assertArrayEquals(new String[]{"ga"}, SpiderRuntime.parseKeep("  ga  "));
        assertEquals(0, SpiderRuntime.parseKeep("").length);
        assertEquals(0, SpiderRuntime.parseKeep("   ").length);
    }

    /** 空串条目要丢掉：留着它等于让 cookieOut.get("") 去命中一个不存在的键。 */
    @Test public void keep空串条目被丢掉() {
        JSONArray arr = new JSONArray().put("").put("  ").put("ga");
        assertArrayEquals(new String[]{"ga"}, SpiderRuntime.parseKeep(arr));
    }

    /** 非字符串条目（数字/对象/内嵌 null）一律丢，不静默转成字符串。 */
    @Test public void keep非字符串条目被丢掉() {
        JSONArray arr = new JSONArray().put(7).put(JSONObject.NULL).put("ga");
        assertArrayEquals(new String[]{"ga"}, SpiderRuntime.parseKeep(arr));
    }

    /** 重复的名字只留一份：cookieOut 那张表一个名字只有一个位置。 */
    @Test public void keep重复名字只留一份() {
        JSONArray arr = new JSONArray().put("ga").put("sid").put("ga");
        assertArrayEquals(new String[]{"ga", "sid"}, SpiderRuntime.parseKeep(arr));
    }

    /** 认不出来的类型按「不要」算：keep 是可选参数，它写歪了不该让整次 net 失败。 */
    @Test public void keep类型不对按空表算() {
        assertEquals(0, SpiderRuntime.parseKeep(Integer.valueOf(1)).length);
        assertEquals(0, SpiderRuntime.parseKeep(new JSONObject()).length);
    }

    // ── cookies 回传 ──────────────────────────────────────────────────

    /** 只回传 keep 要的那几个，别的 cookie 一个都不带（省带宽，也不让源误以为拿到了）。 */
    @Test public void pickCookies只挑keep要的名字() {
        Map<String, String> jar = new LinkedHashMap<String, String>();
        jar.put("ga", "abc123");
        jar.put("phpsession", "zzz");
        JSONObject out = SpiderRuntime.pickCookies(jar, new String[]{"ga"});
        assertNotNull(out);
        assertEquals(1, out.length());
        assertEquals("abc123", out.optString("ga", ""));
        assertEquals("", out.optString("phpsession", ""));
    }

    /** 站点没下发：返回 null，调用方据此<b>不放</b> cookies 字段（源走兜底值）。 */
    @Test public void pickCookies没收到就是null() {
        Map<String, String> jar = new LinkedHashMap<String, String>();
        jar.put("phpsession", "zzz");
        assertNull(SpiderRuntime.pickCookies(jar, new String[]{"ga"}));
        assertNull(SpiderRuntime.pickCookies(null, new String[]{"ga"}));
    }

    /** keep 为空表 → null。这条是契约里「keep 为空就不带 cookies 字段」的落点。 */
    @Test public void pickCookies没要就是null() {
        Map<String, String> jar = new LinkedHashMap<String, String>();
        jar.put("ga", "abc123");
        assertNull(SpiderRuntime.pickCookies(jar, new String[0]));
        assertNull(SpiderRuntime.pickCookies(jar, null));
    }

    /**
     * 空值不算「收到了」。
     *
     * <p>站点清标记时下发 {@code ga=; Max-Age=0}，把头里的空串带回去是
     * {@code Cookie: ga=} —— 对面会当成「有标记但无效」，比不带更糟。
     */
    @Test public void pickCookies空值不算收到() {
        Map<String, String> jar = new LinkedHashMap<String, String>();
        jar.put("ga", "");
        assertNull(SpiderRuntime.pickCookies(jar, new String[]{"ga"}));
    }

    /** 值原样保留：base64 里的 {@code =}、引号都得一字不动还回去。 */
    @Test public void pickCookies值原样保留() {
        Map<String, String> jar = new LinkedHashMap<String, String>();
        jar.put("sid", "a=b==c");
        JSONObject out = SpiderRuntime.pickCookies(jar, new String[]{"sid"});
        assertNotNull(out);
        assertEquals("a=b==c", out.optString("sid", ""));
    }

    /** 多个名字：要的几个都在、顺序和 keep 一致。 */
    @Test public void pickCookies多个名字都在() {
        Map<String, String> jar = new LinkedHashMap<String, String>();
        jar.put("sid", "s1");
        jar.put("ga", "g1");
        JSONObject out = SpiderRuntime.pickCookies(jar, new String[]{"ga", "sid"});
        assertNotNull(out);
        assertEquals(2, out.length());
        assertEquals("g1", out.optString("ga", ""));
        assertEquals("s1", out.optString("sid", ""));
        String text = out.toString();
        assertFalse("cookies 里不该出现别的键", text.contains("phpsession"));
    }
}
