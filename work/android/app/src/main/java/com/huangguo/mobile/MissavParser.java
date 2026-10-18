package com.huangguo.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * missav.live 纯 HTML/JS 字符串解析器。
 *
 * ── 为什么单独抽一个「不碰网络、不碰 Android」的类 ──────────────────────
 * missav 是纯服务端渲染站（没有 /api/* JSON 口），所有数据都在 HTML 文本里。
 * 而这个站最脆的一块是**播放器里那段 packed JS**：站点换模板时最容易改的就是它，
 * 改坏了表现为「详情能打开、播放一直转圈」，而这种故障在真机上看日志极难定位。
 * 所以这里把解析全部做成纯静态方法（String in → JSON out），零 Android 依赖，
 * 单元测试 / 本地 main 都能直接喂真实 HTML 快照跑。
 *
 * ── 本类不认识网络，也不该认识 ──────────────────────────────────────
 * 抓页面是 {@code MissavSite}/adapter 的事。这里只做字符串→JSON，
 * 因此**任何一条正则失配都只允许让单个字段变空字符串，不允许抛异常** ——
 * 一个字段解析失败把整页列表打成 500，是最坏的失败模式。
 *
 * ── 公开方法一览（输入 / 输出）──────────────────────────────────────
 *   parseList(String html)        → JSONArray，每项 {id,title,cover,duration,code}
 *   parseCategories(String html)   → JSONArray，每项 {id,name,count}
 *   parseDetail(String html)       → JSONObject 单条详情（不含 playUrl 依赖的签名）
 *   parsePlay(String html)         → JSONObject {playUrl,qualities,duration}
 *   extractIdFromUrl(String url)   → String，视频 id（"fct-210"）
 *   extractCode(String title)      → String，番号（"FCT-210"）
 *   decodeEntities(String s)       → String，HTML 实体解码（公开给上层复用）
 *   selfTest()                     → String，一段自测输出，直接 main 跑
 */
final class MissavParser {

    private MissavParser() {
    }

    // ─────────────────────────────────────────────────────────────
    // 列表页 / 搜索页
    // ─────────────────────────────────────────────────────────────

    /**
     * 卡片外层容器。外层栅格 div 一页只有一个，卡片 div 是 N 个，所以用它切卡片。
     * 写成 "thumbnail" 而不是整段 class 字面量：站点给缩略图容器加过一堆
     * utility class（group / relative / aspect-w-16），锁死 class 列表等于把
     * 一次模板改版变成「首页全空」。只要 class 里出现 thumbnail 就算命中。
     * <p><b>class 前面必须允许别的属性</b>（2026-10-18 实测踩到）：真实卡片是
     * <pre>
     *   &lt;div @mouseenter="setPreview('68d4…')" @mouseleave="setPreview()"
     *        @click="clickPreview('68d4…')" class="thumbnail group"&gt;
     * </pre>
     * Alpine.js 的指令属性排在 class 前面。所以这里用 {@code <div\b[^>]*class=} 而不是
     * {@code <div\s+class=} —— 后者在合成 fixture 上过得很漂亮，喂真实页面却<b>一张卡都
     * 解析不出来</b>（列表页直接返回空数组）。教训：卡片的切分锚点不要假设 class 是第一个
     * 属性，站点模板里插一段 Alpine 指令就够了。
     *
     * <p>{@code \bthumbnail\b} 加了词边界：站点 CSS 里有一堆 {@code taboola-thumbnails-container}
     * 这类广告类名，里面也含 thumbnail 子串。
     */
    private static final Pattern P_CARD_SPLIT =
            Pattern.compile("<div\\b[^>]*\\bclass\\s*=\\s*\"[^\"]*\\bthumbnail\\b[^\"]*\"",
                    Pattern.CASE_INSENSITIVE);

