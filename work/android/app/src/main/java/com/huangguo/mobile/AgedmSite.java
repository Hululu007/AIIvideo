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

/** AGE动漫：域名识别，数据在 v2 接口上。 */
final class AgedmSite implements SiteAdapter {
    @Override public String id() { return "agedm"; }

    /** 地址是不是 AGE动漫（含 api./m./www. 几个子域）。 */
    public boolean matchesUrl(String url) {
        try {
            String host = new URI(url == null ? "" : url.trim()).getHost();
            return host != null && (host.equals("agedm.io") || host.endsWith(".agedm.io"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * AGE动漫 → 我们的配置。
     *
     * 这个站没法按响应形状识别（页面是 HTML），所以按域名认。认出来之后不去 fetch
     * 用户填的那个地址（那会拿到 HTML），直接去探它的数据接口 —— 探通了才存，
     * 免得留一份点了没反应的配置。
     */
    public JSONObject probe(String url) throws Exception {
        final String api = "https://api.agedm.io";
        JSONObject probe = UserSource.fetch(api + "/v2/home-list");
        if (probe.optJSONArray("latest") == null || probe.optJSONArray("recommend") == null) {
            throw new IOException("AGE动漫 的接口没有返回预期数据，可能改版了");
        }
        log("AGE动漫 探测通过：latest " + probe.optJSONArray("latest").length()
                + " 条 / recommend " + probe.optJSONArray("recommend").length() + " 条");

        // 播放走同名采集源 —— 站点自己的地址是 age_ 密文 + Go/WASM 签名门，解不了。
        // 默认给暴风（2026-10-16 实测同名作品在它上面是明文 m3u8）。匹配要求标题
        // 完全一致，对不上就明确报错，不猜。想换源改这一个字段就行。
        final String playback = "https://bfzyapi.com/api.php/provide/vod/";

        JSONObject config = new JSONObject();
        config.put("adapter", "agedm");
        config.put("api", api);
        config.put("playback", playback);
        config.put("_note", "AGE动漫。数据来自 api.agedm.io/v2/（裸请求可拉，无鉴权）。"
                + "播放地址站点自己是加密的，这里是拿标题去 playback 那个苹果CMS 采集站匹配同名作品"
                + "（标题必须完全一致，对不上会明确报错）。");

        JSONObject web = new JSONObject();
        web.put("firstPageSize", 24);
        web.put("pageSize", 24);

        JSONObject paths = new JSONObject();
        paths.put("home", "/api/home");
        paths.put("detail", "/api/videos/{id}");
        paths.put("episode", "/api/videos/{id}/play?ep={episode}");
        paths.put("search", "/api/search?q={keyword}&page={page}");
        paths.put("category", "/api/videos?cate_id={cateId}&order=time&page={page}&size={size}");
        paths.put("recommend", "/api/videos?order=hits&page={page}&size={size}");
        paths.put("newest", "/api/videos?order=time&page={page}&size={size}");
        paths.put("tags", "/api/tags");
        paths.put("tag-list", "/api/videos?cate_id={slug}&order=time&page={page}&size={size}");
        web.put("paths", paths);

        // 分类：AGE动漫 的「类型」是中文词（label=搞笑），没有数字 id。
        // slug 和值都直接用那个词 —— 值会被填进 {cateId}，适配器拿去当 label 用。
        JSONObject categories = new JSONObject();
        JSONArray slugs = new JSONArray();
        for (int i = 0; i < AgedmAdapter.LABELS.length; i++) {
            categories.put(AgedmAdapter.LABELS[i], AgedmAdapter.LABELS[i]);
            slugs.put(AgedmAdapter.LABELS[i]);
        }
        web.put("categories", categories);

        JSONObject ui = new JSONObject();
        JSONArray tabs = new JSONArray();
        tabs.put(tab("all", "分类", "grid"));
        ui.put("tabs", tabs);
        JSONObject ids = new JSONObject();
        ids.put("all", slugs);
        ui.put("categoryIds", ids);
        JSONObject labels = new JSONObject();
        labels.put("all", slugs);
        ui.put("categoryLabels", labels);
        JSONObject titles = new JSONObject();
        titles.put("all", "全部分类");
        ui.put("categoryTitles", titles);
        JSONObject leads = new JSONObject();
        leads.put("all", "46 个类型词，来自这个站自己的筛选字典。");
        ui.put("categoryLeads", leads);

        // 首页那排胶囊：挑几个常见的类型。
        JSONArray hot = new JSONArray();
        String[] picks = {"热血", "奇幻", "恋爱", "战斗", "搞笑", "校园", "悬疑", "治愈"};
        for (int i = 0; i < picks.length; i++) {
            JSONArray pair = new JSONArray();
            pair.put(picks[i]);
            pair.put(picks[i]);
            hot.put(pair);
        }
        ui.put("popularTags", hot);
        ui.put("publicPopularTags", new JSONArray());
        // tags 接口交给适配器合成（站点没有标签接口），分组名和它对上。
        JSONArray groups = new JSONArray();
        JSONArray one = new JSONArray();
        one.put("agedm");
        one.put("全部分类");
        groups.put(one);
        ui.put("tagGroups", groups);
        web.put("ui", ui);

        // 字段别名。**必须和适配器实际吐出来的键名对上** —— 前端 pick() 是按这张表
        // 找值的，表里没有的键就等于没有。（第一版这里全部写成角色名本身，
        // 而适配器吐的是 ep_num，于是剧集号解析成 undefined，集数按钮全是
        // data-episode="undefined"，播放根本没开始。）
        JSONObject fields = new JSONObject();
        fields.put("id", arr("id"));
        fields.put("title", arr("title"));
        fields.put("cover", arr("cover"));
        fields.put("score", arr("score"));
        fields.put("tags", arr("tags"));
        fields.put("description", arr("description"));
        fields.put("category", arr2("category", "cate_id"));
        fields.put("badge", arr("badge"));
        fields.put("episodeCount", arr("episode_count"));
        fields.put("episodeNumber", arr("ep_num"));
        fields.put("source", arr("video_url"));
        fields.put("duration", arr("duration"));
        web.put("fields", fields);
        config.put("web", web);

        JSONObject cover = new JSONObject();
        cover.put("mode", "plain");
        // 封面是百度图床的代理地址（gimg0-4.baidu.com/gimg/…?src=真图），
        // 白名单精确匹配、不支持通配符，所以五个都列上。
        JSONArray hosts = new JSONArray();
        hosts.put("gimg0.baidu.com");
        hosts.put("gimg1.baidu.com");
        hosts.put("gimg2.baidu.com");
        hosts.put("gimg3.baidu.com");
        hosts.put("gimg4.baidu.com");
        cover.put("allowedHosts", hosts);
        config.put("cover", cover);

        return config;
    }

    @Override public byte[] fetch(SiteProfile profile, String path) throws Exception {
        return AgedmAdapter.fetch(path, profile);
    }

    @Override public String check(JSONObject config) {
        return SiteCfg.checkApi(config);
    }

    @Override public String identity(JSONObject config) {
        String base = config.optString("api", "").trim();
        if (base.length() == 0) base = "https://api.agedm.io";
        return "hgsrc:agedm|" + trimSlash(base) + "/v2/detail/";
    }
}
