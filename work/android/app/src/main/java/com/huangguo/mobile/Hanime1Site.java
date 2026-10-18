package com.huangguo.mobile;

import static com.huangguo.mobile.SiteCfg.*;
import static com.huangguo.mobile.UserSource.*;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** hanime1：单站 HTML 型，没有接口，配置全部由代码生成。 */
final class Hanime1Site implements SiteAdapter {
    @Override public String id() { return "hanime1"; }

    /**
     * 地址是不是 hanime1（含 m./www. 这类子域）。
     *
     * 只认 hanime1.me 这一个域。站点的镜像有几个（hanimeone.me 是它的漫画站、
     * 别的镜像域不在我们的适配器覆盖范围内），**不跟着认** —— 认了一个对不上
     * DOM 形状的镜像域，表现是首页一片空白而不是明确报错，那比不认还糟。
     */
    public boolean matchesUrl(String url) {
        try {
            String host = new URI(url == null ? "" : url.trim()).getHost();
            if (host == null) return false;
            return host.equals("hanime1.me") || host.endsWith(".hanime1.me");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * hanime1 → 我们的配置。
     *
     * 和 agedm 不一样的地方：这个站**没有接口可探**（/api/* 全 404），所以
     * 「探通才存」这件事只能拿首页**页面本身**当探针 —— 拉一次 GET /，确认
     * 里面能抠出卡片链接，才认为这个源可用。
     *
     * 探针失败就抛，不留一份点了没反应的配置。这正是 UserSource 那套
     * 「切换走完整校验」的规矩要的效果。
     */
    public JSONObject probe(String url) throws Exception {
        String base = "https://hanime1.me";
        JSONArray probe = probeHanime1(base);
        if (probe == null) {
            throw new IOException("hanime1 首页里没找到任何影片卡片，站点可能改版或该地区被墙");
        }
        log("hanime1 探测通过：首页卡片 " + probe.length() + " 条");

        JSONObject config = new JSONObject();
        config.put("adapter", "hanime1");
        config.put("origin", base);

        JSONObject cover = new JSONObject();
        cover.put("mode", "plain");
        JSONArray hosts = new JSONArray();
        hosts.put("vdownload.hembed.com");
        cover.put("allowedHosts", hosts);
        cover.put("hostRewrites", new JSONObject());
        config.put("cover", cover);

        JSONObject web = new JSONObject();
        web.put("firstPageSize", 24);
        web.put("pageSize", 40);

        JSONObject paths = new JSONObject();
        paths.put("home", "/api/home");
        paths.put("detail", "/api/videos/{id}");
        paths.put("episode", "/api/videos/{id}/play?ep={episode}");
        paths.put("search", "/api/search?q={keyword}&page={page}");
        paths.put("category", "/api/videos?cate_id={cateId}&page={page}&size={size}");
        // ── tag-list 走 tag=，不走 cate_id ──────────────────────────────
        // 这两个参数在站点那边是**两套东西**：
        //   cate_id → genre（10 个大方向：裏番 / 3DCG / MMD …）
        //   tag     → tags[]（标签面板上那 240 个多选框：巨乳 / NTR / 校園 …）
        // 走错了不会有报错，只会「点标签出来的还是默认列表」，看着像没过滤。
        // Hanime1Adapter 那边按参数名分流，见它的 fetch()。
        paths.put("tag-list", "/api/videos?tag={slug}&page={page}&size={size}");
        paths.put("tags", "/api/tags");
        web.put("paths", paths);

        JSONObject categories = new JSONObject();
        JSONArray labels = new JSONArray();
        for (int i = 0; i < HANIME1_GENRES.length; i++) {
            categories.put(HANIME1_GENRES[i], HANIME1_GENRES[i]);
            labels.put(HANIME1_GENRES[i]);
        }
        web.put("categories", categories);

        // ── 底部导航 ────────────────────────────────────────────────────
        // 站点的十个 genre 里，前四个是量最大的（裏番 / 泡麵番 / 3DCG / 2D動畫），
        // 剩下的合成一个「更多」。这和 wrapCms 那套「按子分类数量排序取前 4 个」
        // 是同一个思路，只是这里的分类是固定的十个、没有层级，所以写死顺序。
        JSONObject ui = new JSONObject();
        JSONArray tabs = new JSONArray();
        String[][] plan = {
            {"裏番", "裏番", "film"},
            {"泡麵番", "泡麵番", "sparkle"},
            {"3DCG", "3DCG", "grid"},
            {"2D動畫", "2D動畫", "tv"},
            {"more", "更多", "grid"},
        };
        for (int i = 0; i < plan.length; i++) {
            JSONObject tab = new JSONObject();
            tab.put("key", plan[i][0]);
            tab.put("label", plan[i][1]);
            tab.put("icon", plan[i][2]);
            tabs.put(tab);
        }
        ui.put("tabs", tabs);

        JSONArray mainFour = new JSONArray();
        mainFour.put("裏番");
        mainFour.put("泡麵番");
        mainFour.put("3DCG");
        mainFour.put("2D動畫");
        JSONArray rest = new JSONArray();
        rest.put("Motion Anime");
        rest.put("2.5D");
        rest.put("AI生成");
        rest.put("MMD");
        rest.put("Cosplay");
        rest.put("新番預告");

        JSONObject cids = new JSONObject();
        cids.put("裏番", oneArray("裏番"));
        cids.put("泡麵番", oneArray("泡麵番"));
        cids.put("3DCG", oneArray("3DCG"));
        cids.put("2D動畫", oneArray("2D動畫"));
        cids.put("more", rest);
        ui.put("categoryIds", cids);

        JSONObject clabels = new JSONObject();
        clabels.put("裏番", oneArray("裏番"));
        clabels.put("泡麵番", oneArray("泡麵番"));
        clabels.put("3DCG", oneArray("3DCG"));
        clabels.put("2D動畫", oneArray("2D動畫"));
        clabels.put("more", rest);
        ui.put("categoryLabels", clabels);

        JSONObject ctitles = new JSONObject();
        ctitles.put("裏番", "裏番");
        ctitles.put("泡麵番", "泡麵番");
        ctitles.put("3DCG", "3DCG");
        ctitles.put("2D動畫", "2D動畫");
        ctitles.put("more", "更多分类");
        ui.put("categoryTitles", ctitles);

        JSONObject cleads = new JSONObject();
        cleads.put("裏番", "hanime1.me 主分类。");
        cleads.put("泡麵番", "短篇。");
        cleads.put("3DCG", "3DCG 作品。");
        cleads.put("2D動畫", "2D 动画。");
        cleads.put("more", "其余分类。");
        ui.put("categoryLeads", cleads);

        // ── 首页那排胶囊：从 240 个标签里挑 8 个有代表性的 ────────────────
        // 挑法不是「从头截前 8 个」（那会全是「無碼 / AI解碼 / 中文字幕」这种
        // 规格词，看着像一排设置项），而是**每个分区轮一个** —— 和 wrapCms
        // 那套「每个频道轮流挑一个」是同一个思路。
        //
        // 顺序也刻意打散了：规格词放第一个（它最常用），剩下按分区铺开。
        JSONArray popular = new JSONArray();
        String[][] picks = {
            { "中文字幕", "中文字幕" },   // 影片屬性里最常用的
            { "巨乳",     "巨乳" },
            { "JK",       "JK" },
            { "純愛",     "純愛" },
            { "NTR",      "NTR" },
            { "校園",     "校園" },
            { "後宮",     "後宮" },
            { "3P",       "3P" },
        };
        for (int i = 0; i < picks.length; i++) {
            JSONArray pair = new JSONArray();
            pair.put(picks[i][0]);
            pair.put(picks[i][1]);
            popular.put(pair);
        }
        ui.put("popularTags", popular);
        ui.put("publicPopularTags", new JSONArray());

        // ── 「全部类型」页的分区：7 个 ───────────────────────────────────
        // 这是这个站**最值钱的一层**。标签不分组的话，240 个挤在一页里没法看。
        //
        // ⚠️ 组名必须和适配器交上来的 `tag.group` 一字不差。对不上的组会被
        //    前端**整组丢掉**——不报错，只是页面上少一块（见站点配置参考）。
        //
        // 分区表原来是从适配器那边取（Hanime1Adapter.tagSections()），好处是
        // 「唯一来源」。但那个类现在不在仓库里（见 .gitignore），留着这行会让
        // 整个文件编译不过 —— 而分区表本来就是**配置的一部分**，放在这里
        // 反而更自洽：它是「这个源的界面长什么样」，不是「怎么解析响应」。
        //
        // 唯一的代价：改分区要同时动适配器的 TAG_SECTIONS。两处都留了指回来的
        // 注释，对不上时表现为「某个分区的标签消失」，好排查。
        JSONArray tagGroups = new JSONArray();
        String[][] sections = HANIME1_TAG_SECTIONS;
        for (int i = 0; i < sections.length; i++) {
            JSONArray g = new JSONArray();
            g.put(sections[i][1]);   // code，必须和 tag.group 一致
            g.put(sections[i][2]);   // 界面上的标题
            tagGroups.put(g);
        }
        // other 一定要留着：适配器认不出的分区名会折进这里（站点改字形就会发生）
        JSONArray other = new JSONArray();
        other.put("other");
        other.put("其他");
        tagGroups.put(other);
        ui.put("tagGroups", tagGroups);

        web.put("ui", ui);
        config.put("web", web);

        config.put("_note", "hanime1.me。数据全在 SSR 的 HTML 里（站点没有任何 JSON 接口，"
                + "/api/* 全 404），播放地址是页面内联的 ?secure= 签名直链，"
                + "**必须在点播放那一刻现抓详情页**，不能进缓存。"
                + "适配器见 Hanime1Adapter.java；那个站的逆向笔记不在仓库里"
                + "（内容是成人向，代码留、内容不留）。");
        return config;
    }

    /** 站点的十个分类。值就是中文原词 —— 它的 URL 里就是中文，没有代码。 */
    static final String[] HANIME1_GENRES = {
        "裏番", "泡麵番", "Motion Anime", "3DCG", "2.5D",
        "2D動畫", "AI生成", "MMD", "Cosplay", "新番預告"
    };

    /**
     * 标签面板的七个分区：{ 站点分区名, 我们的组 code, 界面上的标题 }。
     *
     * ⚠️ **必须和适配器里的 TAG_SECTIONS 一字不差。** 那边负责把 240 个标签
     * 按分区打上 group，这边负责告诉前端「有哪些组要渲染」。两个表对不上的
     * 那一组会被**整组丢掉** —— 不报错，只是页面上少一块。
     *
     * 为什么会分成两份而不是共用：适配器不在仓库里（见 .gitignore），
     * 而这张表是**配置的一部分**（界面长什么样），不是解析逻辑。真要合并，
     * 得先把适配器放回来。
     */
    static final String[][] HANIME1_TAG_SECTIONS = {
        { "影片屬性", "hanime-attr",  "影片属性" },
        { "人物關係", "relationship", "人物关系" },
        { "角色設定", "hanime-role",  "角色设定" },
        { "外貌身材", "style",        "外貌身材" },
        { "情境場所", "setting",      "情境场所" },
        { "故事劇情", "plot",         "故事剧情" },
        { "性交體位", "hanime-act",   "性交体位" },
    };

    /**
     * hanime1 的探针：拉一次首页，数里面的 watch 链接。
     *
     * **不用 JSON**（它没有），所以这里做的是「HTML 里有没有卡片」这个最基础的
     * 存在性检查 —— 不用 Hanime1Adapter 那套完整解析，因为探针失败的原因不需要
     * 区分（站点改版 / 被墙 / 返回错误页，对用户来说都是「这个源用不了」）。
     *
     * 数不出来返回 null。**不猜**：返回 0 条也当失败。
     */
    static JSONArray probeHanime1(String base) {
        try {
            byte[] body = AiiHttp.getRaw(base + "/", 20);
            if (body == null || body.length == 0) return null;
            String html = new String(body, "UTF-8");
            JSONArray hits = new JSONArray();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "/watch\\?v=(\\d+)").matcher(html);
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            while (m.find() && hits.length() < 5) {
                if (seen.add(m.group(1))) hits.put(m.group(1));
            }
            return hits.length() == 0 ? null : hits;
        } catch (Throwable e) {
            log("hanime1 探针失败：" + e);
            return null;
        }
    }

    @Override public byte[] fetch(SiteProfile profile, String path) throws Exception {
        return Hanime1Adapter.fetch(path, profile);
    }

    @Override public String check(JSONObject config) {
        return SiteCfg.checkOrigin(config);
    }
}
