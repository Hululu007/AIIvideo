import java.lang.reflect.Method;

/** 把各种代理串喂进 AiiHttp.parseProxy，看谁能过。 */
public class ProxyParseTest {
    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("com.huangguo.mobile.AiiHttp");
        Method m = c.getDeclaredMethod("parseProxy", String.class);
        m.setAccessible(true);

        String[][] cases = {
            {"裸 host:port",              "1.2.3.4:1080"},
            {"socks5 无认证",             "socks5://1.2.3.4:1080"},
            {"socks5 带认证",             "socks5://u:p@1.2.3.4:1080"},
            {"http 代理",                 "http://1.2.3.4:8080"},
            {"—— 以下是机场节点格式 ——",  ""},
            {"ss (Shadowsocks)",          "ss://YWVzLTI1Ni1nY206cGFzcw==@1.2.3.4:8388#%E8%8A%82%E7%82%B9"},
            {"vmess",                     "vmess://eyJhZGQiOiIxLjIuMy40IiwicG9ydCI6NDQzLCJpZCI6ImFiYyJ9"},
            {"vless",                     "vless://abc-uuid@1.2.3.4:443?type=ws&security=tls#node"},
            {"trojan",                    "trojan://password123@1.2.3.4:443?sni=example.com#node"},
            {"hysteria2",                 "hysteria2://pass@1.2.3.4:443?sni=example.com#node"},
            {"tuic",                      "tuic://uuid:pass@1.2.3.4:443#node"},
        };
        for (String[] t : cases) {
            if (t[1].isEmpty()) { System.out.println("\n" + t[0]); continue; }
            Object r = m.invoke(null, t[1]);
            String verdict = r == null ? "❌ 解析失败（App 会报「格式看不懂」）" : "✅ " + r;
            System.out.printf("  %-22s %s%n", t[0], verdict);
        }
    }
}
