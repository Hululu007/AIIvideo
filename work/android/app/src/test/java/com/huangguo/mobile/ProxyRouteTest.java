package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ProxyRoute} 的语义回归。
 *
 * <p>这个类的存在意义是「代理只有一种形态」：字符串进来，值对象出去，
 * 之后校验、挂认证、给 WebView 取 Proxy 都问它。所以用例盯的不是「能不能解析」
 * （{@code ProxyParseTest} 管那个），而是**三态语义**和**不泄密**。
 */
public class ProxyRouteTest {

    /** 空串是「直连」，不是「错」。两者混了的话，清空代理会弹一句报错。 */
    @Test public void 空串是直连不是错误() {
        ProxyRoute.Parsed p = ProxyRoute.parse("");
        assertTrue(p.direct());
        assertTrue("空串必须是成功状态", p.ok());
        assertNull(p.route);
        assertNull(p.error);

        assertTrue(ProxyRoute.parse("   ").direct());
        assertTrue(ProxyRoute.parse(null).direct());
    }

    /** 认不出来的串要带一句人话，不能静默变直连。 */
    @Test public void 认不出来要报错不能静默直连() {
        ProxyRoute.Parsed p = ProxyRoute.parse("vmess://eyJhZGQiOiIxLjIuMy40In0=");
        assertFalse("机场节点格式不该被当成直连", p.direct());
        assertFalse(p.ok());
        assertNull(p.route);
        assertNotNull("必须带一句给人看的错", p.error);
    }

    @Test public void 合法写法都能解析() {
        assertNotNull(ProxyRoute.parse("1.2.3.4:1080").route);
        assertNotNull(ProxyRoute.parse("socks5://1.2.3.4:1080").route);
        assertNotNull(ProxyRoute.parse("http://u:p@1.2.3.4:8080").route);
        assertEquals(ProxyRoute.Kind.SOCKS, ProxyRoute.parse("socks5://1.2.3.4:1080").route.kind);
        assertEquals(ProxyRoute.Kind.HTTP, ProxyRoute.parse("1.2.3.4:1080").route.kind);
        assertEquals(1080, ProxyRoute.parse("1.2.3.4:1080").route.port);
        assertEquals("1.2.3.4", ProxyRoute.parse("1.2.3.4:1080").route.host);
    }

    /** 密码里带 @ 也要切对：user:p@ss@host:port。 */
    @Test public void 密码里的at不破坏切分() {
        ProxyRoute r = ProxyRoute.parse("user:p@ss@1.2.3.4:1080").route;
        assertNotNull(r);
        assertEquals("1.2.3.4", r.host);
        assertEquals(1080, r.port);
        assertTrue(r.needsAuth());
    }

    /**
     * <b>日志和缓存键里不能出现密码。</b>
     *
     * 这两样东西会进 logcat 和调试路径。凭据是明文的，漏一次就是事故 ——
     * 所以这条单独钉死。
     */
    @Test public void 描述和缓存键不带密码() {
        ProxyRoute r = ProxyRoute.parse("socks5://alice:s3cr3t@1.2.3.4:1080").route;
        assertNotNull(r);
        assertFalse("describe 泄露了密码：" + r.describe(), r.describe().contains("s3cr3t"));
        assertFalse("key 泄露了密码：" + r.key(), r.key().contains("s3cr3t"));
        // 但「带认证」这件事要能看出来
        assertTrue(r.describe().contains("带认证"));
        assertTrue(r.key().contains("alice"));
    }

    /** 缓存键要区分「同主机不同认证」—— 换账号要换一条连接。 */
    @Test public void 不同账号的缓存键不同() {
        ProxyRoute a = ProxyRoute.parse("socks5://u1:p@1.2.3.4:1080").route;
        ProxyRoute b = ProxyRoute.parse("socks5://u2:p@1.2.3.4:1080").route;
        assertNotNull(a);
        assertNotNull(b);
        assertFalse("换账号必须换 key，否则会复用上一条带着旧凭据的连接",
                a.key().equals(b.key()));
    }

    /** 端口越界、host 为空、残片都得拒 —— 收下之后会在发请求时炸成看不懂的错。 */
    @Test public void 边界值都拒绝() {
        assertNull(ProxyRoute.parse("1.2.3.4:0").route);
        assertNull(ProxyRoute.parse("1.2.3.4:70000").route);
        assertNull(ProxyRoute.parse(":1080").route);
        assertNull(ProxyRoute.parse("1.2.3.4").route);
        assertNull(ProxyRoute.parse("://1.2.3.4:1080").route);
        assertNull(ProxyRoute.parse("1.2.3.4:1080/x").route);
    }

    /** 生成的 java.net.Proxy 类型要和 scheme 对得上（SOCKS 走 SOCKS，别退成 HTTP）。 */
    @Test public void javaProxy类型正确() {
        assertEquals(java.net.Proxy.Type.SOCKS,
                ProxyRoute.parse("socks5://1.2.3.4:1080").route.javaProxy().type());
        assertEquals(java.net.Proxy.Type.HTTP,
                ProxyRoute.parse("http://1.2.3.4:8080").route.javaProxy().type());
    }
}
