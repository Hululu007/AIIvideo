package com.huangguo.mobile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 把 libVLC 的取流请求接到我们自己的 OkHttp 上，走内置代理。
 *
 * ── 为什么需要这个 ────────────────────────────────────────────────────
 * 内置核心起来的 SOCKS5 只在 Java 侧对 OkHttp 生效。libVLC 是原生栈，
 * 它的取流不走 OkHttp，所以核心开着时视频流仍然是直连的 —— 表现是
 * 「列表能刷出来、点开播不了」，因为接口走了代理而 CDN 没有。
 *
 * 原本以为加个 media option 就完事，实测两条都不行（2026-10-18 真机）：
 *
 *   · `:socks=host:port`      字符串在 .so 里确实有，但挂的是 Live555 的
 *                             RTSP 栈（SocksNegotiate 上下文全是
 *                             OnDemandServerMediaSubsession / RTPSink）。
 *                             播放时 SOCKS 端口零连接，视频直连出画。
 *   · `:http-proxy=host:port` 同样零连接。用假代理（只记日志不转发）验过，
 *                             连上去的请求一个都没有。
 *   · 全局 `--socks=`         放在 new LibVLC(options) 里，一样无效。
 *
 * 所以 VLC 本身没法被配置成走代理。能让它走代理的唯一办法是**换地址**：
 * 给它一个本机 HTTP 地址，那个地址背后是我们的 OkHttp。
 *
 * ── 怎么接 ────────────────────────────────────────────────────────────
 *
 *   VLC ──HTTP──> 127.0.0.1:<port>/s?u=<base64 的原始地址>
 *                        │
 *                        └─ OkHttp（带 effectiveProxy）──> 原站/CDN
 *
 * 选这个形状而不是「透传原始路径」，是因为 VLC 会自己拼相对地址（m3u8 的
 * 分片就是相对主播放列表解析的）。把原始 URL 整个编码进 query，VLC 无论
 * 怎么拼都只会拼出 `/s?u=...` 这一种形状，我们不用维护任何映射表。
 *
 * ── Range 必须原样透传 ────────────────────────────────────────────────
 * 媒体栈靠 Range 做 seek 和拖进度。抽掉的话要么整集从头读，要么直接判
 * 「不支持」。这里上下行都原样搬：请求头 Range 转给 OkHttp，响应的
 * 206 / Content-Range / Content-Length 原样写回。
 *
 * 实测（2026-10-18）：VLC 播本地 HTTP 地址时会带 `Range: bytes=0-` 来请求，
 * 并且能正常出画（decoder output 1280x720）。所以这条路是通的。
 */
final class VlcRelay {

    private static final String TAG_PREFIX = "[relay] ";
    private static final String PATH = "/s";

    /** 单条连接的读写缓冲。媒体流是顺序大块，64KB 比默认 8KB 少很多系统调用。 */
    private static final int BUF = 65536;

    /** 只在没有更多数据可写时用的空闲超时；活着的流不会触发。 */
    private static final int IDLE_MS = 30000;

    private static volatile VlcRelay instance;

    private final ServerSocket server;
    private final int port;
    private volatile boolean closed;
    private final AtomicReference<Thread> acceptor = new AtomicReference<Thread>();

    private VlcRelay(ServerSocket server, int port) {
        this.server = server;
        this.port = port;
    }

    /**
     * 起一个只监听回环的中继。已经起来就直接复用。
     *
     * 绑 127.0.0.1 而不是通配地址：这个是**无鉴权的开放代理**，绑到 0.0.0.0
     * 等于同网段任何人拿它当跳板，而且流量会从用户的代理节点出去。
     */
    static VlcRelay ensure() throws IOException {
        VlcRelay local = instance;
        if (local != null && !local.closed) return local;
        synchronized (VlcRelay.class) {
            if (instance != null && !instance.closed) return instance;
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            VlcRelay relay = new VlcRelay(socket, socket.getLocalPort());
            relay.start();
            instance = relay;
            log("中继已启动 127.0.0.1:" + relay.port);
            return relay;
        }
    }

    static void shutdown() {
        VlcRelay local = instance;
        if (local == null) return;
        instance = null;
        local.closed = true;
        try {
            local.server.close();
        } catch (Throwable ignored) {
        }
        log("中继已停止");
    }

    int port() {
        return closed ? 0 : port;
    }

