package com.huangguo.mobile;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML / 文本工具 —— 八个适配器各抄一份的那些函数的唯一实现。
 *
 * ── 为什么值得抽 ────────────────────────────────────────────────────
 * 2026-10-18 量过一次：`stripHtml` 在 5 个适配器里各写一遍，`enc` 8 遍，
 * `param` 3 遍（Porn91 / Mrds66 / Yhdm **逐字相同**，md5 一致）。
 * 合计约 1200 行纯管道代码。
 *
 * 行数是小事，**行为漂移**是大事：`percentDecode` 的中文解码在 Mrds66 里
 * 修过一次 bug（按 char 拼字节 → 「å]ç」乱码），后来接 Yhdm 是照抄对的。
 * 抄错一次，症状和「站点改版」完全一样：搜中文搜不到、标签全空。
 *
 * ── 抽什么，不抽什么 ────────────────────────────────────────────────
 * 抽的是「跟站点无关的字符串操作」：
 *   取标签文字、反转义、URL 编码解码、从查询串取参数、相对地址拼绝对地址
 *
 * **不抽**「怎么从一个站的 DOM 里认出卡片」—— 那是站点知识，正则比选择器
 * 更短更快也更好调。抽象到此为止。
 */
final class HtmlKit {

    private HtmlKit() {}

    // ── 标签与实体 ────────────────────────────────────────────────────

