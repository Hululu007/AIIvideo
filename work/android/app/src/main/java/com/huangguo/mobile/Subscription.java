package com.huangguo.mobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 订阅下载与解析。
 *
 * 「订阅」在网络代理这个圈子里指的是**一个装着很多节点的文本**，格式有三种：
 *
 *   1. 裸节点列表   每行一条 vmess:// / vless:// / trojan:// / ss://
 *   2. base64 包着  整段 base64，解开之后就是上面那种
 *   3. Clash YAML   `proxies:` 下的一串映射
 *
 * 前两种占绝大多数，这里都支持；Clash YAML **不做**，理由见 parse()。
 *
 * ── 为什么不用现成的订阅转换服务 ──
 * 那些服务（subconverter 之类）要跑一个常驻进程，而且会把节点地址过一遍
 * 别人的服务器。下载 + 解析在本地做完，既没有额外依赖，也不用把用户的
 * 节点交给第三方。
 */
final class Subscription {

    /** 单份订阅最多收这么多条。防止有人塞个几万行的文件把 App 卡死。 */
    private static final int MAX_NODES = 400;

    /** 下载上限。正常的订阅都在 200KB 以内，给到 4MB 已经很宽松。 */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private Subscription() {}

    /** 解析结果：成功的节点 + 失败条数与原因摘要。 */
    static final class Result {
        final List<XrayConfig.Node> nodes = new ArrayList<XrayConfig.Node>();
        /** 原样的分享链接，和 nodes 一一对应。存原文是因为「再启动一次」要用它。 */
        final List<String> links = new ArrayList<String>();
        int failed;
        /** 认得协议名但不支持的（hysteria2 / tuic / ssr 之类）。单独计数，
         *  因为这不是「格式坏了」，是「这个协议我们没实现」—— 提示语不一样。 */
        int unsupported;
        /** 不支持的协议名，用于提示（去重，最多几个）。 */
        final java.util.Set<String> unsupportedNames = new LinkedHashSet<String>();
        String firstError;

        int size() {
            return nodes.size();
        }
    }

    /**
     * 下载并解析。
     *
     * 用 AiiHttp.getRaw 而不是自己开连接：下载订阅这件事该走用户配的代理
     * （很多人订阅地址本身就要翻墙才能访问），而 AiiHttp 已经带着 effectiveProxy。
     * 走同一个客户端也省得再处理一遍 DoH / 重试 / 用户代理。
     */
    /**
     * 内置的免费节点，打进 APK 的 assets/free-nodes.json。
     *
     * 这是「没机场的人」的默认路径 —— 他们不需要知道订阅是什么，进设置页
     * 点一下测速就行。文件由维护者人工更新，格式见文件里的 _note。
     *
     * 为什么不用 assets 里直接塞原始订阅文本：那样维护的人得懂 base64，
     * 而且改一条要重算整个编码。JSON 数组是所有人都能直接改的形态。
     */
    static Result builtin(Context context) {
        Result empty = new Result();
        try {
            String text = new String(
                    HotUpdateManager.read(context.getAssets().open(BUILTIN_ASSET), MAX_BYTES),
                    StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            JSONArray links = root.optJSONArray("links");
            if (links == null) {
                empty.firstError = "内置节点文件里没有 links 数组";
                return empty;
            }
            StringBuilder joined = new StringBuilder(links.length() * 120);
            for (int i = 0; i < links.length(); i++) {
                String line = links.optString(i, "").trim();
                if (line.length() > 0) joined.append(line).append('\n');
            }
            // 直接复用 parse：去重、逐条校验、统计失败数都是现成的。
            // 这样「内置」和「订阅」走的是同一套解析，不会出现两份行为不一致。
            return parse(joined.toString());
        } catch (Throwable e) {
            String m = e.getMessage();
            empty.firstError = m == null || m.isEmpty()
                    ? "内置节点读不出来（" + e.getClass().getSimpleName() + "）" : m;
            return empty;
        }
    }

    /** 内置节点文件的资产名。 */
    static final String BUILTIN_ASSET = "free-nodes.json";

    static Result fetch(String url, int timeoutSeconds) throws Exception {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("订阅地址是空的");
        }
        String target = url.trim();
        String lower = target.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("订阅地址要以 http:// 或 https:// 开头");
        }

