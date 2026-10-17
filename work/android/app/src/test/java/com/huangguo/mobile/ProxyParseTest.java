package com.huangguo.mobile;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * AiiHttp.parseProxy() 的单元测试：把各种代理串喂进去，看谁能过。
 *
 * 这个函数的产出直接决定了「设置里填的代理能不能用」。机场节点格式（ss / vmess /
 * vless / trojan / hysteria2 / tuic）目前**解析不出来**，返回 null，App 会提示
 * 「格式看不懂」—— 这是现状，不是 bug，测试把这条边界固定下来，
 * 免得哪天它悄悄变成「解析成功但连不上」那种更难查的状态。
 *
 * ⚠ 等等，上面那句是旧的描述。实际测完发现分两类：
 *   - 裸 host:port、socks5://、http://  → 应当能解析（走代理）
 *   - 机场节点格式（ss/vmess/…）        → 解析返回 null（不认识）
 * 见下方 setUp 里的假设和每个用例的断言。
 */
public class ProxyParseTest {

    /** 能解析的：裸地址、socks5、http。 */
    @Test
    public void 裸host_port能解析() throws Exception {
        assertNotNull(invoke("1.2.3.4:1080"));
    }

    @Test
    public void socks5无认证能解析() throws Exception {
        assertNotNull(invoke("socks5://1.2.3.4:1080"));
    }

    @Test
    public void socks5带认证能解析() throws Exception {
        assertNotNull(invoke("socks5://u:p@1.2.3.4:1080"));
    }

    @Test
    public void http代理能解析() throws Exception {
        assertNotNull(invoke("http://1.2.3.4:8080"));
    }

    /** 解析不了的：机场订阅里那种节点 URI。给 null 而不是抛异常。 */
    @Test
    public void ss节点解析为null() throws Exception {
        assertNull(invoke("ss://YWVzLTI1Ni1nY206cGFzcw==@1.2.3.4:8388#%E8%8A%82%E7%82%B9"));
    }

    @Test
    public void vmess节点解析为null() throws Exception {
        assertNull(invoke("vmess://eyJhZGQiOiIxLjIuMy40IiwicG9ydCI6NDQzLCJpZCI6ImFiYyJ9"));
    }

    @Test
    public void vless节点解析为null() throws Exception {
        assertNull(invoke("vless://abc-uuid@1.2.3.4:443?type=ws&security=tls#node"));
    }

    @Test
    public void trojan节点解析为null() throws Exception {
        assertNull(invoke("trojan://password123@1.2.3.4:443?sni=example.com#node"));
    }

    @Test
    public void hysteria2节点解析为null() throws Exception {
        assertNull(invoke("hysteria2://pass@1.2.3.4:443?sni=example.com#node"));
    }

    @Test
    public void tuic节点解析为null() throws Exception {
        assertNull(invoke("tuic://uuid:pass@1.2.3.4:443#node"));
    }

    private static Object invoke(String value) throws Exception {
        Class<?> type = Class.forName("com.huangguo.mobile.AiiHttp");
        java.lang.reflect.Method method = type.getDeclaredMethod("parseProxy", String.class);
        method.setAccessible(true);
        return method.invoke(null, value);
    }
}
