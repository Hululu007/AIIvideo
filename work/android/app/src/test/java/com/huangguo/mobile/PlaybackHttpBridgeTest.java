package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 播放层出口白名单的回归。
 *
 * <p>2026-10-18 之前，这两台机器写死在 {@link PlaybackHttpBridge} 的
 * {@code HTTP_HOSTS} 里 —— 换个源或换个播放线路就得改 Java、重新发版。
 * 现在由源自己在 {@code http.hosts} 里声明，这条用例钉住三条语义：
 * 声明什么就是什么、大小写不敏感、**没声明就是全拒**。
 *
 * <p>最后那条是重点。空表如果被当成「不限」，那这面墙就形同不存在 ——
 * 而它挡的是「页面上的任意脚本借 Java 的网络能力去访问任意 https 站点」。
 */
public class PlaybackHttpBridgeTest {

    private static JSONObject block(String... hosts) throws Exception {
        JSONObject http = new JSONObject();
        JSONArray list = new JSONArray();
        for (String h : hosts) list.put(h);
        http.put("hosts", list);
        return http;
    }

    /** 声明了几个就是几个，原样保留（顺序按声明）。 */
    @Test public void 声明什么就是什么() throws Exception {
        assertEquals("[jx.wuzhoupai.com, xcdn.khfed.com]",
                PlaybackHttpBridge.hostsOf(block("jx.wuzhoupai.com", "xcdn.khfed.com")).toString());
    }

    /** 手写配置里写大写也该能用 —— 域名比较不该受大小写影响。 */
    @Test public void 主机名统一小写() throws Exception {
        assertTrue(PlaybackHttpBridge.hostsOf(block("JX.WuZhouPai.COM")).contains("jx.wuzhoupai.com"));
    }

    /** 空行、空格、空串都丢掉：留个空行不该变成「允许访问空主机名」。 */
    @Test public void 空白条目被丢掉() throws Exception {
        assertTrue(PlaybackHttpBridge.hostsOf(block("  ", "", " xcdn.khfed.com ")).size() == 1);
        assertTrue(PlaybackHttpBridge.hostsOf(block("  ", " xcdn.khfed.com ")).contains("xcdn.khfed.com"));
    }

    /** 没声明 = 空表 = 全拒。这条是墙本身，不能退化成「不限」。 */
    @Test public void 没声明就是空表() throws Exception {
        assertEquals(0, PlaybackHttpBridge.hostsOf(null).size());
        assertEquals(0, PlaybackHttpBridge.hostsOf(new JSONObject()).size());
        assertEquals(0, PlaybackHttpBridge.hostsOf(block()).size());
    }

    /** 装载之后就按新表判：配一个源不该影响另一个源的判断。 */
    @Test public void 装载后按当前源判断() throws Exception {
        PlaybackHttpBridge.configure(block("a.example.com"));
        assertTrue(PlaybackHttpBridge.hostsOf(block("a.example.com")).contains("a.example.com"));
        assertFalse(PlaybackHttpBridge.hostsOf(null).contains("a.example.com"));
        // 收尾：别把状态留给别的用例
        PlaybackHttpBridge.configure(null);
        assertEquals(0, PlaybackHttpBridge.hostsOf(null).size());
    }
}
