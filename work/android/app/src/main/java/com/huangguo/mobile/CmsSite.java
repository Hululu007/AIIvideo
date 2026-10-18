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

/** 苹果CMS：按响应形状认，不按域名。 */
final class CmsSite implements SiteAdapter {
    @Override public String id() { return "cms"; }

    @Override public boolean matchesBody(JSONObject body) {
        return body != null && body.has("code") && body.has("list") && body.has("class");
    }

    /** 两次探测 + XML 补层级，都在这里做完，再交给 build。 */
    @Override public JSONObject probe(String url, JSONObject fetched) throws Exception {
        return build(url, enrich(url, fetched));
    }

    @Override public byte[] fetch(SiteProfile profile, String path) throws Exception {
        return CmsAdapter.fetch(path, profile);
    }

    @Override public String check(JSONObject config) {
        return SiteCfg.checkApi(config);
    }

    @Override public String identity(JSONObject config) {
        String api = config.optString("api", "").trim();
        if (api.length() == 0) api = config.optString("playback", "").trim();
        return api.length() > 0 ? "hgsrc:cms|" + api : null;
    }

    /**
     * 苹果CMS → 我们的配置。
     *
     * 存的只是「api 地址 + 从 class[] 生成出来的分类表」。真正的映射在 CmsAdapter 里 ——
     * 它负责让 CMS 的响应看起来像我们的站，于是前端 adapters.js 一行不用改。
     */
    /**
     * 两次探测 + XML 补层级。
     *
     * 两次探测的结果是**互补的**，实测（暴風源，2026-10-16）：
     *
     *   裸地址 / ?ac=list      list=20  class=49  封面域名=0
     *   ?ac=detail&pg=1        list=20  class= 0  封面域名=1
     *
     * class[]（分类表）只在 list 那侧出现，vod_pic（封面）只在 detail 那侧。
     * 少任何一边都不行：没有 class 就建不出分类表，没有 vod_pic 就提取不出
     * 封面域名 —— 而空的 allowedHosts 会在 SiteProfile 构造时抛「缺少封面域名」，
     * 那是 Activity.onCreate 里，表现是一装完就闪退。
     *
     * XML 那套接口（/at/xml/）的 &lt;class&gt; 里只有 id 和名字，没有 type_pid，
     * 分类树读不出来 —— 所以从同门 JSON 接口把层级补进去。
     */
    private JSONObject enrich(String url, JSONObject fetched) throws Exception {
        JSONObject merged = new JSONObject(fetched.toString());
        try {
            JSONObject rich = UserSource.fetch(SiteCfg.withParams(url, "ac=detail&pg=1"));
            if (rich.has("list")) merged.put("list", rich.getJSONArray("list"));
            if (!merged.has("class") && rich.has("class")) merged.put("class", rich.getJSONArray("class"));
        } catch (Exception e) {
            // 第二次拉不动就用第一份。后面提取不到封面域名会明确报错，
            // 不会把坏配置存进去。
        }

        if (!SiteCfg.hasHierarchy(merged) && url.contains("/at/xml/")) {
            try {
                JSONObject sibling = UserSource.fetch(
                        SiteCfg.withParams(url.replace("/at/xml/", "/"), "ac=list"));
                int filled = SiteCfg.mergeParents(merged.optJSONArray("class"),
                        sibling.optJSONArray("class"));
                UserSource.log("XML 接口没有分类层级，从同门 JSON 接口补到 " + filled + " 条");
            } catch (Throwable e) {
                UserSource.log("补问同门 JSON 接口失败，分类按一维处理：" + e);
            }
        }
        return merged;
    }

