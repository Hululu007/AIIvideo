package com.huangguo.mobile;

import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 把一条落地节点翻译成 Xray 能吃的 JSON。
 *
 * ── 为什么要单独一个类 ────────────────────────────────────────────
 * 它只做一件事：节点信息 → 配置字符串。刻意不碰 Android 类、不碰 native、
 * 不读文件 —— 于是它能在 JVM 单测里跑，每个字段的拼法都能被断言盯住。
 * 一旦它长出一个 Context 参数，那几十条断言就全得改成仪器测试，等于没人测。
 *
 * ── 出站协议覆盖到哪 ──────────────────────────────────────────────
 *   vmess / shadowsocks / trojan / vless / socks / http   已实现
 *   wireguard / hysteria2 / tuic                          没有。它们的 outbound
 *                                                         结构和上面全不一样，
 *                                                         不是「多填一个字段」。
 *
 * 不认识的一律抛 IllegalArgumentException，**不要**在这里悄悄退回直连 ——
 * 那样用户看到的是「配置成功了但所有请求都从本地 IP 出去」，比直接报错危险。
 */
final class XrayConfig {

    /** 入站 tag。ProxyCore 用它查流量统计，所以是常量而不是散落的字面量。 */
    static final String TAG_IN = "socks-in";
    static final String TAG_OUT = "proxy";
    static final String TAG_DIRECT = "direct";

    private static final String LISTEN = "127.0.0.1";

    private XrayConfig() {}

    // ── 节点 ──────────────────────────────────────────────────────────

    /**
     * 一条落地节点。
     *
     * 字段是「各协议并集」而不是继承出六个子类：机场订阅里混杂的协议就那几种，
     * 扁平载体 + 按 protocol 分派比六个子类少一半样板，而且从 UI 表单
     * （一行一个字段）直接映射过来时不用做类型判断。
     */
    static final class Node {
        /** vmess / vless / trojan / shadowsocks / socks / http */
        String protocol;
        String server;
        int port;

        /** vmess 的 id、vless 的 uuid、trojan 与 socks/http 的密码。 */
        String uuid;
        /** shadowsocks 的加密方式；socks/http 出站时复用成「密码」（见 socksOut）。 */
        String method;
        /** vmess 的 alterId，默认 0（AEAD）。老机场要 64。 */
        int alterId = 0;
        /** vmess 的加密选路，默认 auto。 */
        String security = "auto";

        /** 传输层：tcp / ws / grpc / h2 */
        String network = "tcp";
        /** 传输层安全：none / tls / reality */
        String tls = "none";

        /** ws / h2 的路径。 */
        String path = "/";
        /** ws / h2 的 Host 头；TLS 缺 SNI 时也拿它。 */
        String host;
        /** 指纹：chrome / firefox / safari / randomized … */
        String fingerprint;
        /**
         * reality 的公钥（分享链接里叫 pbk）。**没有它 reality 起不来** ——
         * 客户端要靠它验证服务端临时密钥，缺了 Xray 会拒绝启动或在握手时失败。
         */
        String publicKey;
        /** reality 的 shortId（链接里叫 sid）。老版本可空，新版本多数会给。 */
        String shortId;
        /** vless / trojan 的流控，透传。 */
        String flow;
        /** grpc 的 serviceName。 */
        String serviceName;
        /**
         * TLS 证书不校验。裸 https 代理（free 订阅里成片出现）几乎全是自签证书，
         * 不写这条握手就过不去。只对「用户自己粘进来的代理」生效。
         */
        boolean allowInsecure;

        Node() {}

        Node(String protocol, String server, int port) {
            this.protocol = protocol;
            this.server = server;
            this.port = port;
        }

        /** 给人看的一行。不含凭据 —— 这行会进 logcat。 */
        String describe() {
            return protocol + " " + server + ":" + port;
        }
    }

    // ── 生成 ──────────────────────────────────────────────────────────

