package com.huangguo.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AIIvideo —— 苹果CMS **XML 接口**（`/at/xml/`）的解析。
 *
 * 它跟 JSON 那套是同一个 CMS 的两代接口，字段一一对应，只是包在 XML 里：
 *
 *   JSON                          XML
 *   list[].vod_id                 <video><id>
 *   list[].vod_name               <video><name><![CDATA[…]]>
 *   list[].type_id / type_name     <video><tid> / <type>
 *   list[].vod_pic                <video><pic>          ← **只在详情里有**
 *   list[].vod_remarks            <video><note><![CDATA[…]]>
 *   list[].vod_content            <video><des><![CDATA[…]]>
 *   list[].vod_play_from          <dl><dd flag="…">（多条）
 *   list[].vod_play_url           <dl><dd> 的内容（多条）
 *   class[].type_id / type_name   <class><ty id="…">…</ty>
 *
 * **所以这个类只做一件事：把 XML 翻译成 JSON 那套形状。**
 * 翻译完丢给 CmsAdapter，后面的映射、分集拆解、播放地址提取全部复用，
 * 不用写第二遍。这是把「两种接口」的差异收敛到一个点上的做法。
 *
 * ── 两个实测出来的坑 ──────────────────────────────────────────────
 *
 * ① **列表里没有封面。** 旧版 XML 接口的 list 只有 id/tid/name/type/dt/note，
 *    <pic> 只在详情响应里。所以：
 *      · 首页和分类页拿不到封面（这是接口的限制，不是我们的 bug）
 *      · 提取封面域名时得**单独探一次详情**，否则一个域名都提不出来
 *
 * ② **多条线路，第一条不一定是直链。** 实测某源：
 *      <dd flag="liangzi">第01集$https://v.lzcdn27.com/share/47fa1e27…#…
 *      <dd flag="lzm3u8">第01集$https://v.lzcdn27.com/20261009/…/index.m3u8#…
 *    第一条是 `/share/` 分享页（要第三方解析），第二条才是直链。
 *    所以这里把多条线路按 `$$$` 拼起来（跟 JSON 那套一致），
 *    由 CmsAdapter.splitPlayUrl 去挑一条能直接播的。
 */
final class CmsXml {

    private static final Pattern VIDEO = Pattern.compile("<video>(.*?)</video>", Pattern.DOTALL);
    private static final Pattern DD = Pattern.compile("<dd[^>]*>(.*?)</dd>", Pattern.DOTALL);
    private static final Pattern DD_FLAG = Pattern.compile("<dd[^>]*flag=\"([^\"]*)\"[^>]*>(.*?)</dd>", Pattern.DOTALL);
    private static final Pattern TY = Pattern.compile("<ty[^>]*id=\"([^\"]*)\"[^>]*>(.*?)</ty>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private CmsXml() {}

    /** 认一下是不是 XML 接口的响应。 */
    static boolean looksLikeXml(String body) {
        if (body == null) return false;
        String head = body.trim();
        if (head.length() > 200) head = head.substring(0, 200);
        return head.startsWith("<?xml") || head.startsWith("<rss") || head.startsWith("<list");
    }

    /**
     * 把 XML 响应转成 JSON CMS 的那套形状。
     * 解析不了就抛 —— 上层会把消息带给用户，不静默返回空。
     */
    static JSONObject parse(String xml) throws Exception {
        if (xml == null || xml.indexOf("<list") < 0) {
            throw new Exception("XML 响应里没有 <list>，这个接口可能不是苹果CMS 的 XML 格式");
        }
        JSONObject out = new JSONObject();
        out.put("code", 1);

        // <list page="1" pagecount="7960" pagesize="20" recordcount="159193">
        int listStart = xml.indexOf("<list");
        int listEnd = xml.indexOf('>', listStart);
        if (listStart >= 0 && listEnd > listStart) {
            Matcher m = ATTR.matcher(xml.substring(listStart, listEnd));
            while (m.find()) {
                out.put(m.group(1), intOf(m.group(2)));
            }
        }
        if (!out.has("page")) out.put("page", 1);
        if (!out.has("pagecount")) out.put("pagecount", 1);

        JSONArray videos = new JSONArray();
        Matcher vm = VIDEO.matcher(xml);
        while (vm.find()) videos.put(video(vm.group(1)));
        out.put("list", videos);

        // <class><ty id="1">电影片</ty>…</class>
        JSONArray classes = new JSONArray();
        Matcher tm = TY.matcher(xml);
        while (tm.find()) {
            JSONObject c = new JSONObject();
            c.put("type_id", intOf(tm.group(1)));
            c.put("type_name", text(tm.group(2)));
            classes.put(c);
        }
        if (classes.length() > 0) out.put("class", classes);

        return out;
    }

    private static JSONObject video(String body) {
        JSONObject v = new JSONObject();
        try {
            v.put("vod_id", intOf(tag(body, "id")));
            v.put("vod_name", tag(body, "name"));
            v.put("type_id", intOf(tag(body, "tid")));
            v.put("type_name", tag(body, "type"));
            v.put("vod_pic", tag(body, "pic"));
            v.put("vod_remarks", tag(body, "note"));
            v.put("vod_content", tag(body, "des"));
            v.put("vod_score", "");                  // XML 接口没有评分
            v.put("vod_play_from", playFlags(body));
            v.put("vod_play_url", playUrls(body));
        } catch (Exception ignored) {}
        return v;
    }

    /** <dl> 里各条 <dd flag="…"> 的 flag，多条用 $$$ 拼（跟 JSON 那套的格式一致）。 */
    private static String playFlags(String body) {
        StringBuilder sb = new StringBuilder();
        Matcher m = DD_FLAG.matcher(body);
        while (m.find()) {
            if (sb.length() > 0) sb.append("$$$");
            sb.append(m.group(1));
        }
        return sb.toString();
    }

    /** 各条 <dd> 的内容，多条用 $$$ 拼。 */
    private static String playUrls(String body) {
        StringBuilder sb = new StringBuilder();
        Matcher m = DD.matcher(body);
        while (m.find()) {
            if (sb.length() > 0) sb.append("$$$");
            sb.append(text(m.group(1)));
        }
        return sb.toString();
    }

    /** 取一个标签的内容。取不到返回空串（不返回 null，上面到处在用它拼字符串）。 */
    private static String tag(String body, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(body);
        return m.find() ? text(m.group(1)) : "";
    }

    /**
     * 去掉 CDATA 包裹、解掉几个基本实体、压掉空白。
     *
     * CDATA 是必须处理的：片名和简介里全是它（`<![CDATA[你却爱着一个他]]>`），
     * 不剥掉的话界面上显示的就是「<![CDATA[…]]>」。简介里还常有成片的
     * 全角空格，一并压掉。
     */
    private static String text(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("<![CDATA[")) {
            int end = s.lastIndexOf("]]>");
            s = end > 9 ? s.substring(9, end) : s.substring(9);
        }
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
             .replace("&apos;", "'").replace("&amp;", "&");
        // 全角空格和连续空白：简介里常有一大段缩进用的
        s = s.replace('\u3000', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{2,}", "\n");
        return s.trim();
    }

    private static int intOf(String value) {
        try { return Integer.parseInt(value.trim()); } catch (Exception e) { return 0; }
    }
}