    /**
     * 把原始播放地址包成中继地址。中继没起来时返回 null，调用方退回原始地址
     * （直连，和没有内置核心时的行为一致）。
     *
     * 判断根据是 effectiveProxy() 而不是「中继在不在跑」：代理串为空就说明
     * 核心没开（或者是直连/外部地址模式），这时候绕中继纯属多一跳。
     * 这样即使某处漏了 shutdown()，核心一停中继自然就没人用了。
     */
    static String wrap(String originalUrl) {
        if (originalUrl == null || originalUrl.length() == 0) return null;
        if (AiiHttp.effectiveProxy().length() == 0) return null;
        // 本地回环地址不包一层：一是没意义，二是会绕出环来。
        if (originalUrl.startsWith("http://127.0.0.1:") || originalUrl.startsWith("http://localhost")) {
            return null;
        }
        try {
            VlcRelay relay = ensure();
            if (relay.port() <= 0) return null;
            return "http://127.0.0.1:" + relay.port() + PATH
                    + "?u=" + encode(originalUrl);
        } catch (Throwable e) {
            log("包地址失败，退回直连：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    // ── base64url，不带 padding ───────────────────────────────────────
    //
    // 用 base64url 而不是 URLEncoder：后者把每个非 ASCII 字节展开成 %XX（三倍长），
    // 而且中文路径/%2B 这类会来回转义。base64url 字母表里没有 + / =，
    // 放进 query 不需要再编码，VLC 自己拼相对地址也不会踩到。

    private static String encode(String s) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String s) {
        try {
            return new String(java.util.Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    // ── 接客 ──────────────────────────────────────────────────────────

    private void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "vlc-relay");
        t.setDaemon(true);
        acceptor.set(t);
        t.start();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket client = null;
            try {
                client = server.accept();
                handle(client);
            } catch (Throwable e) {
                if (closed) return;
                // 单条连接出错不该把整个中继带走。
                closeQuietly(client);
            }
        }
    }

    private void handle(final Socket client) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                serve(client);
            }
        }, "vlc-relay-conn");
        t.setDaemon(true);
        t.start();
    }

    private void serve(Socket client) {
        Response response = null;
        try {
            client.setSoTimeout(IDLE_MS);
            client.setTcpNoDelay(true);

            InputStream in = new BufferedInputStream(client.getInputStream(), BUF);
            OutputStream out = new BufferedOutputStream(client.getOutputStream(), BUF);

            String requestLine = readLine(in);
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeSimple(out, 400, "Bad Request");
                return;
            }
            String method = parts[0];
            String target = parts[1];

            // 头部要读完，否则 next request 会错位；同时把 Range 留下来。
            Map<String, String> headers = new LinkedHashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }

            if (!target.startsWith(PATH + "?")) {
                writeSimple(out, 404, "Not Found");
                return;
            }

            String query = target.substring(PATH.length() + 1);
            String encoded = param(query, "u");
            String original = encoded == null ? null : decode(encoded);
            if (original == null || original.length() == 0) {
                writeSimple(out, 400, "Bad target");
                return;
            }

            if ("HEAD".equalsIgnoreCase(method)) {
                // VLC 有时先探一次。发一个不带 body 的 GET 拿状态和长度。
                Request.Builder probe = new Request.Builder().url(original).head();
                applyHeaders(probe, headers);
                response = MediaProxy.relayClient().newCall(probe.build()).execute();
                writeHead(out, response);
                return;
            }

            Request.Builder builder = new Request.Builder().url(original).get();
            applyHeaders(builder, headers);
            response = MediaProxy.relayClient().newCall(builder.build()).execute();

            writeHead(out, response);

            ResponseBody body = response.body();
            if (body != null) {
                InputStream src = body.byteStream();
                byte[] buf = new byte[BUF];
                int n;
                while ((n = src.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    // 不每块 flush：BufferedOutputStream 满 64KB 自己吐，
                    // 每块 flush 会把吞吐砍一半。VLC 是流式读，能自己等着。
                }
                out.flush();
            }
        } catch (Throwable e) {
            // 用户播完退出、拖进度、VLC 主动断连都会走到这里，属于正常。
            // 只记异常类型，别把 message 也写出去（可能含签名参数）。
            log("连接结束：" + e.getClass().getSimpleName());
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable ignored) {
                }
            }
            closeQuietly(client);
        }
    }

    /**
     * 透传给上游的头。
     *
     * 只搬媒体栈真正用得上的：Range 是播放和 seek 的命根子；UA/Referer 有些
     * CDN 会校验（VlcPlayerActivity 已经把它们塞进 payload，这里一并转过去）。
     * 其余（Accept-Encoding 之类）不搬 —— 我们要原样字节，不要上游压过的。
     */
    private static void applyHeaders(Request.Builder builder, Map<String, String> headers) {
        String range = headers.get("range");
        if (range != null && range.length() > 0) builder.header("Range", range);

        String ua = headers.get("user-agent");
        if (ua != null && ua.length() > 0) builder.header("User-Agent", ua);

        String referer = headers.get("referer");
        if (referer != null && referer.length() > 0) builder.header("Referer", referer);

        // 媒体栈要求原样字节（identity;q=1, *;q=0），别让上游压。
        builder.header("Accept-Encoding", "identity");
    }

    /**
     * 写状态行 + 响应头。
     *
     * 200 和 206 都必须能把 Content-Length 带过去 —— VLC 靠它算总时长和
     * seek 范围。206 还要 Content-Range，少了它拖动进度会跳到错误位置。
     */
    private static void writeHead(OutputStream out, Response response) throws IOException {
        int code = response.code();
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(code).append(' ')
                .append(response.message() == null || response.message().length() == 0
                        ? reason(code) : response.message())
                .append("\r\n");

        append(head, "Content-Type", response.header("Content-Type"));
        append(head, "Content-Length", response.header("Content-Length"));
        append(head, "Content-Range", response.header("Content-Range"));
        append(head, "Accept-Ranges", response.header("Accept-Ranges"));
        append(head, "Last-Modified", response.header("Last-Modified"));
        append(head, "ETag", response.header("ETag"));

        // 长度未知时必须用 chunked，否则 VLC 会一直等我们声明的 body。
        if (response.header("Content-Length") == null && code != 204 && code != 304) {
            head.append("Transfer-Encoding: chunked\r\n");
        }
        head.append("Connection: close\r\n\r\n");

        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void append(StringBuilder sb, String name, String value) {
        if (value == null || value.length() == 0) return;
        sb.append(name).append(": ").append(value).append("\r\n");
    }

    private static void writeSimple(OutputStream out, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            default: return "Status " + code;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 8192) return null;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String param(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }

    private static void log(String message) {
        try {
            android.util.Log.d("AIIvideo", TAG_PREFIX + message);
        } catch (Throwable ignored) {
        }
    }
}