    /**
     * 生成完整配置。
     *
     * @param node 落地节点
     * @param port 本地 SOCKS5 端口，由 ProxyCore 选好传进来
     */
    static JSONObject build(Node node, int port) throws JSONException {
        if (node == null) throw new IllegalArgumentException("节点为空");
        if (empty(node.server)) throw new IllegalArgumentException("节点缺服务端地址");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("本地端口超出范围：" + port);

        JSONObject root = new JSONObject();
        root.put("log", new JSONObject().put("loglevel", "warning"));
        root.put("stats", new JSONObject());
        root.put("policy", policy());
        root.put("inbounds", new JSONArray().put(inbound(port)));
        root.put("outbounds", new JSONArray().put(outbound(node)).put(direct()).put(block()));
        // api 只对「进程内自己查」有用，不监听端口 —— 见 api()。
        root.put("api", api());
        root.put("routing", routing());
        return root;
    }

    /** 便捷入口：直接拿字符串喂 startLoop。 */
    static String buildString(Node node, int port) {
        try {
            return build(node, port).toString();
        } catch (JSONException e) {
            // build() 里只有 put 会抛，而键都是常量 —— 到不了这儿。
            throw new IllegalStateException("配置序列化失败", e);
        }
    }

    // ── inbound ───────────────────────────────────────────────────────

    /**
     * SOCKS5 入站。
     *
     * listen 必须是 127.0.0.1，不能省 —— 省掉就是 0.0.0.0，同网段任何人抓一下
     * 手机 IP 就能白嫖你的机场，而且全程记在你头上。这是整个文件里唯一一处
     * 「写错会出事」的字段。
     */
    static JSONObject inbound(int port) throws JSONException {
        JSONObject settings = new JSONObject()
                .put("auth", "noauth")
                // udp 不开的话，走 QUIC 的流媒体（YouTube 的 h3、部分站的 WebRTC）
                // 会表现成「网页能开、视频一直转圈」。
                .put("udp", true)
                // 关键：让 SOCKS5 请求里的域名原样交给落地节点去解析。
                // 缺这一条时 Xray 先在本地把域名解析成 IP 再发出去 —— 等于把 DNS
                // 查询暴露给本地网络，而且对被污染的域名直接就是错的地址。
                .put("ip", "127.0.0.1");
        return new JSONObject()
                .put("tag", TAG_IN)
                .put("port", port)
                .put("listen", LISTEN)
                .put("protocol", "socks")
                .put("settings", settings)
                // sniffing 让 Xray 从流量里嗅出真实域名再回填/路由，
                // 对「客户端直接连 IP」的场景是必需的辅助。
                .put("sniffing", new JSONObject()
                        .put("enabled", true)
                        .put("destOverride", new JSONArray().put("http").put("tls"))
                        .put("routeOnly", false));
    }

    // ── 各协议的 outbound ─────────────────────────────────────────────

    private static JSONObject outbound(Node node) throws JSONException {
        String protocol = normalize(node.protocol);
        JSONObject out = new JSONObject()
                .put("tag", TAG_OUT)
                .put("protocol", protocol)
                .put("settings", settings(protocol, node));
        JSONObject stream = stream(node);
        if (stream != null) out.put("streamSettings", stream);
        return out;
    }

    private static JSONObject settings(String protocol, Node node) throws JSONException {
        switch (protocol) {
            case "vmess":       return vmess(node);
            case "vless":       return vless(node);
            case "trojan":      return trojan(node);
            case "shadowsocks": return shadowsocks(node);
            case "socks":       return socksOut(node);
            case "http":        return httpOut(node);
            default:
                throw new IllegalArgumentException("暂不支持 " + node.protocol + " 协议");
        }
    }

    private static JSONObject vmess(Node node) throws JSONException {
        require(node.uuid, "vmess 缺 uuid");
        JSONObject user = new JSONObject()
                .put("id", node.uuid)
                // alterId=0 是 AEAD，2022 年之后建的节点都是这个；老机场写 64 才连得上，
                // 所以从节点里读而不是写死。
                .put("alterId", node.alterId)
                .put("security", empty(node.security) ? "auto" : node.security);
        JSONObject vnext = new JSONObject()
                .put("address", node.server)
                .put("port", node.port)
                .put("users", new JSONArray().put(user));
        return new JSONObject().put("vnext", new JSONArray().put(vnext));
    }

    private static JSONObject vless(Node node) throws JSONException {
        require(node.uuid, "vless 缺 uuid");
        JSONObject user = new JSONObject().put("id", node.uuid).put("encryption", "none");
        if (!empty(node.flow)) user.put("flow", node.flow);
        JSONObject vnext = new JSONObject()
                .put("address", node.server)
                .put("port", node.port)
                .put("users", new JSONArray().put(user));
        return new JSONObject().put("vnext", new JSONArray().put(vnext));
    }

