import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hanime1Adapter 的离线回归。
 *
 * 拿真实抓下来的 HTML（fixtures/*.html，用 Chrome 的 TLS 指纹抓的）喂给
 * 适配器的**真实解析逻辑**，验证：
 *
 *   ① 首页抠得出卡片、数量对得上
 *   ② 分类页抠得出、广告卡被过滤掉
 *   ③ 搜索页抠得出
 *   ④ 越界页抠成空（判空逻辑）
 *   ⑤ 详情页的 meta 全取到
 *   ⑥ 播放页三档直链全抠到、画质认得对
 *   ⑦ 标签面板 7 个分区 / 240 个标签，归属和 slug 都对
 *
 * ⚠️ 那些 fixture **不在仓库里**（见 .gitignore 末尾）：它们是被测站点的
 *    真实页面，而那个站是成人内容站。第一次跑先执行 `fixtures/refresh.py`
 *    现场抓一份；缺 fixture 时 run-hanime1.sh 会直接打印这句话然后跳过，
 *    不会抛一堆 FileNotFound。
 *
 * 为什么用反射调私有方法：适配器里 parseCards/parseSources/meta 都是 private，
 * 而它们正是要测的东西。改成包可见也行，但那是为了让测试好写去动生产代码 ——
 * 反射调用一次性的代价更小。
 *
 * ★ 它不测什么：**不发任何网络请求**。所以它证明的是「解析对不对」，
 *   不是「安卓端能不能连上站点」—— 后者已在真机上验过（见适配器类注释）。
 */
public class Hanime1Test {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "java-test/fixtures";

        Class<?> cls = Class.forName("com.huangguo.mobile.Hanime1Adapter");

        Method parseCards = cls.getDeclaredMethod("parseCards", String.class, String.class);
        parseCards.setAccessible(true);
        Method parseSources = cls.getDeclaredMethod("parseSources", String.class);
        parseSources.setAccessible(true);
        Method meta = cls.getDeclaredMethod("meta", String.class, String.class, String.class);
        meta.setAccessible(true);
        Method parseTags = cls.getDeclaredMethod("parseTags", String.class, String.class);
        parseTags.setAccessible(true);
        Method parseRelated = cls.getDeclaredMethod("parseRelated", String.class, String.class);
        parseRelated.setAccessible(true);
        Method qualityOf = cls.getDeclaredMethod("qualityOf", String.class);
        qualityOf.setAccessible(true);
        Method parseTagPanel = cls.getDeclaredMethod("parseTagPanel", String.class);
        parseTagPanel.setAccessible(true);

        String home = read(dir + "/home.html");
        String genre1 = read(dir + "/genre-1.html");
        String query1 = read(dir + "/query-1.html");
        String empty = read(dir + "/empty-page.html");
        String watch = read(dir + "/watch-408706.html");

        // ── ① 首页 ─────────────────────────────────────────────────────
        JSONArray hCards = (JSONArray) parseCards.invoke(null, home, "https://hanime1.me/");
        check("首页卡片数 ≥ 100", hCards.length() >= 100, "实际 " + hCards.length());
        JSONObject c0 = hCards.optJSONObject(0);
        check("首页首条有 id", c0.optString("id").length() > 0, c0.optString("id"));
        check("首页首条有 title", c0.optString("title").length() > 0, c0.optString("title"));
        check("首页首条封面在 hembed", c0.optString("cover").contains("vdownload.hembed.com"),
                c0.optString("cover").isEmpty() ? "(空)" : "ok");
        check("首页首条有 badge(mm:ss)", c0.optString("badge").contains(":"), c0.optString("badge"));
        check("首页首条有 score(%)", c0.optString("score").contains("%"), c0.optString("score"));
        // 去重
        java.util.Set<String> ids = new java.util.HashSet<String>();
        boolean dup = false;
        for (int i = 0; i < hCards.length(); i++) {
            if (!ids.add(hCards.optJSONObject(i).optString("id"))) dup = true;
        }
        check("首页 id 无重复", !dup, "共 " + ids.size() + " 个唯一 id");