    /** 详情页链接。取 href 值 —— id 从这里推，比从 alt 推可靠（alt 有时是标题全文）。 */
    private static final Pattern P_HREF =
            Pattern.compile("href\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);

    /** 图片地址。src 优先（实测 src 和 data-src 同值，src 一定有）；双引号/单引号/无引号都吃。 */
    private static final Pattern P_IMG_SRC =
            Pattern.compile("<img[^>]*\\ssrc\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);

    /** alt 属性，用来兜底标题（以及番号）。 */
    private static final Pattern P_ALT =
            Pattern.compile("alt\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);

    /**
     * 时长 `1:16:25` / `45:30`。
     * 要求分钟/秒是两位以上数字，且整体锚在 span 文本上：
     * <span class="absolute bottom-1 right-1 …">1:16:25</span>
     * 之所以单独限制 "绝对长度 {7,}" 之类不写，是因为卡片里唯一冒号就是时长；
     * 但为了不让正文里的 "FCT-210: 无码" 误命中，要求前面不是 ASCII 字母/数字。
     */
    private static final Pattern P_DURATION =
            Pattern.compile("(?<![0-9A-Za-z])((?:\\d{1,2}:)?\\d{1,2}:\\d{2})(?![0-9])");

    /** 番号：FCT-210 / ABC-123 / 1pondo-123456 这种「字母+连字符+数字」形态。 */
    private static final Pattern P_CODE =
            Pattern.compile("(?<![0-9A-Za-z])([A-Za-z]{2,10}[-]{1}[0-9]{2,7})(?![0-9A-Za-z])");

    /**
     * 解析列表页 / 分类页 / 搜索页（三种页面形状一致，都是 div.thumbnail）。
     *
     * @param html 完整页面 HTML，可为 null / 空
     * @return JSONArray，元素 {id,title,cover,duration,code}；一张卡都解析不出来时返回**空数组**
     *         （不抛异常，由上层判断「这个源没内容」还是「解析失效」）
     */
    static JSONArray parseList(String html) {
        JSONArray out = new JSONArray();
        if (html == null || html.isEmpty()) return out;
        try {
            Matcher m = P_CARD_SPLIT.matcher(html);
            // 先记下每张卡片 <div …thumbnail…> 之后的字符位置（= 卡片正文起点）。
            // 分两次遍历而不是在一次 find 循环里用 m.end()：那个值在循环外是
            // 「最后一个匹配」的终点，会把所有卡片都切到同一起点，列表只剩一张卡。
            List<Integer> starts = new ArrayList<Integer>();
            while (m.find()) starts.add(m.end());
            for (int i = 0; i < starts.size(); i++) {
                int from = starts.get(i);
                // 卡片结束位置：下一个 thumbnail 卡片起点；最后一张吃到 html 末尾。
                // 不能按 div 配对数括号 —— div 嵌套太深且属性里可能带 >，配对必然错。
                int to = (i + 1 < starts.size()) ? starts.get(i + 1) : html.length();
                if (to <= from) continue;
                JSONObject item = parseOneCard(html.substring(from, to));
                if (item != null && item.optString("id").length() > 0) out.put(item);
            }
        } catch (Exception ignored) {
            // 兜底：正则层面任何异常都不能让整页失败
        }
        return out;
    }

    /**
     * 解析单张卡片（已切出的一小段 HTML）。
     * 返回 null 表示这一段里找不到详情链接（不是卡片），调用方跳过。
     */
    private static JSONObject parseOneCard(String card) {
        String href = firstAttr(card, P_HREF);
        String id = extractIdFromUrl(href);
        if (id.isEmpty()) return null;

        JSONObject o = new JSONObject();
        put(o, "id", id);

        // 封面：img 的 src 或 data-src。
        //
        // ★ 判据不是「src 为空」，而是「src 是不是一张真图」★（2026-10-18 实测踩到）：
        // 站点用 lozad 懒加载，第 8 张及之后的卡片 src 是 1×1 的 base64 占位图 ——
        //   src="data:image/png;base64,iVBORw0KGgo..."   ← 有值，但不是封面
        //   data-src="https://fourhoi.com/pacopacomama-101726_100/cover-t.jpg"  ← 这才是
        // 原来的写法是「src 为空才试 data-src」，于是那 7 张永远拿到 1×1 像素图，
        // 表现成列表尾部一排灰块。判据改成「是不是 data: URI」，两种情况一次盖住。
        String cover = firstAttr(card, P_IMG_SRC);
        if (cover.startsWith("data:")) cover = "";
        if (cover.isEmpty()) {
            Matcher dm = Pattern.compile("<img[^>]*\\sdata-src\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
                    Pattern.CASE_INSENSITIVE).matcher(card);
            if (dm.find()) {
                String v = attrValue(dm);
                if (!v.startsWith("data:")) cover = v;
            }
        }
        put(o, "cover", cover);

        // 时长：span 里那个 1:16:25。整段扫而不是只扫 span —— span 的 class 尾巴
        // （absolute bottom-1 right-1 …）改版概率远高于「时长冒号」这个格式本身。
        String duration = firstDuration(card);
        put(o, "duration", duration);

        // 标题：优先 <a class="text-secondary …">…</a> 的正文；失败退回 img 的 alt。
        String title = titleFrom(card);
        if (title.isEmpty()) title = clean(firstAttr(card, P_ALT));
        put(o, "title", title);

        // 番号：从标题首段抠；标题为空时用 alt/卡片里任一 CODE 形态串。
        String code = extractCode(title);
        if (code.isEmpty()) code = extractCode(firstAttr(card, P_ALT));
        put(o, "code", code);
        return o;
    }

    /**
     * 从卡片里取标题。
     *
     * 取的是 class 里带 text-secondary 的那个 a（正文标题链接），而不是第一个 a
     * —— 第一个 a 是封面图上的图片链接，它的 alt 是番号不是标题。
     * 取不到再退到 img 的 alt（alt 是标题文本，实测等于正文标题）。
     */
    private static String titleFrom(String card) {
        Matcher m = Pattern.compile("<a[^>]*class=\"[^\"]*text-secondary[^\"]*\"[^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(card);
        if (m.find()) {
            String t = clean(stripTags(decodeEntities(m.group(1))));
            if (!t.isEmpty()) return t;
        }
        // 兜底：class 带 truncate 的容器（<div class="my-2 text-sm text-nord4 truncate">）
        Matcher d = Pattern.compile("<div[^>]*class=\"[^\"]*truncate[^\"]*\"[^>]*>(.*?)</div>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(card);
        if (d.find()) return clean(stripTags(decodeEntities(d.group(1))));
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // 分类页
    // ─────────────────────────────────────────────────────────────

    /**
     * 类型页分类项：
     * <a href="https://missav.live/dm96/cn/genres/%E9%AB%98%E6%B8%85" class="text-nord13">高清</a>
     * <p class="text-nord10"><a href="…">290816 条影片</a></p>
     *
     * 匹配策略：抓 `/genres/` 链接 → id 取 href 末段数字段（dm96），
     * 名字取链接文本，条数从「N 条影片」里正则抠数字。
     * 用 `/genres/` 定位而不是 class=text-nord13：class 名是 tailwind 调色板
     * 语义（nord13/nord10），换主题就全废；`/genres/` 是路由，稳得多。
     */
    private static final Pattern P_GENRE_HREF =
            Pattern.compile("<a[^>]*href\\s*=\\s*\"([^\"]*/genres/[^\"]*)\"[^>]*>(.*?)</a>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 「290816 条影片」里的数字。允许中间有空格/全角空格。 */
    private static final Pattern P_GENRE_COUNT =
            Pattern.compile("([0-9][0-9,，\\s]{0,12}?)\\s*条影片");

    /** 分类 id：dm96 —— href 末段 `dm96`。 */
    private static final Pattern P_GENRE_ID =
            Pattern.compile("/(dm[0-9]+)(?:/|$)", Pattern.CASE_INSENSITIVE);

    /**
     * 解析类型页 → JSONArray，每项 {id,name,count}。
     * 同一个分类在页面上会出现两次（一次是名字，一次是「N 条影片」那个链接，href 相同），
     * 所以按 id 去重并合并条数（后者非 0 时覆盖）。
     */
    static JSONArray parseCategories(String html) {
        JSONArray out = new JSONArray();
        if (html == null || html.isEmpty()) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        try {
            Matcher m = P_GENRE_HREF.matcher(html);
            while (m.find()) {
                String href = m.group(1);
                String idm = firstGroupMatch(P_GENRE_ID, href);
                if (idm.isEmpty()) continue;
                String id = idm.toLowerCase(java.util.Locale.US);
                String text = clean(stripTags(decodeEntities(m.group(2))));
                int count = 0;
                Matcher c = P_GENRE_COUNT.matcher(text);
                if (c.find()) {
                    try {
                        count = Integer.parseInt(c.group(1).replaceAll("[^0-9]", ""));
                    } catch (Exception ignored) {
                    }
                }
                boolean isLabel = count == 0 && text.length() > 0;
                if (seen.contains(id) && !isLabel) {
                    // 第二次出现（带条数的那个）：就地更新 count
                    updateCount(out, id, count);
                    continue;
                }
                if (seen.contains(id)) continue;
                seen.add(id);
                JSONObject o = new JSONObject();
                put(o, "id", id);
                put(o, "name", count == 0 ? text : "");
                o.put("count", count);
                out.put(o);
            }
        } catch (Exception ignored) {
        }
        // 名字缺失的补一次：常见顺序是「先名字后条数」，第一次进来就带 count 时名字留空，
        // 这里统一从全部链接文本里回填一次「非空且非纯数字」的文本。
        for (int i = 0; i < out.length(); i++) {
            JSONObject o = out.optJSONObject(i);
            if (o != null && o.optString("name").isEmpty()) {
                String n = categoryNameFallback();
                if (!n.isEmpty()) put(o, "name", n);
            }
        }
        return out;
    }

    /** 就地把某分类的 count 改成非 0 值（用于合并第二次出现时携带的条数）。 */
    private static void updateCount(JSONArray arr, String id, int count) {
        if (count <= 0) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) {
                try {
                    o.put("count", count);
                } catch (Exception ignored) {
                    // 分类条目是我们自己 new 出来的 JSONObject，put 理论上不会失败。
                    // 真失败也只是少一个条数字段，不该让整个类型页挂掉。
                }
                return;
            }
        }
    }

    /** 分类名兜底：站点分类 id 是内部码（dm96），本地没有可映射的表，只能给空。 */
    private static String categoryNameFallback() {
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // 详情页
    // ─────────────────────────────────────────────────────────────

    /** og meta：property / name 都吃，单双引号、无引号都吃。 */
    private static final Pattern P_OG =
            Pattern.compile("<meta[^>]+(?:property|name)\\s*=\\s*[\"']og:([a-z0-9_:]+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern P_META_CONTENT =
            Pattern.compile("content\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);

    /**
     * 解析详情页元信息。
     *
     * @return JSONObject {id,title,cover,description,actors[],releaseDate,duration,code}
     *         拿不到时字段为空字符串 / 空数组，**不返回 null**（上层不用判空就能 put）。
     */
    static JSONObject parseDetail(String html) {
        JSONObject o = new JSONObject();
        if (html == null || html.isEmpty()) return o;
        try {
            // id：优先 og:url 末段，其次页面里第一个 /cn/<id> 链接。
            String id = "";
            String ogUrl = meta(html, "url");
            if (!ogUrl.isEmpty()) id = extractIdFromUrl(ogUrl);
            if (id.isEmpty()) {
                Matcher m = Pattern.compile("href\\s*=\\s*\"([^\"]*/cn/[a-z0-9-]+)\"", Pattern.CASE_INSENSITIVE)
                        .matcher(html);
                if (m.find()) id = extractIdFromUrl(m.group(1));
            }
            put(o, "id", id);

            String title = clean(stripTags(decodeEntities(meta(html, "title"))));
            // og:title 常是「FCT-210 标题…」，若有 twitter:image:alt 且更干净就换上去。
            String alt = clean(stripTags(decodeEntities(metaByPrefix(html, "image:alt", "twitter:"))));
            if (!alt.isEmpty() && alt.length() > title.length()) title = alt;
            put(o, "title", title);

            put(o, "cover", meta(html, "image"));
            put(o, "description", clean(stripTags(decodeEntities(meta(html, "description")))));

            // 演员：og:video:actor 是逗号分隔。存成数组，UI 直接铺。
            String actor = meta(html, "video:actor");
            JSONArray actors = new JSONArray();
            for (String a : actor.split("[,，]")) {
                String n = clean(stripTags(decodeEntities(a)));
                if (!n.isEmpty()) actors.put(n);
            }
            o.put("actors", actors);

            String release = meta(html, "video:release_date");
            if (release.length() > 10) release = release.substring(0, 10); // 只要 YYYY-MM-DD
            put(o, "releaseDate", release);

            String duration = meta(html, "video:duration");
            if (duration.isEmpty()) duration = firstDuration(html);
            put(o, "duration", duration);

            put(o, "code", extractCode(title.isEmpty() ? id : title));

            // playUrl / qualities 由 parsePlay 负责（详情解析不碰 packed JS，
            // 这样上层可以先渲染列表再补播放源）。
            put(o, "playUrl", playUrl(html));
            o.put("qualities", qualities(html));
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 读某个 og meta 的 content。找不到返回空串。 */
    private static String meta(String html, String key) {
        Matcher m = P_OG.matcher(html);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(key)) {
                Matcher c = P_META_CONTENT.matcher(m.group());
                if (c.find()) return clean(attrValue(c));
            }
        }
        return "";
    }

    /** 读 twitter: 命名空间里某个 meta（twitter:image:alt 等）。 */
    private static String metaByPrefix(String html, String suffix, String prefix) {
        Matcher m = P_OG.matcher(html);
        while (m.find()) {
            String k = m.group(1).toLowerCase(java.util.Locale.US);
            if (k.equals(prefix + suffix)) {
                Matcher c = P_META_CONTENT.matcher(m.group());
                if (c.find()) return clean(attrValue(c));
            }
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // 播放器 packed JS 反混淆
    // ─────────────────────────────────────────────────────────────

    /**
     * packed JS 的调用形态（真实页面实测，2026-10-18）：
     *
     * <pre>
     * eval(function(p,a,c,k,e,d){…})(
     *   'f=\'8://7.6/5-4-3-2-1/e.0\';d=\'8://7.6/5-4-3-2-1/c/9.0\';b=\'…\'',
     *   16,16,
     *   'm3u8|78531695c531|ab7a|…|source'.split('|'),
     *   0,{})
     * </pre>
     *
     * <b>两个捕获组，顺序不能反</b>：group(1) 是**代码载荷**（一堆数字占位符），
     * group(2) 是**词典**。这个 packer 的第三参数 a=16 表示没启用 base-N 变体
     * （词典是明文），所以「拿词典回填代码载荷里的数字」就等于解包结果。
     * 做完整 unpack 只会引入这站用不到的 base-N 转换分支，纯负担。
     *
     * <p><b>锚点是 {@code }(' ，不是 {@code })('}</b>（2026-10-18 实测，连踩两次）。
     * 真实页面里这段是<b>内联</b>的 —— packer 函数体的收尾花括号直接接调用括号：
     * <pre>
     *   …;while(c--){…}}return p}('f=\'8://…\'',16,16,'m3u8|…'.split('|'),0,{}))
     * </pre>
     * 而<b>另一种形态</b>（手工 eval 时会看到）是 {@code eval((function(){…})('…',…))} ——
     * 那个多一层右括号。两个形态只差一个 {@code )}，用错哪一个正则就完全不匹配，
     * 而且 {@code playUrl} 静默变成空串、没有任何报错 —— 是靠拿真实页面跑才看出来的。
     *
     * <p>所以这里写成 {@code \}\s*\(}：有没有那个 {@code )} 都吃。
     *
     * <p><b>另外不要试图去匹配 packer 的函数体</b>：里面嵌套了三层花括号
     * （{@code while(c--){ if(k[c]){ … } }}），正则要写成「一层 + 任意层」的循环形式，
     * 又难读又容易漏。只锚定函数体<b>结束</b>那个 {@code (}，往后两个参数就是平铺的
     * 字符串和数字，不用再管前面长什么样。
     */
    private static final Pattern P_PACKED =
            Pattern.compile(
                    "\\}\\s*\\(?\\s*"
                            + "'((?:[^'\\\\]|\\\\.)*)'\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*"
                            + "'((?:[^'\\\\]|\\\\.)*)'",
                    Pattern.CASE_INSENSITIVE);

    /** 解包后的源码里，形如  f='https://surrit.com/…/1080p/video.m3u8';  的取值。 */
    private static final Pattern P_M3U8 =
            Pattern.compile("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]*?\\.m3u8",
                    Pattern.CASE_INSENSITIVE);

    /** 画质档位，从 m3u8 路径里抠。 */
    private static final Pattern P_QUALITY = Pattern.compile("/(360p|480p|720p|1080p)/", Pattern.CASE_INSENSITIVE);

    /**
     * 解包 packed JS，返回反混淆后的源码片段（多段用 \n 拼）。
     * 解不出（没有 eval(function…) 或字典为空）时返回空串。
     */
    static String unpackScript(String html) {
        if (html == null || html.isEmpty()) return "";
        try {
            Matcher m = P_PACKED.matcher(html);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String payload = unescapeJs(m.group(1));
                String[] words = m.group(2).split("\\|");
                if (words.length == 0) continue;
                // 词典第 i 个词回填代码载荷里的占位符 i —— 索引 **0-based**，
                // 而占位符本身是 **base-36 写法**。
                //
                // ══ 这个 packer 的两个反直觉点（2026-10-18 连踩两次才定位）══
                //
                // ① 占位符不是十进制。packer 里是
                //      e = function (c) { return c.toString(36) }
                //    所以索引 10~15 在载荷里长成 a b c d e f。
                //    载荷 `f='8://7.6/5-4-3-2-1/e.0'` 里的 `e` 就是索引 14，
                //    不是字母 e、也不是数字 14 —— 按十进制去替换，它永远不命中，
                //    结果是播放地址变成 `…/e.m3u8`（少一段，看起来像站点改版）。
                //
                // ② 索引从 0 数，不是从 1。依据：`https` 必须是 `8://` 里的 `8`，
                //    而 `https` 是词典第 9 项（下标 8）。
                //
                // 两条合起来的正确解包结果是（对照浏览器网络面板逐字核过）：
                //    f = https://surrit.com/{uuid}/playlist.m3u8    ← 主列表
                //    d = https://surrit.com/{uuid}/720p/video.m3u8
                //    b = https://surrit.com/{uuid}/1080p/video.m3u8
                //
                // ★ 从大到小替换 ★：\b 是词边界，但 "13" 和 "3" 在纯数字串里会互相
                // 干扰 —— 先替 1 会让 "13" 里的 "1" 抢先命中。
                String src = payload;
                for (int i = words.length - 1; i >= 0; i--) {
                    String w = words[i].trim();
                    if (w.isEmpty()) continue;
                    // 键是 base-36（见上），不是十进制。
                    src = src.replaceAll("\\b" + Integer.toString(i, 36) + "\\b",
                            java.util.regex.Matcher.quoteReplacement(w));
                }
                sb.append(src).append('\n');
            }
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 去掉 JS 单引号串里的转义。
     *
     * 载荷里全是 {@code \'f=\'...\';} 这种形态 —— 不去掉反斜杠的话，解出来的
     * 字符串会带上 {@code \'} 这种残片，正则 {@code https?://…m3u8} 仍然能命中，
     * 但如果站点哪天把某一段写成带 {@code \\} 的路径就会漏。这里按 JS 的三条规则处理：
     * {@code \\' \\" \\} 和 \\n}。
     */
    private static String unescapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\\\'", "'").replace("\\\\\"", "\"")
                .replace("\\'", "'").replace("\\n", "\n").replace("\\r", "");
    }

    /**
     * 提取播放地址：解包所有 packed JS，正则取所有 m3u8。
     * 没有 m3u8 时再兜底扫原始 HTML（有些页面直接内嵌 <source src=…m3u8>）。
     * 取**第一个** m3u8 作为主地址（通常是最高清/默认档）；画质档位另算。
     */
    static String playUrl(String html) {
        List<String> urls = allPlayUrls(html);
        return urls.isEmpty() ? "" : urls.get(0);
    }

    /**
     * 提取全部 m3u8 地址（去重，保持出现顺序）。
     * 先从解包源码里找，找不到再退回原始 HTML 直接搜。
     */
    static List<String> allPlayUrls(String html) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (html == null || html.isEmpty()) return new ArrayList<String>();
        try {
            collectM3u8(unpackScript(html), out);
            if (out.isEmpty()) collectM3u8(html, out);
        } catch (Exception ignored) {
        }
        return new ArrayList<String>(out);
    }

    private static void collectM3u8(String src, LinkedHashSet<String> out) {
        if (src == null) return;
        Matcher m = P_M3U8.matcher(src);
        while (m.find()) out.add(m.group());
    }

    /**
     * 画质档位列表：从全部 m3u8 地址里抠 360p/480p/720p/1080p，去重按降序（高清在前）。
     */
    static JSONArray qualities(String html) {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        for (String u : allPlayUrls(html)) {
            Matcher m = P_QUALITY.matcher(u);
            while (m.find()) set.add(m.group(1).toLowerCase(java.util.Locale.US));
        }
        List<String> list = new ArrayList<String>(set);
        // 降序：1080 > 720 > 480 > 360，让 UI 第一个就是最清晰
        list.sort((a, b) -> qualityRank(a) - qualityRank(b));
        JSONArray arr = new JSONArray();
        for (String q : list) arr.put(q);
        return arr;
    }

    private static int qualityRank(String q) {
        try {
            return -Integer.parseInt(q.replace("p", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 播放信息（parseDetail 的子集）
    // ─────────────────────────────────────────────────────────────

    /**
     * 解析播放信息 → JSONObject {playUrl, qualities, duration}。
     * 只有详情页（内含播放器 packed JS）才拼得出来；列表页调用会得到 playUrl=""，
     * 上层据此判断「需要先拉详情」而不是当成播放失败。
     */
    static JSONObject parsePlay(String html) {
        JSONObject o = new JSONObject();
        try {
            put(o, "playUrl", playUrl(html));
            o.put("qualities", qualities(html));
            put(o, "duration", meta(html, "video:duration"));
        } catch (Exception ignored) {
        }
        return o;
    }

    // ─────────────────────────────────────────────────────────────
    // 公共小工具
    // ─────────────────────────────────────────────────────────────

    /**
     * 从详情页 URL 里取视频 id：取最后一段非空路径段。
     * 同时支持 `https://missav.live/cn/fct-210` 和带变体前缀的 `https://missav.live/dm539/cn/fct-210`
     * —— 两者的视频 id 都是末段 `fct-210`，所以「取末段」比匹配固定段数更稳。
     * 末尾斜杠、query、hash 都会先剥掉。
     */
    static String extractIdFromUrl(String url) {
        if (url == null) return "";
        String s = url.trim();
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int slash = s.lastIndexOf('/');
        String last = slash >= 0 ? s.substring(slash + 1) : s;
        // 末段若不是 id（比如以 / 结尾的空路径），回退到倒数第二段。
        if (last.isEmpty()) return "";
        return last;
    }

    /**
     * 从标题里抠番号：找首个 `字母+数字` 的 CODE 形态串并大写。
     * 标题形如「FCT-210 标题…」，番号恒为首段；alt 形态是「fct-210」纯番号也能命中。
     * 找不到返回空串（不抛）。
     */
    static String extractCode(String title) {
        if (title == null || title.isEmpty()) return "";
        Matcher m = P_CODE.matcher(title);
        if (m.find()) return m.group(1).toUpperCase(java.util.Locale.US);
        return "";
    }

    /**
     * HTML 实体解码。
     * 手写 map 而不用正则通用实体表：站点只用固定几种（&amp; &lt; &gt; &quot; &#39; &nbsp; &#x27;），
     * 手写覆盖更全（含数字实体 &#NN; / &#xHH;）且无正则回溯风险。
     * ★ 双重编码要解两次 ★（实测标题里出现过 `&amp;#39;`，只解一次会留 `&#39;` 字面量）。
     */
    static String decodeEntities(String s) {
        if (s == null) return "";
        String out = s;
        for (int pass = 0; pass < 2; pass++) {
            String before = out;
            out = out.replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&#39;", "'")
                    .replace("&#039;", "'").replace("&apos;", "'")
                    .replace("&#x27;", "'").replace("&nbsp;", " ")
                    .replace("&amp;", "&");
            // 数字实体（&#NN; / &#xHH;）用带 group 的正则替换：
            // 必须走 Matcher.appendReplacement 而不是把结果再拼回去（实体里可能有 $ 或 \），
            // 之前手写的 MatchOperation 占位是错的，这里是唯一实现。
            Matcher hex = P_HEX_ENTITY.matcher(out);
            StringBuffer sb = new StringBuffer();
            while (hex.find()) {
                try {
                    hex.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(hex.group(1), 16))));
                } catch (Exception e) {
                    hex.appendReplacement(sb, Matcher.quoteReplacement(hex.group()));
                }
            }
            hex.appendTail(sb);
            out = sb.toString();

            Matcher dec = P_DEC_ENTITY.matcher(out);
            sb = new StringBuffer();
            while (dec.find()) {
                try {
                    dec.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(dec.group(1), 10))));
                } catch (Exception e) {
                    dec.appendReplacement(sb, Matcher.quoteReplacement(dec.group()));
                }
            }
            dec.appendTail(sb);
            out = sb.toString();
            if (out.equals(before)) break;
        }
        return out;
    }

    private static final Pattern P_HEX_ENTITY = Pattern.compile("&#x([0-9a-fA-F]{1,6});");
    private static final Pattern P_DEC_ENTITY = Pattern.compile("&#([0-9]{1,7});");

    /** HTML 实体解码（对外公开，方便上层直接清理 meta 值）。 */
    static String decode(String s) {
        return decodeEntities(s);
    }

    /** 去掉标签，保留文本。 */
    private static String stripTags(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "");
    }

    /** 清理空白：连续空白折叠为单个空格并 trim。 */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    /** 从一段 HTML 里取第一个匹配的时长串，找不到返回空串。 */
    private static String firstDuration(String src) {
        Matcher m = P_DURATION.matcher(src);
        return m.find() ? m.group(1) : "";
    }

    /** 取第一个属性值（自动处理双/单/无引号），无则空串。 */
    private static String firstAttr(String src, Pattern p) {
        Matcher m = p.matcher(src);
        if (m.find()) return attrValue(m);
        return "";
    }

    /** 从属性正则的匹配里取值（第2~4组任一非空）。 */
    private static String attrValue(Matcher m) {
        for (int i = 2; i <= 4; i++) {
            String v = m.group(i);
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    /** 取正则第一个捕获组，无则空串。 */
    private static String firstGroupMatch(Pattern p, String src) {
        Matcher m = p.matcher(src);
        return m.find() ? m.group(1) : "";
    }

    /** 往 JSONObject 写字符串字段，null/空安全（不抛）。 */
    private static void put(JSONObject o, String k, String v) {
        try {
            o.put(k, v == null ? "" : v);
        } catch (Exception ignored) {
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 自测
    // ─────────────────────────────────────────────────────────────

    /**
     * 自测入口：`java MissavParser` 或单元测试里直接调，返回一段可读文本。
     * 用最小合成 HTML 覆盖：列表卡、分类、详情 meta、packed JS 解包。
     * 不依赖任何外部资源，可在 JVM 单测跑。
     */
    static String selfTest() {
        StringBuilder log = new StringBuilder();

        String listHtml = "<div class=\"grid\">"
                + "<div class=\"thumbnail group\">"
                + "  <div class=\"relative rounded\">"
                + "    <a href=\"https://missav.live/cn/fct-210\" alt=\"fct-210\">"
                + "      <img data-src=\"https://fourhoi.com/fct-210/cover-t.jpg\""
                + "           src=\"https://fourhoi.com/fct-210/cover-t.jpg\" alt=\"FCT-210 测试标题\">"
                + "    </a>"
                + "    <a href=\"https://missav.live/dm539/cn/fct-210\" alt=\"fct-210\">"
                + "      <span class=\"absolute bottom-1 right-1\">1:16:25</span>"
                + "    </a>"
                + "  </div>"
                + "  <div class=\"my-2 truncate\">"
                + "    <a class=\"text-secondary group-hover:text-primary\""
                + "       href=\"https://missav.live/cn/fct-210\">FCT-210 测试&amp;标题</a>"
                + "  </div>"
                + "</div></div>";
        log.append("parseList => ").append(parseList(listHtml)).append('\n');

        String catHtml = "<div class=\"grid\">"
                + "<div><a href=\"https://missav.live/dm96/cn/genres/%E9%AB%98%E6%B8%85\""
                + " class=\"text-nord13\">高清</a>"
                + "<p class=\"text-nord10\"><a href=\"https://missav.live/dm96/cn/genres/%E9%AB%98%E6%B8%85\">"
                + "290816 条影片</a></p></div></div>";
        log.append("parseCategories => ").append(parseCategories(catHtml)).append('\n');

        // packed JS 构造：词典明文逗号分隔（模拟真实词典），源码用索引指代
        String packed = "eval(function(p,a,c,k,e,d){var g='function';return g}())("
                + "'f=https://surrit.com/18b4bbc2-c802-44a7-ab7a-78531695c531/1080p/video.m3u8;',"
                + "16,16,'m3u8|78531695c531|ab7a|44a7|c802|18b4bbc2|com|surrit|https|video|1080p|source1280|720p|source842|playlist|source'.split('|'),0,{})";
        String detailHtml = "<meta property=\"og:title\" content=\"FCT-210 测试标题\">"
                + "<meta property=\"og:image\" content=\"https://fourhoi.com/fct-210/cover-t.jpg\">"
                + "<meta property=\"og:description\" content=\"描述文本\">"
                + "<meta property=\"og:video:actor\" content=\"女优A,女优B\">"
                + "<meta property=\"og:video:duration\" content=\"1:16:25\">"
                + "<meta property=\"og:video:release_date\" content=\"2026-10-18\">"
                + "<script>" + packed + "</script>";
        log.append("parseDetail => ").append(parseDetail(detailHtml)).append('\n');
        log.append("parsePlay   => ").append(parsePlay(detailHtml)).append('\n');
        log.append("extractIdFromUrl(dm539) => ").append(extractIdFromUrl("https://missav.live/dm539/cn/fct-210")).append('\n');
        log.append("decodeEntities => ").append(decodeEntities("A&amp;#39;B&nbsp;C&lt;D&#x27;E")).append('\n');
        return log.toString();
    }

    /**
     * 数字实体解码用（正则 MatchOperation 实现的便捷入口）。
     */
    public static void main(String[] args) {
        System.out.print(selfTest());
    }
}