    private static JSONObject trojan(Node node) throws JSONException {
        require(node.uuid, "trojan 缺密码");
        JSONObject server = new JSONObject()
                .put("address", node.server)
                .put("port", node.port)
                .put("password", node.uuid);
        if (!empty(node.flow)) server.put("flow", node.flow);
        return new JSONObject().put("servers", new JSONArray().put(server));
    }

    private static JSONObject shadowsocks(Node node) throws JSONException {
        // 老订阅把密码放 password、新写法放 uuid，两个都认 —— 同一份节点从
        // ss:// 和 vmess:// 解析出来时字段名不一样，别让调用方去记这件事。
        // 约定：uuid 有值就是密码，method 有值就是加密方式；只有 method 有值时，
        // 说明调用方把「密码」误填进了 method，按密码处理并回落默认加密。
        String password;
        String method;
        if (!empty(node.uuid)) {
            password = node.uuid;
            method = empty(node.method) ? "aes-256-gcm" : node.method;
        } else {
            password = node.method;
            method = "aes-256-gcm";
        }
        require(password, "shadowsocks 缺密码");
        JSONObject server = new JSONObject()
                .put("address", node.server)
                .put("port", node.port)
                .put("method", method)
                .put("password", password)
                // uot（UDP over TCP）各家实现行为不一致，开了更容易连不上；
                // SOCKS 层的 udp 我们已经在入站里放行了。
                .put("uot", false);
        return new JSONObject().put("servers", new JSONArray().put(server));
    }

    /** socks 出站：上游本来就是代理，是这里最短的一条。method 复用成密码。 */
    private static JSONObject socksOut(Node node) throws JSONException {
        return userServer(node);
    }

    private static JSONObject httpOut(Node node) throws JSONException {
        return userServer(node);
    }

    private static JSONObject userServer(Node node) throws JSONException {
        JSONObject server = new JSONObject()
                .put("address", node.server)
                .put("port", node.port);
        if (!empty(node.uuid)) {
            server.put("users", new JSONArray().put(new JSONObject()
                    .put("user", node.uuid)
                    .put("pass", empty(node.method) ? "" : node.method)));
        }
        return new JSONObject().put("servers", new JSONArray().put(server));
    }

    // ── 传输层 ────────────────────────────────────────────────────────

    /**
     * streamSettings。tcp + 无 TLS 时返回 null —— 那是 Xray 的默认值，
     * 写出来只是让配置变长、让测试断言多一层噪音。
     */
    static JSONObject stream(Node node) throws JSONException {
        String network = normalize(node.network);
        if (empty(network)) network = "tcp";
        String security = normalize(node.tls);
        if (empty(security)) security = "none";

        if ("tcp".equals(network) && "none".equals(security)) return null;

        JSONObject stream = new JSONObject().put("network", network).put("security", security);

        if ("tls".equals(security)) {
            JSONObject tls = new JSONObject();
            // SNI 缺省用 Host 头。ws + tls 的机场几乎总是同域，两处分开的只有
            // 「SNI 走 CDN、Host 走源站」的少数场景。
            tls.put("serverName", empty(node.host) ? node.server : node.host);
            if (!empty(node.fingerprint)) tls.put("fingerprint", node.fingerprint);
            if (node.allowInsecure) tls.put("allowInsecure", true);
            stream.put("tlsSettings", tls);
        } else if ("reality".equals(security)) {
            // libgojni.so 里 reality 只有 1 处字符串命中，八成是残缺实现。
            // 字段照摆，但不做任何「能用」的承诺。
            JSONObject reality = new JSONObject()
                    .put("serverName", empty(node.host) ? node.server : node.host)
                    .put("publicKey", empty(node.publicKey) ? "" : node.publicKey)
                    .put("shortId", empty(node.shortId) ? "" : node.shortId);
            if (!empty(node.fingerprint)) reality.put("fingerprint", node.fingerprint);
            stream.put("realitySettings", reality);
        }

        if ("ws".equals(network)) {
            JSONObject ws = new JSONObject().put("path", empty(node.path) ? "/" : node.path);
            if (!empty(node.host)) ws.put("headers", new JSONObject().put("Host", node.host));
            stream.put("wsSettings", ws);
        } else if ("grpc".equals(network)) {
            stream.put("grpcSettings", new JSONObject()
                    .put("serviceName", empty(node.serviceName) ? "" : node.serviceName)
                    .put("multiMode", false));
        } else if ("h2".equals(network)) {
            // Xray 里 h2 的键叫 httpSettings（h2 只是 network 的别名）。
            JSONObject h2 = new JSONObject().put("path", empty(node.path) ? "/" : node.path);
            if (!empty(node.host)) h2.put("host", new JSONArray().put(node.host));
            stream.put("httpSettings", h2);
        }
        return stream;
    }