        // ── ② 分类页：广告必须被过滤 ───────────────────────────────────
        JSONArray gCards = (JSONArray) parseCards.invoke(null, genre1, "https://hanime1.me/");
        check("分类页卡片数 30~45", gCards.length() >= 30 && gCards.length() <= 45,
                "实际 " + gCards.length());
        boolean hasAd = false, badCover = false;
        for (int i = 0; i < gCards.length(); i++) {
            JSONObject v = gCards.optJSONObject(i);
            String id = v.optString("id");
            if (id.length() == 0 || !id.matches("\\d+")) hasAd = true;
            if (v.optString("cover").contains("/image/icon/")) badCover = true;
        }
        check("分类页没有广告卡混入", !hasAd, hasAd ? "混进了非数字 id" : "ok");
        check("分类页没有广告位图集当封面", !badCover, badCover ? "混进了 /image/icon/" : "ok");
        JSONObject g0 = gCards.optJSONObject(0);
        check("分类页首条有标题", g0.optString("title").length() > 0, g0.optString("title"));

        // ── ③ 搜索页 ───────────────────────────────────────────────────
        JSONArray qCards = (JSONArray) parseCards.invoke(null, query1, "https://hanime1.me/");
        check("搜索页卡片数 ≥ 30", qCards.length() >= 30, "实际 " + qCards.length());

        // ── ④ 越界页必须抠成 0 条（判空逻辑的根据）─────────────────────
        JSONArray eCards = (JSONArray) parseCards.invoke(null, empty, "https://hanime1.me/");
        check("越界页抠出 0 条", eCards.length() == 0, "实际 " + eCards.length());

        // ── ⑤ 详情 meta ────────────────────────────────────────────────
        String ogTitle = (String) meta.invoke(null, watch, "property", "og:title");
        String ogImage = (String) meta.invoke(null, watch, "property", "og:image");
        String ogDur = (String) meta.invoke(null, watch, "property", "og:video:duration");
        String twDesc = (String) meta.invoke(null, watch, "name", "twitter:description");
        check("og:title 取到", ogTitle.length() > 0, ogTitle);
        check("og:image 取到且是 hembed", ogImage.contains("vdownload.hembed.com"), ogImage);
        check("og:video:duration 是数字", ogDur.matches("\\d+"), ogDur);

        String title = ogTitle.replace(" - Hanime1.me", "");
        JSONArray tags = (JSONArray) parseTags.invoke(null, twDesc, title);
        check("标签剥得出来(≥5 个)", tags.length() >= 5, tags.length() + " 个");
        boolean qualityLeaked = false;
        for (int i = 0; i < tags.length(); i++) {
            if (tags.optString(i).matches("\\d{3,4}[pP]")) qualityLeaked = true;
        }
        check("画质词没混进标签", !qualityLeaked, qualityLeaked ? "混进了 1080p 之类" : "ok");

        // ── ⑤b 标签面板（240 个细标签，7 个分区）────────────────────────
        // 这一层是这个站最值钱的部分：genre 只是大方向，细标签才决定找不找得到东西。
        String panelHtml = read(dir + "/tagpanel.html");
        JSONArray panel = (JSONArray) parseTagPanel.invoke(null, panelHtml);
        int totalTags = 0;
        StringBuilder sectionSummary = new StringBuilder();
        for (int i = 0; i < panel.length(); i++) {
            JSONObject g = panel.optJSONObject(i);
            int n = g.optJSONArray("tags") == null ? 0 : g.optJSONArray("tags").length();
            totalTags += n;
            sectionSummary.append(g.optString("code")).append("=").append(n).append(" ");
        }
        check("标签面板解析出 7 个分区", panel.length() == 7, "实际 " + panel.length() + " → " + sectionSummary);
        check("标签总数 ≥ 200", totalTags >= 200, "实际 " + totalTags);
        // 每个分区的 group 不能是空串，也不能全是 other（那说明分区名没认出来）
        int otherCount = 0;
        for (int i = 0; i < panel.length(); i++) {
            if ("other".equals(panel.optJSONObject(i).optString("code"))) otherCount++;
        }
        check("认不出的分区 ≤1", otherCount <= 1, "落进 other 的分区数 " + otherCount);