    JSONObject build(String url, JSONObject probe) throws Exception {
        JSONObject config = new JSONObject();
        config.put("adapter", "cms");
        String api = url.trim();
        int q = api.indexOf('?');
        config.put("api", q < 0 ? api : api.substring(0, q));

        JSONObject web = new JSONObject();
        JSONObject categories = CmsAdapter.categoriesOf(probe);
        if (categories.length() == 0) throw new IOException("CMS 的分类表是空的，这个接口可能不能用了");
        web.put("categories", categories);

        JSONObject paths = new JSONObject();
        paths.put("home", "/api/home");
        paths.put("detail", "/api/videos/{id}");
        paths.put("episode", "/api/videos/{id}/play?ep={episode}");
        paths.put("search", "/api/search?q={keyword}&page={page}");
        paths.put("category", "/api/videos?cate_id={cateId}&page={page}&size={size}");
        paths.put("recommend", "/api/videos?page={page}&size={size}");
        paths.put("newest", "/api/videos?sort=new&page={page}&size={size}");
        paths.put("tags", "/api/tags");
        // 标签走**分类**而不是搜索。CMS 的搜是标题匹配（wd=），拿「动作片」去搜
        // 一个都搜不到 —— 上一版就是 /api/search?q={keyword}，点标签等于白点。
        // 而标签的 slug 在 CMS 那边本来就是 type_id（见 CmsAdapter.tags），
        // 直接当 cate_id 用，一次到位。
        paths.put("tag-list", "/api/videos?cate_id={slug}&page={page}&size={size}");
        web.put("paths", paths);

        // CMS 每页固定 20 条（实测 limit=20），size 参数它不认 —— 这里写 20 只是
        // 让前端的分页逻辑算出正确的结果。
        web.put("firstPageSize", 20);
        web.put("pageSize", 20);

        // ── 底部导航那几格 ────────────────────────────────────────────
        //
        // 上一版是「两个 key 填同一份分类」：CMS 的分类是一维的，而界面只有两个
        // 入口，于是剧场和 AI 长得一模一样，名字还跟内容没关系 —— 源里根本没有
        // 「AI」这种东西。现在导航由配置出（web.ui.tabs），这里就按**源自己的
        // 分类树**生成：class[] 里 type_pid 指向父类的就是二级类型，顶层那几个
        // （电影片/连续剧/综艺片/动漫片）天然就是入口。
        JSONArray classes = probe.optJSONArray("class");
        LinkedHashMap<String, JSONObject> byId = new LinkedHashMap<String, JSONObject>();
        ArrayList<String> order = new ArrayList<String>();
        for (int i = 0; classes != null && i < classes.length(); i++) {
            JSONObject c = classes.optJSONObject(i);
            if (c == null) continue;
            String id = plain(c.opt("type_id"));
            if (id.length() == 0) continue;
            if (!byId.containsKey(id)) order.add(id);
            byId.put(id, c);
        }
        if (byId.isEmpty()) throw new IOException("CMS 的分类表是空的，这个接口可能不能用了");

        ArrayList<String> roots = new ArrayList<String>();
        LinkedHashMap<String, ArrayList<String>> children =
                new LinkedHashMap<String, ArrayList<String>>();
        for (String id : order) {
            String pid = plain(byId.get(id).opt("type_pid"));
            // 顶层 = pid 是 0、压根没有 pid、或者指到一个不存在的类。
            // 最后那条是给「pid 乱填」的源兜底的 —— 不然那些类会凭空消失。
            if (pid.length() == 0 || "0".equals(pid) || !byId.containsKey(pid)) {
                roots.add(id);
                children.put(id, new ArrayList<String>());
            }
        }
        for (String id : order) {
            String pid = plain(byId.get(id).opt("type_pid"));
            ArrayList<String> kids = children.get(pid);
            if (kids != null && !roots.contains(id)) kids.add(id);
        }

        // 顶层里**有子分类**的那些才是能当入口的频道。这个区分不是审美问题：
        // 苹果CMS 查父类不出内容（实测 t=<父id> 返回 0 条），所以一个入口要能用，
        // 必须把它下面的子分类列全；而本来就没有子分类的顶层（短剧/AI漫剧 这种）
        // 自己就是一条能查的叶子分类。
        ArrayList<String> channels = new ArrayList<String>();
        ArrayList<String> bare = new ArrayList<String>();
        for (String rootId : roots) {
            if (children.get(rootId).isEmpty()) bare.add(rootId);
            else channels.add(rootId);
        }
        // 子分类多的当入口。实测这比「取前几个」准：量子那个源按顺序第 5 个是
        // 「体育」（4 个子分类），而「综艺片」也是 4 个 —— 按内容量排能稳定地把
        // 电影/连续剧/动漫/综艺这四个主频道挑出来，体育、短剧那些进「其他」。
        Collections.sort(channels, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                return children.get(b).size() - children.get(a).size();
            }
        });

        JSONArray tabList = new JSONArray();
        JSONObject ids = new JSONObject();
        JSONObject labels = new JSONObject();
        JSONObject titles = new JSONObject();
        JSONObject leads = new JSONObject();

        if (!channels.isEmpty()) {
            // dock 一行塞得下的上限：这里 4 个 + 首页 + 我的 = 6 格，再加一个「其他」
            // 是 7 格。360dp 的机器上 365dp 宽的 dock 分成 7 份，每格 ~52dp ——
            // 再多 label 就要被压成两个字了。
            int picked = Math.min(4, channels.size());
            for (int i = 0; i < picked; i++) {
                String rootId = channels.get(i);
                JSONObject root = byId.get(rootId);
                JSONArray slugs = new JSONArray();
                JSONArray names = new JSONArray();
                for (String kid : children.get(rootId)) {
                    slugs.put(kid);
                    names.put(byId.get(kid).optString("type_name", "分类"));
                }
                String name = shortLabel(root.optString("type_name", "分类"));
                tabList.put(tab(rootId, name, iconFor(name)));
                ids.put(rootId, slugs);
                labels.put(rootId, names);
                titles.put(rootId, name);
                leads.put(rootId, "分类来自这个源自己的接口。");
            }
            // 没当上入口的频道 + 本来就没有子分类的顶层，合成「其他」。不是收拾残局 ——
            // 它们在 CMS 里都是有独立内容的分类，丢掉就是丢内容。
            ArrayList<String> rest = new ArrayList<String>();
            for (int i = picked; i < channels.size(); i++) {
                rest.addAll(children.get(channels.get(i)));
            }
            rest.addAll(bare);
            if (!rest.isEmpty()) {
                JSONArray slugs = new JSONArray();
                JSONArray names = new JSONArray();
                for (String kid : rest) {
                    slugs.put(kid);
                    names.put(byId.get(kid).optString("type_name", "分类"));
                }
                tabList.put(tab("more", "其他", "grid"));
                ids.put("more", slugs);
                labels.put("more", names);
                titles.put("more", "其他");
                leads.put("more", "这个源里剩下的那些分类。");
            }
        } else {
            // 一维分类（整棵树都没有 pid），或者顶层全是没有子分类的叶子：
            // 一个入口，全部类型排在子分类条里，那条是横向滚动的。
            JSONArray slugs = new JSONArray();
            JSONArray names = new JSONArray();
            for (String id : order) {
                slugs.put(id);
                names.put(byId.get(id).optString("type_name", "分类"));
            }
            tabList.put(tab("all", "影视库", "grid"));
            ids.put("all", slugs);
            labels.put("all", names);
            titles.put("all", "影视库");
            leads.put("all", "这个源没给出分类层级，全部类型都在下面这一条里。");
        }

        JSONObject ui = new JSONObject();
        ui.put("tabs", tabList);
        ui.put("categoryIds", ids);
        ui.put("categoryLabels", labels);
        // categoryTitles 是**一个字符串**（页面大标题），不是数组 —— 塞数组进去
        // 会被插值成「电影片,连续剧,综艺片,…」四十个名字连成一串。
        // 分类名由上面的 tab 按钮承载，大标题只要一个说法就行。
        ui.put("categoryTitles", titles);
        ui.put("categoryLeads", leads);

        // ── 首页那排标签 + 「全部类型」页 ─────────────────────────────
        //
        // CMS 没有独立的标签体系，它的 type 分类表就是标签表 —— CmsAdapter.tags
        // 把每个 type 打上 group="cms" 交上来。所以：
        //
        //   · tagGroups 必须留一组 "cms"。上一版把这几张表全清空了（那会儿是怕
        //     黄果的「都市/校园/古风」漏过来），结果「全部类型」页整页空白 ——
        //     首页那排胶囊也一个不剩。
        //   · popularTags 挑几个叶子分类当快捷入口。**不能拿顶层**：苹果CMS 查
        //     父类返回 0 条（见 docs 坑 19），点进去会是空的。
        //
        // 挑法是每个频道轮一个，不是从头截 8 个 —— 从头截出来的全是电影片的
        // 子类（动作/喜剧/爱情/科幻…），首页那排看着像只有电影。
        JSONArray tagGroups = new JSONArray();
        JSONArray cmsGroup = new JSONArray();
        cmsGroup.put("cms");
        cmsGroup.put("全部分类");
        tagGroups.put(cmsGroup);
        ui.put("tagGroups", tagGroups);
        ui.put("popularTags", hotTags(byId, channels, children, bare, order, 8));
        // 公共模式那份（红果用的）留空，别让内置的黄果标签漏进来。
        ui.put("publicPopularTags", new JSONArray());
        web.put("ui", ui);

        // ── 字段别名 ──────────────────────────────────────────────────
        //
        // 左边是我们内部的角色名（前端 pick() 用的），右边是**这个族的原始字段名**。
        //
        // 适配器不再认字段名了 —— 它只按这张表把命中的那个键**原样**搬过来，改名交给
        // 前端的 pick()（按顺序取第一个有值的）。所以**同一个族里换字段名的站，改这
        // 张表就行，不用碰 Java**。
        //
        // 上一版这里写的是 ["title"]，而 CmsAdapter 那边把 vod_name 改名成了 title ——
        // 同一个概念写两遍，而且其中一遍在编译期。现在只有这一份。
        //
        // 顺序有讲究：适配器会自己产出的角色（id / score / description）排前面，
        // 它没产出的时候才轮到原始字段兜底。
        JSONObject fields = new JSONObject();
        fields.put("id", arr2("id", "vod_id"));
        fields.put("title", arr2("title", "vod_name"));
        fields.put("cover", arr2("cover", "vod_pic"));
        fields.put("score", arr2("score", "vod_score"));
        fields.put("badge", arr2("badge", "vod_remarks"));
        fields.put("description", arr2("description", "vod_content"));
        fields.put("category", arr2("category", "type_id"));
        // 下面这几个 CMS 没有对应字段，写空数组会被 validate() 拒（候选键列表为空），
        // 所以给一个不会命中的名字 —— 拿不到就是 undefined，前端当空处理。
        fields.put("tags", arr("tags"));
        fields.put("episodeCount", arr("episode_count"));
        fields.put("episodeNumber", arr("ep_num"));
        fields.put("source", arr("video_url"));
        fields.put("duration", arr("duration"));
        web.put("fields", fields);

        config.put("web", web);

        // ── 封面 ──────────────────────────────────────────────────────
        //
        // 两块都必须显式覆盖，否则会从内置模板那边漏过来：
        //
        // ① mode：内置是 aes-cbc（黄果的封面是加密图），而 CMS 返回的是普通
        //    JPEG。照内置那套解密会把图毁掉 —— 表现是封面全是占位图。
        //
        // ② allowedHosts：SiteProfile.coverUrl() 会拿它逐个精确比对 host，
        //    不匹配直接抛「无效封面域名」。它是防 SSRF 的（拿固定密钥去解任意
        //    地址），但 CMS 的封面域名我们事先不知道，只能从探测响应里现取。
        //    实测每个源只用**一个**封面域名（三个源各 20/20 条一致），所以够用。
        //    真出现第二个域名的那天，那部分封面会降级成占位图，frontend 的
        //    日志里有域名，能看出来。
        JSONArray hosts = coverHostsOf(probe);

        // XML 接口（/at/xml/）的列表里**没有 <pic>** —— 封面只在详情响应里。
        // 所以上面那一次提取必然是空的，得单独探一条详情把域名捞出来。
        // 只探一条：同一个源的封面通常只用一个 CDN（实测三个源各 20/20 条一致）。
        if (hosts.length() == 0) {
            String firstId = firstVideoId(probe);
            if (firstId.length() > 0) {
                try {
                    JSONObject one = UserSource.fetch(withParams(url, "ac=detail&ids=" + firstId));
                    hosts = coverHostsOf(one);
                } catch (Exception ignored) {}
            }
        }

        if (hosts.length() == 0) {
            // 提取不到就当场说清楚。放一个空数组过去的话，SiteProfile 构造时会抛
            // 「缺少封面域名」—— 那是在 Activity.onCreate 里，表现是一装完就闪退，
            // 用户完全不知道哪里错了。
            throw new IOException("从响应里提取不到封面域名 —— 这个接口的内容结构不标准，换一个源试试");
        }
        JSONObject cover = new JSONObject();
        cover.put("mode", "plain");
        cover.put("hostRewrites", new JSONObject());
        cover.put("allowedHosts", hosts);
        config.put("cover", cover);

        return config;
    }
}