    // ── 附带结构 ──────────────────────────────────────────────────────

    /**
     * 只开进程内 api。注意**没有 listen 字段** —— 有的话就是在本机开了一个
     * gRPC 控制台，同机任何 App 都能改路由。
     */
    private static JSONObject api() throws JSONException {
        return new JSONObject()
                .put("tag", "api")
                .put("services", new JSONArray()
                        .put("HandlerService")
                        .put("LoggerService")
                        .put("StatsService"));
    }

    private static JSONObject policy() throws JSONException {
        JSONObject levels = new JSONObject().put("0", new JSONObject()
                .put("statsUserUplink", true)
                .put("statsUserDownlink", true));
        return new JSONObject().put("levels", levels)
                .put("system", new JSONObject()
                        .put("statsInboundUplink", true)
                        .put("statsInboundDownlink", true)
                        .put("statsOutboundUplink", true)
                        .put("statsOutboundDownlink", true));
    }

    private static JSONObject routing() throws JSONException {
        JSONArray rules = new JSONArray()
                // api 自己的流量必须直连，否则查统计会绕一圈走代理。
                .put(new JSONObject()
                        .put("type", "field")
                        .put("inboundTag", new JSONArray().put("api"))
                        .put("outboundTag", TAG_DIRECT));
        return new JSONObject().put("domainStrategy", "AsIs").put("rules", rules);
    }

    private static JSONObject direct() throws JSONException {
        return new JSONObject().put("tag", TAG_DIRECT).put("protocol", "freedom");
    }

    private static JSONObject block() throws JSONException {
        return new JSONObject().put("tag", "block").put("protocol", "blackhole");
    }

    // ── 分享链接解析 ──────────────────────────────────────────────────