    /** 剥标签，不处理实体（调用方按需 {@link #unescape}）。 */
    static String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", " ");
    }

    /**
     * 常见实体。
     *
     * 除了标准 HTML 实体，还要处理两种「JSON 塞进 JS 字符串」留下的转义：
     * {@code \u0026} 和 {@code \/}。porn91 和 hanime1 的页面里都有 ——
     * 那不是 HTML 实体，是站点把 JSON 直接内联进 &lt;script&gt; 时产生的，
     * 不处理就会在标题里看见「Tom \u0026 Jerry」。
     */
    static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#039;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
                .replace("\\/", "/");
    }

    /**
     * 只删标签，<b>不插空格</b>，收尾 trim。
     *
     * 和 {@link #stripHtml} 的区别是 `""` vs `" "`：两个词被标签隔开时，
     * 插空格会把它们分开（`<b>a</b><b>b</b>` → `a b`），不插就是 `ab`。
     * 两种语义站点都用到过：卡片标题要空格（不然词粘一起），
     * 而拼接属性片段时不要空格。别混用。
     */
    static String stripTags(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "").trim();
    }

    /**
     * 剥标签 + 反转义 + 把连续空白压成一个空格。
     *
     * 这是「从一段 HTML 里取人读的文字」的完整动作，八个适配器里出现过
     * 二十多次 `unescape(stripHtml(x)).replaceAll("\\s+"," ").trim()`。
     */
    static String text(String html) {
        return unescape(stripHtml(html)).replaceAll("\\s+", " ").trim();
    }

    // ── URL ───────────────────────────────────────────────────────────

    /**
     * 相对地址拼成绝对地址。
     *
     * 三种形态都得认，这是这几个站混用的：
     * <pre>
     *   /cover2/123.jpg      以 / 开头 → 拼站点根
     *   //pic.other.com/a.jpg 协议相对 → 补 https:
     *   https://cdn/a.jpg    已经是绝对地址 → 原样
     *   ./a.jpg  a.jpg       相对当前路径 → 交给 URI.resolve
     * </pre>
     */
    static String abs(String base, String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.length() == 0) return "";
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        if (u.startsWith("//")) return "https:" + u;
        String b = base == null ? "" : base.replaceAll("/+$", "");
        if (u.startsWith("/")) return b + u;
        try {
            return new URI(b + "/").resolve(u).toString();
        } catch (Exception e) {
            return b + "/" + u;
        }
    }

    /** 从任意 URL 取小写 host。取不到返回空串。 */
    static String hostOf(String url) {
        try {
            String host = new URI(url == null ? "" : url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** 去掉末尾斜杠。 */
    static String trimSlash(String s) {
        if (s == null) return "";
        String out = s;
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    /** 双斜杠归一化：`https://a.com//x/y.jpg` → `https://a.com/x/y.jpg`。 */
    static String normalizeUrl(String url) {
        if (url == null) return "";
        String s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme < 0) return s;
        int i = scheme + 3;
        while (i < s.length() && s.charAt(i) != '/') i++;
        if (i >= s.length()) return s;
        int j = i;
        while (j < s.length() && s.charAt(j) == '/') j++;
        return s.substring(0, i) + "/" + s.substring(j);
    }

    // ── 编码 ──────────────────────────────────────────────────────────

    /**
     * URL 编码，按 RFC 3986 的 unreserved 集合（`A-Z a-z 0-9 - _ . ~`）原样保留。
     *
     * <p><b>不用 `java.net.URLEncoder`</b>，两个原因：
     * <ol>
     *   <li>它把空格编成 `+`，而路径段和查询串里 `+` 是合法字符 —— 站点会把
     *       `+` 当字面量解析（分类名里带空格的不在少数）</li>
     *   <li>它把 `~` 编成 `%7E`。`~` 在 RFC 3986 里是 unreserved，不该编码；
     *       多数服务器容忍，但有的签名校验按原样比对，编了就 403</li>
     * </ol>
     *
     * 这份实现是从 {@code Porn91Adapter} 原来的手写编码器提上来的 ——
     * 当时它是三个实现里唯一正确的那个（另两个是 URLEncoder 包一层）。
     * 2026-10-18 统一时测试当场抓到差异：`abc-._~` 编出来变成 `abc-._%7E`。
     */
    static String enc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append('%').append(String.format("%02X", b & 0xFF));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 只解 %XX，不动 `+`。
     *
     * **不用 URLDecoder.decode()** —— 那个会把 `+` 解成空格，而标签词里
     * 出现 `+` 的概率不为零（比如「C++」），解错了就查不到。
     *
     * 必须按**字节**攒，不能按 char 拼：`%E5%90%83` 是 UTF-8 的三个字节，
     * 逐个当 char 追加会把一个字符拆成三个 U+00E5/U+0090/U+0083，
     * 解出来是「å]ç」。中文标签和搜索词全走这条路，错了就全废。
     */
    static String percentDecode(String raw) {
        if (raw == null || raw.length() == 0) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream(raw.length());
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    buf.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            if (buf.size() > 0) {
                sb.append(new String(buf.toByteArray(), StandardCharsets.UTF_8));
                buf.reset();
            }
            sb.append(c);
        }
        if (buf.size() > 0) {
            sb.append(new String(buf.toByteArray(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ── 内部动作路径的参数 ────────────────────────────────────────────

    /**
     * 从 `/api/x?a=1&b=2` 取参数（自动 percentDecode）。
     *
     * 前缀写 `(?:^|[?&])` 而不是 `[?&]`：调用方有时传整条动作路径，有时直接传
     * 一段裸查询串（OpenList 的 `cate_id=…&page=2`）。只认 `[?&]` 的话后者
     * 永远取不到 —— 而这个失败是静默的：参数空 → 请求不发 → 页面停在上一页。
     */
    static String param(String path, String key) {
        if (path == null) return "";
        Matcher m = Pattern.compile("(?:^|[?&])" + Pattern.quote(key) + "=([^&#]*)")
                .matcher(path);
        if (!m.find()) return "";
        return percentDecode(m.group(1));
    }

    /** 从路径取页码，缺省 1，非法 1，最小 1。 */
    static int page(String path) {
        String p = param(path, "page");
        try {
            return p.length() == 0 ? 1 : Math.max(1, Integer.parseInt(p));
        } catch (Exception e) {
            return 1;
        }
    }

    // ── 正则小工具 ────────────────────────────────────────────────────

    /** 第一个捕获组，没匹配返回空串。最常用的一句，省掉四个适配器各写一遍的 m.find()。 */
    static String first(String regex, String text) {
        return first(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL), text);
    }

    static String first(Pattern pattern, String text) {
        if (text == null) return "";
        Matcher m = pattern.matcher(text);
        return m.find() ? (m.groupCount() >= 1 ? safeGroup(m, 1) : m.group()) : "";
    }

    /** 所有捕获组 1 的匹配。 */
    static List<String> all(String regex, String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text);
        while (m.find()) out.add(safeGroup(m, 1));
        return out;
    }

    private static String safeGroup(Matcher m, int index) {
        try {
            String v = m.group(index);
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /** 数一下某个模式出现几次（探针判「至少 N 条卡」时用）。 */
    static int count(String regex, String text) {
        if (text == null) return 0;
        Matcher m = Pattern.compile(regex).matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    // ── 数字 ──────────────────────────────────────────────────────────

    /** 从一段文本里取第一个整数，取不到返回 0。 */
    static int intOf(String text) {
        Matcher m = Pattern.compile("(\\d+)").matcher(text == null ? "" : text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