        // 抽查几个具体标签在不在对应的组里
        check("「巨乳」在外貌身材(style)", hasTagIn(panel, "style", "巨乳"), "查 style 组");
        check("「NTR」在故事剧情(plot)", hasTagIn(panel, "plot", "NTR"), "查 plot 组");
        check("「中文字幕」在影片属性", hasTagIn(panel, "hanime-attr", "中文字幕"), "查 hanime-attr 组");
        check("「校園」在情境场所(setting)", hasTagIn(panel, "setting", "校園"), "查 setting 组");
        check("每个标签都带 slug", everyTagHasSlug(panel), "抽查全部");

        JSONArray rel = (JSONArray) parseRelated.invoke(null, watch, "408706");
        check("相关推荐被截到 ≤12", rel.length() <= 12 && rel.length() > 0, "实际 " + rel.length());
        boolean selfInRel = false;
        for (int i = 0; i < rel.length(); i++) {
            if ("408706".equals(rel.optJSONObject(i).optString("id"))) selfInRel = true;
        }
        check("相关推荐里没有自己", !selfInRel, selfInRel ? "自己混进去了" : "ok");

        // ── ⑥ 播放直链 ─────────────────────────────────────────────────
        JSONArray srcs = (JSONArray) parseSources.invoke(null, watch);
        check("抠到 3 档直链", srcs.length() == 3, "实际 " + srcs.length());
        java.util.Set<Integer> qs = new java.util.TreeSet<Integer>();
        boolean sigOk = true;
        for (int i = 0; i < srcs.length(); i++) {
            JSONObject s = srcs.optJSONObject(i);
            qs.add(s.optInt("quality"));
            if (!s.optString("url").contains("?secure=")) sigOk = false;
        }
        check("三档是 480/720/1080", qs.toString().equals("[480, 720, 1080]"), qs.toString());
        check("每条都带 ?secure= 签名", sigOk, sigOk ? "ok" : "有裸链");

        int q720 = (Integer) qualityOf.invoke(null,
                "https://vdownload.hembed.com/408706-720p.mp4?secure=abc");
        check("从 URL 认画质 = 720", q720 == 720, "认出 " + q720);

        // ── 汇总 ───────────────────────────────────────────────────────
        System.out.println();
        System.out.println("  通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    static String read(String path) throws Exception {
        byte[] b = Files.readAllBytes(Paths.get(path));
        return new String(b, StandardCharsets.UTF_8);
    }

    /** 某个组里有没有这个标签。 */
    static boolean hasTagIn(JSONArray groups, String code, String tagName) {
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null || !code.equals(g.optString("code"))) continue;
            JSONArray tags = g.optJSONArray("tags");
            if (tags == null) return false;
            for (int j = 0; j < tags.length(); j++) {
                if (tagName.equals(tags.optJSONObject(j).optString("name"))) return true;
            }
        }
        return false;
    }

    /** 每个标签都该有 slug（前端的 tag-list 路径靠它填 {slug}）。 */
    static boolean everyTagHasSlug(JSONArray groups) {
        for (int i = 0; i < groups.length(); i++) {
            JSONArray tags = groups.optJSONObject(i).optJSONArray("tags");
            if (tags == null) continue;
            for (int j = 0; j < tags.length(); j++) {
                String slug = tags.optJSONObject(j).optString("slug");
                if (slug == null || slug.length() == 0) return false;
            }
        }
        return true;
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ok   " + pad(name) + " → " + detail); }
        else { fail++; System.out.println("  FAIL " + pad(name) + " → " + detail); }
    }

    static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 34) b.append(' ');
        return b.toString();
    }
}