    /**
     * 从 vmess:// 与 ss:// 分享链接里读出节点。
     *
     * 放在这个类里而不是另起一个：解析出来的就是 Node，和生成是同一个数据结构的
     * 两个方向。拆开会把「字段名到底叫什么」分散在两处，加一个协议要改两个文件。
     */
    static Node parseShareLink(String link) throws JSONException {
        if (empty(link)) throw new IllegalArgumentException("链接为空");
        String text = link.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("vmess://")) return parseVmess(text.substring(8));
        if (lower.startsWith("ss://")) return parseShadowsocks(text.substring(5));
        if (lower.startsWith("vless://")) return parseVless(text.substring(8));
        if (lower.startsWith("trojan://")) return parseTrojan(text.substring(9));
        // 裸代理地址（不是节点链接，是「直接可用的代理」）：http://user:pass@h:p
        // 和 socks5://h:p 这两种。免费订阅里这类不少，它们本来就能直接用。
        if (lower.startsWith("socks5://")) return parseBareProxy("socks", text.substring(9));
        if (lower.startsWith("socks://")) return parseBareProxy("socks", text.substring(8));
        if (lower.startsWith("http://")) return parseBareProxy("http", text.substring(7));
        if (lower.startsWith("https://")) {
            // https:// 的裸代理 = 「TLS 包着的 HTTP CONNECT」。协议还是 http，
            // 只是流要过 TLS，而且这类自签证书必须跳过校验。
            Node node = parseBareProxy("http", text.substring(8));
            node.tls = "tls";
            node.allowInsecure = true;
            return node;
        }
        throw new IllegalArgumentException(
                "认不出这个链接。支持 vmess:// 、vless:// 、trojan:// 、ss:// ，"
                        + "以及 http(s):// 与 socks5:// 形式的裸代理地址");
    }

    /**
     * vless://uuid@host:port?encryption=none&security=tls&type=ws&path=/x&host=a.com&sni=...
     *
     * 和 trojan 共用一个 URL 解析器：两者形状完全一样（userinfo@host:port + query），
     * 差别只在 userinfo 是 uuid 还是密码、以及默认端口不同。
     *
     * **userinfo 要原样保留**：vless 的 uuid 里没有特殊字符，但 trojan 的密码可能
     * 带 @ 或 :（已经 percent-encode 过），交给 URI 解开比手工切字符串可靠。
     */
    private static Node parseVless(String body) throws JSONException {
        return parseUrlStyle("vless", body, 443);
    }

    /**
     * 裸代理地址：`[user:pass@]host:port`。
     *
     * 和上面那些「节点链接」的区别：这些是**已经能直接用的代理**，不需要额外
     * 协议协商。免费订阅里成片出现（`http://1234:1234@1.2.3.4:2080` 那种）。
     *
     * 用户名密码是可选的，写法按「最后一个 @ 切」—— 和 vless/trojan 一致。
     * 注意这里**不认 query**：这类地址后缀上的 ?xxx 是机场标签，不是参数。
     */
    private static Node parseBareProxy(String protocol, String body) {
        String text = body;

        int hash = text.indexOf('#');
        if (hash >= 0) text = text.substring(0, hash);
        int qmark = text.indexOf('?');
        if (qmark >= 0) text = text.substring(0, qmark);
        // 有些人会在末尾带一个多余斜杠
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);

        String user = null;
        String pass = "";
        int at = text.lastIndexOf('@');
        if (at > 0) {
            String cred = percentDecode(text.substring(0, at));
            text = text.substring(at + 1);
            int sep = cred.indexOf(':');
            if (sep < 0) {
                user = cred;
            } else {
                user = cred.substring(0, sep);
                pass = cred.substring(sep + 1);
            }
            if (empty(user)) throw new IllegalArgumentException(protocol + " 代理地址的用户名是空的");
        }

        int colon = text.lastIndexOf(':');
        if (colon <= 0) throw new IllegalArgumentException(protocol + " 代理地址缺端口");
        String host = text.substring(0, colon).trim();
        int port;
        try {
            port = Integer.parseInt(text.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(protocol + " 代理地址端口不是数字");
        }
        if (host.isEmpty()) throw new IllegalArgumentException(protocol + " 代理地址缺主机");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException(protocol + " 代理地址端口越界：" + port);

        Node node = new Node(protocol, host, port);
        // userServer() 把 uuid 当 username、method 当 password 填进去，
        // 和 socks/http 出站的字段布局正好对上。
        if (user != null) {
            node.uuid = user;
            node.method = pass;
        }
        return node;
    }

    /**
     * vless / trojan 共用的解析：`userinfo@host:port?query#备注`。
     *
     * **不能用 java.net.URI。** 机场链接里的密码常常带 @ : # ? 这些字符（虽然有
     * 的会 percent-encode、有的不会），URI 遇到没转义的 @ 会直接抛 URISyntaxException，
     * 或者把 host 解析成前半截。所以这里手工切，规则是：
     *
     *   1. 先切掉 # 后面的备注
     *   2. 再切掉 ? 后面的 query
     *   3. **从右往左找最后一个 @** —— 密码里带 @ 是常见情况（如 p@ss），
     *      而 host 部分不可能含 @，所以最后一个 @ 一定是分隔符
     *   4. 剩下的 host:port 从右往左找最后一个 :
     *
     * 这套和上面 parseShadowsocks 用的是同一个思路，保持一致。
     */
    private static Node parseUrlStyle(String protocol, String body, int defaultPort) throws JSONException {
        String text = body;

        int hash = text.indexOf('#');
        if (hash >= 0) text = text.substring(0, hash);

        String query = "";
        int qmark = text.indexOf('?');
        if (qmark >= 0) {
            query = text.substring(qmark + 1);
            text = text.substring(0, qmark);
        }

        int at = text.lastIndexOf('@');
        if (at <= 0) throw new IllegalArgumentException(protocol + " 链接缺 uuid/密码");
        String userInfo = percentDecode(text.substring(0, at));
        if (empty(userInfo)) throw new IllegalArgumentException(protocol + " 链接缺 uuid/密码");

        String hostPort = text.substring(at + 1);
        int colon = hostPort.lastIndexOf(':');
        if (colon <= 0) throw new IllegalArgumentException(protocol + " 链接缺端口");
        String host = hostPort.substring(0, colon).trim();
        int port;
        try {
            port = Integer.parseInt(hostPort.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(protocol + " 链接端口不是数字");
        }
        if (host.isEmpty()) throw new IllegalArgumentException(protocol + " 链接缺服务器地址");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException(protocol + " 链接端口越界：" + port);

        Node node = new Node(protocol, host, port > 0 ? port : defaultPort);
        node.uuid = userInfo;
        applyQuery(node, query);
        return node;
    }

    /**
     * 把 query 里的标准参数摊到 Node 上。
     *
     * 只认这些键（都是 vless/trojan/vmess 三家用同一套命名的）：
     *   type            传输层 tcp/ws/grpc/h2
     *   security        tls/none/reality（trojan 缺省按 tls）
     *   path / host     ws、h2 用；host 同时兼作 SNI 缺省
     *   sni             TLS 的 serverName，覆盖 host
     *   fp              指纹
     *   pbk / sid       reality 的公钥与 shortId
     *   flow            vless 流控（xtls-rprx-vision 之类）
     *   serviceName     grpc
     *   alpn            暂不落字段，Xray 会用服务端协商
     *
     * 认不出的键直接忽略 —— 机场会塞一堆自家标签，报错反而不友好。
     */
    private static void applyQuery(Node node, String query) {
        if (empty(query)) {
            // trojan 几乎总是 TLS，而链接里通常不写 security。缺省补上，
            // 否则生成出来的配置是明文连 443，连不上还看不出原因。
            if ("trojan".equals(node.protocol)) node.tls = "tls";
            return;
        }
        java.util.Map<String, String> q = new java.util.HashMap<String, String>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = percentDecode(pair.substring(0, eq)).toLowerCase(Locale.ROOT);
            String v = percentDecode(pair.substring(eq + 1));
            q.put(k, v);
        }

        String type = q.get("type");
        if (!empty(type)) node.network = normalize(type);

        String security = q.get("security");
        if (!empty(security)) {
            node.tls = normalize(security);
        } else if ("trojan".equals(node.protocol)) {
            node.tls = "tls";
        }

        String path = q.get("path");
        if (!empty(path)) node.path = path;

        String host = q.get("host");
        if (!empty(host)) node.host = host;

        String sni = q.get("sni");
        if (!empty(sni)) node.host = sni;

        String fp = q.get("fp");
        if (!empty(fp)) node.fingerprint = fp;

        String pbk = q.get("pbk");
        if (!empty(pbk)) node.publicKey = pbk;

        String sid = q.get("sid");
        if (!empty(sid)) node.shortId = sid;

        String flow = q.get("flow");
        if (!empty(flow)) node.flow = flow;

        // 键在上面统一转成了小写，这里必须用小写键取 —— 写成 serviceName 永远拿不到。
        String serviceName = q.get("servicename");
        if (!empty(serviceName)) node.serviceName = serviceName;

        // grpc 有时把 serviceName 写在 path 上（vmess 那套习惯），兼容一下。
        if ("grpc".equals(node.network) && empty(node.serviceName) && !empty(node.path) && !"/".equals(node.path)) {
            node.serviceName = node.path;
        }
    }

    /**
     * 只解开 %XX，**不碰 +**。
     *
     * URLDecoder 会把 + 解成空格，这在 query 里符合
     * application/x-www-form-urlencoded，但 trojan 的密码里 + 是真的加号，
     * 解成空格就连不上了。机场链接里的路径和密码基本都是 percent-encode 的。
     */
    private static String percentDecode(String value) {
        if (value == null || value.indexOf('%') < 0) return value;
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '%' && i + 2 < value.length()) {
                    int hi = Character.digit(value.charAt(i + 1), 16);
                    int lo = Character.digit(value.charAt(i + 2), 16);
                    if (hi >= 0 && lo >= 0) {
                        out.write((hi << 4) + lo);
                        i += 2;
                        continue;
                    }
                }
                out.write(String.valueOf(c).getBytes(Charset.forName("UTF-8")));
            }
            return utf8(out.toByteArray());
        } catch (Throwable e) {
            return value;
        }
    }

    /**
     * trojan://password@host:port?security=tls&sni=...&type=ws&path=/x#备注
     *
     * trojan 强制 TLS，链接里通常不写 security 字段。这里不替用户补 ——
     * 缺省按 tls 处理是在下面 parseUrlStyle 里统一做的（见 q("security") 那段）。
     */
    private static Node parseTrojan(String body) throws JSONException {
        return parseUrlStyle("trojan", body, 443);
    }


    private static Node parseVmess(String base64) throws JSONException {
        String json = utf8(base64Decode(base64));
        JSONObject raw = new JSONObject(json);
        Node node = new Node("vmess", raw.optString("add"), raw.optInt("port", 443));
        node.uuid = raw.optString("id");
        node.alterId = raw.optInt("aid", 0);
        node.security = raw.optString("scy", "auto");
        node.network = raw.optString("net", "tcp");
        node.tls = raw.optString("tls", "none");
        node.path = raw.optString("path", "/");
        node.host = raw.optString("host", "");
        // vmess 链接里 grpc 的 serviceName 就写在 path 字段上。
        node.serviceName = node.path;
        return node;
    }

    private static Node parseShadowsocks(String body) {
        // 两种写法：
        //   base64(method:password)@host:port#备注
        //   base64(method:password@host:port)#备注
        String text = body;
        int hash = text.indexOf('#');
        if (hash > 0) text = text.substring(0, hash);

        int at = text.lastIndexOf('@');
        if (at > 0) {
            return ssNode(decodeText(text.substring(0, at)), text.substring(at + 1));
        }
        String decoded = decodeText(text);
        int at2 = decoded.lastIndexOf('@');
        if (at2 <= 0) throw new IllegalArgumentException("ss 链接看不懂");
        return ssNode(decoded.substring(0, at2), decoded.substring(at2 + 1));
    }

    private static Node ssNode(String userInfo, String hostPort) {
        int colon = userInfo.indexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("ss 链接缺加密方式");
        int portSep = hostPort.lastIndexOf(':');
        if (portSep <= 0) throw new IllegalArgumentException("ss 链接缺端口");
        int port;
        try {
            port = Integer.parseInt(hostPort.substring(portSep + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ss 链接端口不是数字");
        }
        Node node = new Node("shadowsocks", hostPort.substring(0, portSep), port);
        node.method = userInfo.substring(0, colon);
        node.uuid = userInfo.substring(colon + 1);
        return node;
    }

    private static String decodeText(String value) {
        try {
            return utf8(base64Decode(value));
        } catch (RuntimeException e) {
            // 不是 base64 就说明本来就是明文（RFC 1929 之前的写法）
            return value;
        }
    }

    /**
     * java.util.Base64 是 API 26+，minSdk 正好 26。单测里 JDK 自带，
     * 也不用引 android.util.Base64 —— 那玩意在 JVM 单测里是空壳。
     */
    private static byte[] base64Decode(String value) {
        String text = value.trim();
        // 分享链接里的 base64 常常不补 padding，标准解码器会直接拒。
        while (text.length() % 4 != 0) text = text + "=";
        return java.util.Base64.getDecoder().decode(text.replace('-', '+').replace('_', '/'));
    }

    private static String utf8(byte[] raw) {
        return new String(raw, Charset.forName("UTF-8"));
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    private static void require(String value, String message) {
        if (empty(value)) throw new IllegalArgumentException(message);
    }

    static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 把 JSONObject 摊成「路径 → 值」的有序 Map，测试断言结构时用。
     * JSONObject 的迭代顺序不保证，逐层 hand-write 断言太啰嗦。
     */
    static Map<String, Object> flatten(JSONObject json) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        flatten("", json, out);
        return out;
    }

    private static void flatten(String prefix, JSONObject json, Map<String, Object> out) {
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = json.opt(key);
            if (value instanceof JSONObject) {
                flatten(path, (JSONObject) value, out);
            } else if (value instanceof JSONArray) {
                JSONArray list = (JSONArray) value;
                out.put(path + ".length", list.length());
                for (int i = 0; i < list.length(); i++) {
                    Object item = list.opt(i);
                    if (item instanceof JSONObject) {
                        flatten(path + "[" + i + "]", (JSONObject) item, out);
                    } else {
                        out.put(path + "[" + i + "]", item);
                    }
                }
            } else {
                out.put(path, value);
            }
        }
    }
}