        byte[] body = AiiHttp.getOnce(target, timeoutSeconds);
        if (body == null || body.length == 0) {
            throw new Exception("订阅返回空内容");
        }
        if (body.length > MAX_BYTES) {
            throw new Exception("订阅太大了（" + (body.length / 1024) + " KB），可能是网页而不是订阅");
        }
        return parse(new String(body, StandardCharsets.UTF_8));
    }

    /**
     * 解析一段订阅文本。
     *
     * ── 为什么不做 Clash YAML ──
     * Android 上没有 yaml 解析器，为这个引一个依赖不划算；而 Clash 格式的
     * 节点和 base64 格式通常是**同一批**（同一个机场两种导出），拿 base64
     * 那份就够。真遇到只给 YAML 的源，让用户改用它的「V2Ray 订阅」链接即可。
     *
     * 所以这里只认两件事：行分隔的节点链接，以及「整段 base64」。
     */
    static Result parse(String raw) {
        Result result = new Result();
        if (raw == null) return result;

        String text = raw.trim();
        if (text.isEmpty()) return result;

        // Clash 的 YAML：能认出来但明确说不支持，比让它当成「0 个节点」好 ——
        // 后者用户会以为是订阅坏了。
        if (looksLikeClash(text)) {
            result.firstError = "这是 Clash 配置（YAML），请在机场后台改用「V2Ray 订阅」链接";
            return result;
        }

        // 整段 base64：解开后如果出现协议前缀，就当节点列表重走一遍。
        if (!containsScheme(text)) {
            String decoded = tryDecodeBase64(text);
            if (decoded != null && containsScheme(decoded)) {
                text = decoded;
            } else if (decoded == null) {
                result.firstError = "内容既不是节点列表也不是 base64";
                return result;
            }
        }

        // 去重：同一份订阅里重复节点很常见（多个分组会互相包含）。
        Set<String> seen = new LinkedHashSet<String>();
        for (String line : text.split("\\r?\\n")) {
            if (result.nodes.size() >= MAX_NODES) break;
            String item = line.trim();
            if (item.isEmpty() || item.startsWith("#") || item.startsWith("//")) continue;
            if (!looksLikeLink(item)) {
                // 认得出协议名但不在支持列表里（hysteria2 / tuic / ssr …）。
                // 静默丢掉会让用户以为「我明明有 47 个节点，怎么只出来 23 个」。
                String scheme = schemeOf(item);
                if (scheme != null) {
                    result.unsupported++;
                    if (result.unsupportedNames.size() < 3) result.unsupportedNames.add(scheme);
                }
                continue;
            }
            if (!seen.add(item)) continue;
            try {
                XrayConfig.Node node = XrayConfig.parseShareLink(item);
                // 解析出来还要能生成配置，否则是「解析器认得但 Xray 吃不下」的畸形节点。
                XrayConfig.buildString(node, 20480);
                result.nodes.add(node);
                result.links.add(item);
            } catch (Throwable e) {
                result.failed++;
                if (result.firstError == null) {
                    String m = e.getMessage();
                    result.firstError = m == null || m.isEmpty()
                            ? e.getClass().getSimpleName() : m;
                }
            }
        }
        return result;
    }

    private static boolean containsScheme(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("vmess://") || t.contains("vless://")
                || t.contains("trojan://") || t.contains("ss://")
                || t.contains("socks5://") || t.contains("http://");
    }

    /** `xxx://...` 里的 `xxx`，取不到返回 null。 */
    private static String schemeOf(String line) {
        int mark = line.indexOf("://");
        if (mark <= 0 || mark > 20) return null;
        String scheme = line.substring(0, mark).toLowerCase(Locale.ROOT);
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')) return null;
        }
        return scheme;
    }

    private static boolean looksLikeLink(String line) {
        String t = line.toLowerCase(Locale.ROOT);
        return t.startsWith("vmess://") || t.startsWith("vless://")
                || t.startsWith("trojan://") || t.startsWith("ss://")
                || t.startsWith("socks5://") || t.startsWith("http://")
                || t.startsWith("https://");
    }

    private static boolean looksLikeClash(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("proxies:") || t.contains("proxy-groups:")
                || t.contains("mixed-port:") || t.contains("rules:");
    }

    /**
     * 试着按 base64 解。解不出或解出来不是文本就返回 null。
     *
     * 不抛异常：这个函数是「猜」的，猜不中很正常，调用方据此决定下一步。
     */
    private static String tryDecodeBase64(String text) {
        try {
            String compact = text.replaceAll("\\s", "");
            while (compact.length() % 4 != 0) compact = compact + "=";
            byte[] raw = java.util.Base64.getDecoder()
                    .decode(compact.replace('-', '+').replace('_', '/'));
            String decoded = new String(raw, StandardCharsets.UTF_8);
            // 解出来一堆替换字符说明这根本不是 base64 文本
            if (decoded.indexOf('\uFFFD') >= 0) return null;
            return decoded;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 给 UI 看的一句话摘要。 */
    static String summary(Result result) {
        if (result == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(result.size()).append(" 个节点");
        if (result.failed > 0) sb.append("，").append(result.failed).append(" 个看不懂被跳过");
        if (result.unsupported > 0) {
            sb.append("，").append(result.unsupported).append(" 个不支持（");
            boolean first = true;
            for (String name : result.unsupportedNames) {
                if (!first) sb.append("/");
                sb.append(name);
                first = false;
            }
            sb.append("）");
        }
        return sb.toString();
    }

    static List<XrayConfig.Node> emptyList() {
        return Collections.emptyList();
    }
}
