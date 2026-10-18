package com.huangguo.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 播放层的 HTTP 出口 —— `__http:get|post?payload=<urlencode 后的 JSON>`。
 *
 * ── 为什么要开这个口子 ──────────────────────────────────────────────
 * 页面 CSP 的 connect-src 是 'none'，网络一律走 Java —— 这是刻意的收紧，
 * 所以这个出口也按同样的调子收着：**只放行 https，且主机必须是当前源自己
 * 声明的那些**。
 *
 * 触发原因是 CORS 而不是 CSP：播放层的解析接口要带 Video-Parse-Sign 那几个
 * 自定义头，对面的预检响应只回 access-control-allow-origin，没有
 * allow-headers，浏览器根本不会把请求发出去。OkHttp 不受同源策略管，
 * 借这条桥转一手就绕开了。
 *
 * ── 白名单为什么在配置里（2026-10-18 改） ───────────────────────────
 * 原来这里是两行硬编码：
 *
 * <pre>
 * private static final String[] HTTP_HOSTS = {
 *     "jx.wuzhoupai.com",   // AGE动漫 的播放解析
 *     "xcdn.khfed.com",     // 它的播放器资源
 * };
 * </pre>
 *
 * 问题不是这两行写在哪个文件，而是**它是 Java 里的一条站点知识**：换一个源、
 * 或者同一个源换了播放线路，就得改代码、重新发版。而这类「这个源允许访问哪些
 * 域名」的知识，封面那条路早就放进配置了（{@code cover.allowedHosts}，精确
 * 匹配、不支持通配符）。这面墙没理由和它长得不一样。
 *
 * 现在由适配器在 {@code http.hosts} 里声明，{@link SiteProfile} 构造时把当前
 * 源那份交给这里。**没声明就是一张空表** —— 空表一律拒绝，报错里直接说缺什么，
 * 而不是悄悄放行两个和当前源无关的域名。
 */
final class PlaybackHttpBridge {

    private PlaybackHttpBridge() {}

    /** 当前源声明的出口主机（小写）。空表 = 这个源没有播放层出口。 */
    private static volatile Set<String> allowed = Collections.emptySet();

    /**
     * 装载一个源的播放层出口声明。{@link SiteProfile} 构造时调，和
     * {@link NetRoute#configure} 同一个位置 —— 那也是全 App 唯一一处
     * 「当前源长什么样」的组装点。
     *
     * @param http 源配置里的 {@code http} 块，可以为 null（null = 不声明 = 全拒）
     */
    static void configure(JSONObject http) {
        Set<String> next = hostsOf(http);
        allowed = next;
        // 打一行日志。这面墙最容易出的问题不是「拦错了」，而是「悄悄拦了」——
        // 用户看到的是播放层取不到地址，日志里什么都没有，只能去猜配置。
        UserSource.log(next.isEmpty()
                ? "播放层出口：这个源没有声明 http.hosts，一律拒绝"
                : "播放层出口 " + next.size() + " 个域名：" + next);
    }

    /**
     * 从 {@code {"hosts": [...]}} 里取出小写主机表。纯函数，可单测。
     *
     * 空串和空白会被丢掉：手写配置里留个空行不该变成「允许访问空主机名」。
     */
    static Set<String> hostsOf(JSONObject http) {
        if (http == null) return Collections.emptySet();
        JSONArray list = http.optJSONArray("hosts");
        if (list == null || list.length() == 0) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<String>();
        for (int i = 0; i < list.length(); i++) {
            String h = list.optString(i, "").trim().toLowerCase(Locale.ROOT);
            if (h.length() > 0) out.add(h);
        }
        return Collections.unmodifiableSet(out);
    }

    static JSONObject handle(String body, JSONObject out) {
        try {
            String query = body;
            int mark = body.indexOf('?');
            if (mark >= 0) query = body.substring(mark + 1);
            String payload = UserSource.param(query, "payload");
            if (payload.length() == 0) return UserSource.fail(out, "HTTP 指令没有 payload");

            JSONObject req = new JSONObject(payload);
            String url = req.optString("url", "");
            if (!url.startsWith("https://")) return UserSource.fail(out, "只放行 https：" + url);
            String host = URI.create(url).getHost();
            if (host == null || host.length() == 0) return UserSource.fail(out, "地址里没有主机名：" + url);

            Set<String> hosts = allowed;
            if (hosts.isEmpty()) {
                return UserSource.fail(out,
                        "当前源没有声明播放层出口（http.hosts），不放行任何域名：" + host);
            }
            if (!hosts.contains(host.toLowerCase(Locale.ROOT))) {
                return UserSource.fail(out, "主机不在播放层白名单里：" + host);
            }

            java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
            JSONObject head = req.optJSONObject("headers");
            if (head != null) {
                for (Iterator<String> it = head.keys(); it.hasNext(); ) {
                    String k = it.next();
                    headers.put(k, head.optString(k, ""));
                }
            }

            byte[] raw = body.startsWith("post")
                    ? AiiHttp.postFormRaw(url, req.optString("body", ""), headers, 15)
                    : AiiHttp.getRaw(url, headers, 15);

            out.put("ok", true);
            out.put("body", new String(raw, "UTF-8"));
            return out;
        } catch (Throwable e) {
            return UserSource.fail(out, "HTTP 失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }
}
