package com.huangguo.mobile;

import static com.huangguo.mobile.UserSource.log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 樱花动漫（yhdm.one）的 {@code /api/*} 分流。
 *
 * ── 站点形状（2026-10-18 实测，Mac curl，全部裸请求可拉）────────────────
 *
 *   GET /                            首页，60 张卡（最新更新 + 四个地区分区）
 *   GET /latest/                     最近更新，一列 128 条，无分页
 *   GET /list/?{查询串}              列表第 1 页，18 条
 *   GET /list/?{查询串}&page=N       列表第 N 页（page=1 与短形式等价，不会 301）
 *   GET /search?q={词}               搜索（page 参数**会被忽略**，永远只有第一页）
 *   GET /vod/{id}.html               详情（卡片用 /cover2/{id}.jpg，详情页 /cover/{id}.jpg）
 *   GET /vod-play/{id}/{ep}.html     播放页（ep = ep1 / ep163 / zheng_pian / hd）
 *   GET /_get_plays/{id}/{ep}        JSON：{video_plays:[{play_data, src_site}], html_content}
 *   GET /cover2/{id}.jpg             封面（同域，裸 200）
 *
 * ── 三个实测钉死的坑 ──────────────────────────────────────────────────
 *
 *   ① 搜索只有一页。`?page=2` 服务器收下了但内容不变（实测两种写法字节级一致），
 *      所以 search 永远返回单页，hasMore=false。别配 page 参数装作能翻。
 *
 *   ② 封面懒加载。列表页 img 是 class="lazy" + data-original="/cover2/…jpg"，
 *      **src 是占位图**；首页部分 img 直接 src=。两处都要抓，先 data-original 后 src。
 *
 *   ③ 播放地址必须每次现取。/_get_plays 返回的 m3u8 挂在第三方采集站的域名上
 *      （ppqrrs.com / rstu6.com / wgslsw.com / bfikuncdn.com / modujx10.com …），
 *      线路列表会变、地址会换。详情元数据可以缓存，play() 必须穿透 ——
 *      和 Mrds66Adapter / Hanime1Adapter 同一条规矩。
 *
 * ── 多线路怎么给 ──────────────────────────────────────────────────────
 *
 * 站点一集挂多条线路（WJ / SN / YH…，src_site 区分），每条一个 m3u8。
 * 默认 source 取第一条，全部进 lines（label 用 src_site）。
 * episode 标识用站点自己的 ep 段（ep1 / ep163 / zheng_pian / hd），
 * 详情页解析时原样收进 episodes[i].id，播放时拼回 /_get_plays/{id}/{ep}。
 * 前端 episodes[].number 必须是数字（feed-player 用它拼选集格子），
 * 所以 number 给序号 1..N，站点 ep 段放 episode.id 里。
 */
public final class YhdmAdapter {

    private static final int TIMEOUT = 15;
    private static final int HOME_FEATURED_LIMIT = 8;

    /** 线路探测器的注入点：测试里换成「全都说活」，就不用联网。 */
    interface LineProber { boolean alive(String url); }

    private YhdmAdapter() {}

    private static final String DEFAULT_HOST = "https://yhdm.one";

    // ── 对外入口 ──────────────────────────────────────────────────────

    public static byte[] fetch(String path, SiteProfile profile) throws Exception {
        String base = base(profile);
        JSONObject result;

        if (path.startsWith("/api/home")) {
            result = home(base);
        } else if (path.startsWith("/api/search")) {
            result = search(base, HtmlKit.param(path, "q"));
        } else if (path.startsWith("/api/list")) {
            result = list(base, HtmlKit.param(path, "cate"), HtmlKit.param(path, "query"), HtmlKit.page(path));
        } else if (path.startsWith("/api/reconcile")) {
            // 前端进每个网格页都会调一次，拿详情页真值盖掉列表页的标题/封面。
            // 这个站的列表页本来就是准的（标题和封面直接来自同一张卡），
            // 所以不需要校正 —— 但**必须应答**：不实现的话每次进列表都抛
            // 「适配器不认识的路径」，真机日志里它比正常请求还密，会把真正的
            // 错误淹掉（missav 那次踩过同一个坑）。
            result = reconcile(HtmlKit.param(path, "ids"));
        } else if (path.startsWith("/api/tags")) {
            result = tags();
        } else if (path.startsWith("/api/detail")) {
            result = detail(base, videoId(path));
        } else if (path.startsWith("/api/play")) {
            result = play(base, videoId(path), HtmlKit.param(path, "ep"));
        } else {
            throw new Exception("yhdm 适配器不认识的路径：" + path);
        }

        JSONObject envelope = new JSONObject();
        envelope.put("data", result);
        return envelope.toString().getBytes("UTF-8");
    }

    static String base(SiteProfile profile) {
        return AdapterIo.base(profile, DEFAULT_HOST);
    }

    // ── 各个动作 ──────────────────────────────────────────────────────

    /**
     * 首页。打 /，一次请求。60 张卡按位置切：前 8 条进 featured，其余进 latest。
     * 站点没有分区接口，cate_rec 留空 —— adaptHome 走 featured 那份。
     */
    private static JSONObject home(String base) throws Exception {
        JSONArray all = parseCards(AdapterIo.get(base + "/"), base);

        JSONArray featured = new JSONArray();
        JSONArray latest = new JSONArray();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject v = all.optJSONObject(i);
            if (v == null) continue;
            String id = v.optString("id", "");
            if (id.length() == 0 || !seen.add(id)) continue;
            if (featured.length() < HOME_FEATURED_LIMIT) featured.put(v);
            else latest.put(v);
        }

        JSONObject out = new JSONObject();
        out.put("featured", featured);
        out.put("latest", latest);
        out.put("cate_rec", new JSONArray());
        return out;
    }

    /**
     * 分类列表。cateId 是站点 /list 的查询串 slug（jp / cn / 剧场版 / latest…）。
     *
     * 「latest」特殊：路由是 /latest/ 而不是 /list/?type=latest，单独拼。
     * 其余拼成 /list/?country=… /list/?type=… /list?genre=… —— CATEGORIES 表里
     * 存的就是完整查询串。
     */
    private static JSONObject list(String base, String cate, String query, int page) throws Exception {
        String q = query.length() > 0 ? query : CATEGORIES.get(cate);
        if (q == null || q.length() == 0) q = "country=jp";

        String url;
        if ("latest".equals(cate) || "/latest/".equals(q)) {
            // /latest/ 没有分页（实测 ?page=2 与第一页字节一致），一列 128 条。
            url = base + "/latest/";
        } else {
            url = base + "/list";
            if (q.startsWith("/list")) {           // 完整路径形式（more 组用）
                url = base + q;
            } else {
                url = url + (q.startsWith("?") ? "" : "/?") + q;
            }
            if (page > 1) url = url + "&page=" + page;
        }

        String html = AdapterIo.get(url);
        JSONArray items = parseCards(html, base);

        // 分页形状收口到 AdapterShape：前端 nextPageOf() 只认
        // pagination.page/pages，各适配器自己拼就会长出第二套（见 Missav 那次）。
        int pages = "latest".equals(cate) ? 1 : totalPages(html);
        return AdapterShape.listing(items, page, pages);
    }

    /**
     * 搜索。路由 /search?q={词}。
     *
     * 实测：page 参数服务器收下但内容不变（第一页和第二页字节一致），
     * 所以这里永远只打第一页、返回 hasMore=false。前端显示「已经到底了」，
     * 这是站点行为，不是适配器丢数据。
     */
    private static JSONObject search(String base, String keyword) throws Exception {
        if (keyword == null || keyword.trim().length() == 0) {
            throw new Exception("搜索词为空，不发请求");
        }
        String html = AdapterIo.get(base + "/search?q=" + HtmlKit.enc(keyword.trim()));
        JSONArray items = parseCards(html, base);

        // 站点忽略 page 参数，永远只有第一页 —— 按单页返回
        return AdapterShape.listing(items, 1, 1);
    }

    /**
     * 列表校正（前端自动调）。**零网络**：只把问到的 id 原样回成空壳，
     * 前端拿到就会记进 reconciledFixes，之后不再问同一批。
     *
     * 回空壳而不是空数组，是有意的：前端 {@code applyFixes} 遇到空 items 时
     * 什么都不记，下一屏还会再问一遍 —— 那这条错误就永远存在。
     */
    static JSONObject reconcile(String ids) throws Exception {
        JSONArray items = new JSONArray();
        if (ids != null && ids.length() > 0) {
            for (String raw : ids.split(",")) {
                String id = raw == null ? "" : raw.trim();
                if (id.length() == 0) continue;
                JSONObject one = new JSONObject();
                one.put("id", id);
                items.put(one);
            }
        }
        JSONObject out = new JSONObject();
        out.put("items", items);
        return out;
    }

    /**
     * 标签页。站点的「标签」就是导航那排分类（国家 / 类型 / 地区 / 年份）。
     * 全部塞一个组，group 直接给 category —— renderTags 按它分区。
     */
    private static JSONObject tags() throws Exception {
        JSONObject out = new JSONObject();
        JSONArray groups = new JSONArray();

        JSONObject group = new JSONObject();
        group.put("code", "category");
        JSONArray items = new JSONArray();
        for (String[] c : CATEGORIES_TABLE) {
            JSONObject tag = new JSONObject();
            tag.put("slug", c[0]);
            tag.put("tagName", c[1]);
            tag.put("group", "category");
            items.put(tag);
        }
        group.put("tags", items);
        groups.put(group);

        out.put("items", new JSONArray());
        out.put("categories", groups);
        return out;
    }

    /**
     * 详情。元数据在 detail-left 的 meta 块和「动漫介绍」里。
     *
     * 能拿到的：
     *   标题   <h1 class="names">…</h1>
     *   封面   <img src="/cover/{id}.jpg">
     *   元信息 原名 / 别名 / 地区 / 年代 / 类型（行文本，逐行剥）
     *   简介   「动漫介绍」tab 下那个 div
     *   剧集   「全部剧集」ep-panel 里的 <a title='第01集' href='/vod-play/{id}/ep1.html'>
     */
    private static JSONObject detail(String base, String id) throws Exception {
        if (id.length() == 0) throw new Exception("yhdm 详情缺少 id");
        JSONObject cached = META_CACHE.get(id);
        if (cached != null) return new JSONObject(cached.toString());

        String html = AdapterIo.get(base + "/vod/" + HtmlKit.enc(id) + ".html");
        JSONObject out = detailFrom(id, html, base);
        cache(id, out);
        return new JSONObject(out.toString());
    }

    /**
     * 详情页 HTML → 我们的详情响应。
     *
     * <p>从 detail() 里拆出来，只为一件事：<b>响应形状能被离线测试</b>。
     * 拆之前 detail() 里网络和解析是缠在一起的，测试只能测到「解析出几个字符串」，
     * 测不到「返回的键名和 web.fields 别名表对不对得上」—— 而后者正是
     * 2026-10-18 那个「选集格子全是 undefined」的 bug。
     */
    static JSONObject detailFrom(String id, String html, String base) throws Exception {
        String title = h1Title(html);
        if (title.length() == 0) {
            throw new Exception("详情页里没找到标题（id=" + id + "）。可能：影片已删除、或站点改版。");
        }

        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("title", title);
        out.put("cover", coverOfDetail(html, base));
        out.put("description", intro(html));
        out.put("duration", "");
        out.put("score", "");
        out.put("category", detailCategory(html));
        out.put("tags", detailTags(html));

        JSONObject extra = new JSONObject();
        String meta = metaLines(html);
        if (meta.length() > 0) extra.put("meta", meta);
        String badge = updateBadge(html);
        if (badge.length() > 0) out.put("badge", badge);
        out.put("extra", extra);

        // episodes：number 给序号（前端格子只吃数字），站点 ep 段放 id。
        // play 按这个 id 拼 /_get_plays/{id}/{ep}。
        JSONArray epAnchors = episodeAnchors(html);
        if (epAnchors.length() == 0) {
            throw new Exception("详情页里没有剧集（id=" + id + "）。可能：影片下架。");
        }
        JSONArray episodes = new JSONArray();
        JSONArray epIds = new JSONArray();
        for (int i = 0; i < epAnchors.length(); i++) {
            JSONObject a = epAnchors.optJSONObject(i);
            if (a == null) continue;
            JSONObject ep = new JSONObject();
            ep.put("episodeNumber", i + 1);
            ep.put("id", a.optString("ep", ""));
            ep.put("title", a.optString("title", ""));
            ep.put("duration", "");
            episodes.put(ep);
            epIds.put(a.optString("ep", ""));
        }
        out.put("episodes", episodes);
        out.put("episodeCount", episodes.length());
        out.put("episodeIds", epIds);
        rememberEps(id, epIds);

        out.put("related", related(html, base, id));

        // 形状收口：episodes 保证是数组、episodeCount 和它对齐、单集站也有一条。
        return AdapterShape.detail(out);
    }

    /**
     * 播放地址。每次实时打 /_get_plays/{id}/{ep}，零缓存。
     *
     * 返回多条线路（video_plays[].play_data 是第三方采集站 m3u8 直链）。
     * 默认 source 取第一条；全部进 lines，label 用 src_site（WJ/SN/YH…）。
     */
    private static JSONObject play(String base, String id, String ep) throws Exception {
        if (id.length() == 0) throw new Exception("yhdm 播放缺少 id");

        // ── 前端只传数字 ────────────────────────────────────────────────
        //
        // 选集格子在 app.js 里是 `data-episode="${item.number}"`，number 是我们
        // 详情响应里的序号 1..N。站点自己的 ep 段（ep1 / ep163 / zheng_pian / hd）
        // 到不了播放请求 —— 所以这里要把序号翻回 ep 段：detail() 解析时把
        // epIds 存进 EP_IDS，play 按序号取；取不到（缓存被清 / 没进过详情）
        // 就按 ep{序号} 拼，多集番剧的 ep 段正好就是 ep{序号}，
        // 只有「正片 / HD」这种命名单集剧场版会失手，那种先点一次详情就补上了。
        String siteEp = epOf(id, ep);

        String body = AdapterIo.get(base + "/_get_plays/" + HtmlKit.enc(id) + "/" + HtmlKit.enc(siteEp));
        JSONObject json = new JSONObject(body);
        return playFrom(id, ep, json.optJSONArray("video_plays"), new LineProber() {
            @Override public boolean alive(String url) { return probeLine(url); }
        });
    }

    /**
     * 播放响应的形状组装 —— **不联网**，线路探测从外面注入。
     *
     * <p>拆出来的理由和 {@link #detailFrom} 一样：这个响应里的键名
     * （`source` / `url` / `videoUrl` / `episodeNumber`）必须和
     * `web.fields` 的别名表对得上，而那条契约以前只能靠真机点播放来验。
     * 现在测试可以喂一份真实 `_get_plays` 的 JSON、探测器全返回 true，
     * 就能断言「前端 pick('source') 拿得到值」。
     *
     * @param plays  `/ _get_plays` 返回的 video_plays 数组
     * @param prober 线路探测；传 null 表示全信（测试用）
     */
    static JSONObject playFrom(String id, String ep, JSONArray plays, LineProber prober)
            throws Exception {
        String siteEp = epOf(id, ep);
        // ── 线路要挑活的 ────────────────────────────────────────────────
        //
        // 站点一次给十几条线路（ikzy/hnzy/jyzy/…/yhzy），但**死线路不少**：
        // ffzy / feifei / rstu6 这几家常年 403（连 Mac 裸 curl 都 403，是线路
        // 自己的防盗链/下线，不是我们的请求问题）。站点不排序、第一条也不保证
        // 活 —— 2026-10-18 实测第一集的默认线路就是 403 那条。
        //
        // 内置播放器拿到 source 就开播，挂了只会报「解不了」，不会自动换线
        // （lines 只在 AGE动漫 的 wasm 解析那条路上有用）。所以挑选放在适配器：
        // 每条线路发一个短超时的 GET，认 #EXTM3U 头，**第一条活确的当 source**，
        // 全部线路照常进 lines（活的排前面，label 不变）。探测是顺序的、
        // 单条 3 秒超时，最坏十几条全死时多花几秒 —— 那本来就是要报错的场景。
        //
        // 探测结果进缓存（id+ep → 首条活线路 index，5 分钟），同集重试不重探。
        JSONArray lines = new JSONArray();
        String source = "";
        if (plays != null) {
            // 先照原顺序收齐
            java.util.List<JSONObject> all = new java.util.ArrayList<JSONObject>();
            for (int i = 0; i < plays.length(); i++) {
                JSONObject one = plays.optJSONObject(i);
                if (one == null) continue;
                String url = one.optString("play_data", "").trim();
                if (url.length() == 0) continue;
                String site = one.optString("src_site", "");
                all.add(AdapterShape.line(i, site.length() > 0 ? site.toUpperCase() : "", url));
            }

            // ① 近 10 分钟挑过的直接复用 —— 一集看第二遍、拖进度重连都走这里，
            //    不用再验一遍（验证一轮要好几秒）。
            String picked = pickCacheGet(id + ":" + siteEp);
            if (picked != null) {
                source = picked;
                for (JSONObject line : all) lines.put(line);
            } else {
                // ② 顺序验，**验到第一条能播就停**。
                //
                //    不能全部验完：12 条 × 主列表+子列表+分片三段，最坏三十多秒，
                //    用户点播放要干等。站点把能用的线路通常排在前面，验到就收。
                //    剩下的原样跟进 lines（万一第一条待会儿又挂了，界面里还能换）。
                int tried = 0;
                for (JSONObject line : all) {
                    tried++;
                    if (prober == null || prober.alive(line.optString("url", ""))) {
                        source = line.optString("url", "");
                        // 命中这条排第一，其余保持原顺序跟在后面
                        lines.put(line);
                        break;
                    }
                    if (tried >= MAX_PROBE_LINES) break;
                }
                for (JSONObject line : all) {
                    if (line.optString("url", "").equals(source)) continue;
                    lines.put(line);
                }
                if (source.length() == 0 && all.size() > 0) {
                    // 一条都没验通（可能整站 CDN 都在抖）：退回原顺序第一条，
                    // 让播放器自己去试 —— 比直接报「没有可用线路」好。
                    source = all.get(0).optString("url", "");
                    log("yhdm 线路探测全军覆没，退回默认 " + source);
                }
                if (source.length() > 0) pickCachePut(id + ":" + siteEp, source);
            }
        }
        if (source.length() == 0) {
            throw new Exception("这一集没有可用线路（id=" + id + " ep=" + ep + "）。"
                    + "可能：线路全挂了、或站点改了 _get_plays 的返回。");
        }

        return AdapterShape.play(source, lines, epIndex(ep));
    }

    /**
     * 线路活性探测 —— 一路验到**分片**，不是只看主列表。
     *
     * ── 为什么必须验到分片 ──────────────────────────────────────────────
     * 2026-10-18 真机：12 条线路里
     *
     *   ikzy  主列表 200、子列表 200、第一个分片 **TLS 被掐**（kkzycdn.com:65）
     *   mdzy  全链 206，能用
     *   hnzy / jyzy / xlzy    子列表就挂
     *   jszy / lzzy / ffzy / ukzy / wjzy2 / snzy   主列表就挂
     *
     * 只验主列表的话，挑中的是 ikzy —— 而它恰好是唯一「主列表通、分片不通」的那条。
     * 用户看到的仍然是「点开播放，转两秒，报解不了」。所以判据定在分片：
     * 拿第一个分片的前 2KB，认 200/206。
     *
     * ── 为什么顺带把分片域名注册成直连 ──────────────────────────────────
     * 这一族 CDN 走外网代理反而死（见 DirectHosts 里那段实测）。探测时先按
     * **直连**试分片，通了就把域名记进 DirectHosts —— 播放时 VlcRelay 见到它
     * 直接绕开中继让 VLC 自己连，连代理都不挂。试直连不通再退回走代理。
     *
     * @return 能播返回 true
     */
    static boolean probeLine(String masterUrl) {
        try {
            // ① 主列表
            String master = AdapterIo.get(masterUrl);
            if (!master.startsWith("#EXTM3U")) return false;

            // ② 子列表（第一条码率 / 或本身就是媒体列表）
            String variantUrl = firstUri(master, masterUrl);
            if (variantUrl == null) return false;
            String variant = AdapterIo.get(variantUrl);
            if (!variant.startsWith("#EXTM3U")) return false;

            // ③ 第一个分片
            String segUrl = firstUri(variant, variantUrl);
            if (segUrl == null) return false;

            String segHost = YhdmSite.hostOf(segUrl);
            if (segHost.length() == 0) return false;

            // 先直连试（这一族 CDN 的正常走法），通了就登记，播放时直接受益
            if (segmentFetchable(segUrl, true)) {
                DirectHosts.add(segHost, "樱花动漫 播放分片（探测时直连可用）");
                return true;
            }
            // 直连不通再看走代理能不能拿到（有的 CDN 只对境外 IP 开放）
            return segmentFetchable(segUrl, false);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 播放列表里第一条非注释、非空行，解析成绝对地址。 */
    static String firstUri(String playlist, String baseUrl) {
        String[] lines = playlist.split("\\r?\\n");
        for (String raw : lines) {
            String t = raw.trim();
            if (t.length() == 0 || t.startsWith("#")) continue;
            try {
                return new java.net.URI(baseUrl).resolve(t).toString();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 分片能不能拿到字节。只看前 2KB —— 判的是「链通不通」，不是「片子完不完整」，
     * 整块拉一个 1.3MB 的 ts 会把起播拖到没法忍。
     *
     * @param direct true = 强制直连（临时把域名塞进 DirectHosts 再取）
     */
    static boolean segmentFetchable(String url, boolean direct) {
        String host = YhdmSite.hostOf(url);
        boolean already = DirectHosts.isDirect(host);
        if (direct && !already) DirectHosts.add(host, "樱花动漫 分片探测");
        try {
            byte[] body = AiiHttp.getRaw(url, SiteCfg.browserHeaders(), 6);
            // 分片是二进制；拿到 2KB 以上就认为通了。空响应/错误页都过不了这一关。
            return body != null && body.length >= 1024;
        } catch (Throwable e) {
            return false;
        }
    }

    /** ep 段 → 序号。ep1 → 1，ep163 → 163，zheng_pian/hd → 1。 */
    static int epIndex(String ep) {
        Matcher m = Pattern.compile("ep(\\d+)").matcher(ep == null ? "" : ep);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    // ── 解析 ──────────────────────────────────────────────────────────

    /**
     * 卡片解析。
     *
     * 列表页是「横排卡」：<li><a href="/vod/{id}.html"><img data-original=…alt=标题></a>
     * <a href="/vod/{id}.html" title=标题><h6>标题</h6></a> …</li>；
     * 首页是「竖排卡」：<li class="col-3"><a href="/vod-play/…"><img src=…alt=标题></a>
     * <a href="/vod/{id}.html" title=标题>…</a></li>。
     *
     * 所以一个 <li> 一个 <li> 地切，先收 /vod/ 链接拿 id 和 title，
     * 再在同块里抓图（data-original 优先，懒加载的 src 是占位图）。
     * 首页卡片那个 /vod-play/ 链接是「播最新一集」的快捷方式，忽略 ——
     * 我们统一走详情页，ep 空间才一致。
     */
    static JSONArray parseCards(String html, String base) {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<String>();

        Matcher m = LI_RE.matcher(html);
        while (m.find()) {
            String chunk = m.group(0);

            Matcher idm = VOD_ID_RE.matcher(chunk);
            if (!idm.find()) continue;
            String id = idm.group(1);
            if (id == null || id.length() == 0 || !seen.add(id)) continue;

            String title = cardTitle(chunk);
            if (title.length() == 0) continue;

            JSONObject v = new JSONObject();
            try {
                v.put("id", id);
                v.put("title", title);
                v.put("cover", cardCover(chunk, base));

                String badge = cardBadge(chunk);
                if (badge.length() > 0) v.put("badge", badge);

                String cate = cardCategory(chunk);
                if (cate.length() > 0) v.put("category", cate);
            } catch (Exception ignored) {}
            out.put(v);
        }
        return out;
    }

    /** 卡片标题：title 属性，退到 <h6> / <h5> 文本。 */
    static String cardTitle(String chunk) {
        Matcher m = TITLE_ATTR_RE.matcher(chunk);
        if (m.find()) {
            String s = HtmlKit.unescape(m.group(1)).replaceAll("\\s+", " ").trim();
            if (s.length() > 0) return s;
        }
        m = H_TITLE_RE.matcher(chunk);
        if (m.find()) {
            String s = HtmlKit.unescape(HtmlKit.stripHtml(m.group(1))).replaceAll("\\s+", " ").trim();
            if (s.length() > 0) return s;
        }
        // 首页竖排卡的 alt 也是标题
        m = ALT_RE.matcher(chunk);
        if (m.find()) {
            String s = HtmlKit.unescape(m.group(1)).replaceAll("\\s+", " ").trim();
            if (s.length() > 0) return s;
        }
        return "";
    }

    /**
     * 卡片封面。列表页 img class="lazy" 用 data-original 存真地址（src 是占位）；
     * 首页 img 直接 src=。data-original 优先。
     */
    static String cardCover(String chunk, String base) {
        Matcher m = DATA_ORIGINAL_RE.matcher(chunk);
        if (!m.find()) m = IMG_SRC_RE.matcher(chunk);
        if (!m.find()) return "";
        String src = m.group(1).trim();
        if (src.length() == 0) return "";
        if (src.startsWith("http")) return YhdmSite.originRoot(src);
        if (src.startsWith("//")) return "https:" + src;
        if (src.startsWith("/")) return base + src;
        return base + "/" + src;
    }

    /** 卡片角标：首页竖排卡的 <span>更至第03集</span>。 */
    static String cardBadge(String chunk) {
        Matcher m = EP_TIP_RE.matcher(chunk);
        if (!m.find()) return "";
        return HtmlKit.unescape(HtmlKit.stripHtml(m.group(1))).replaceAll("\\s+", " ").trim();
    }

    /** 卡片分类行：列表卡里 <div class="small"> 动画 </div>。 */
    static String cardCategory(String chunk) {
        Matcher m = CATE_LINE_RE.matcher(chunk);
        if (!m.find()) return "";
        return HtmlKit.unescape(HtmlKit.stripHtml(m.group(1))).replaceAll("\\s+", " ").trim();
    }

    /** 详情页封面：<img src="/cover/{id}.jpg"。 */
    static String coverOfDetail(String html, String base) {
        Matcher m = DETAIL_COVER_RE.matcher(html);
        if (m.find()) return base + m.group(1);
        return "";
    }

    /** 详情页 h1：<h1 class="names" …>标题</h1>。 */
    static String h1Title(String html) {
        Matcher m = H1_NAMES_RE.matcher(html);
        if (!m.find()) return "";
        return HtmlKit.unescape(HtmlKit.stripHtml(m.group(1))).replaceAll("\\s+", " ").trim();
    }

    /**
     * 简介：「动漫介绍」tab 下面那个 <div class="small" …>正文</div>。
     * 起点定在 tab 标题之后，正文块在「推荐动漫」之前结束。
     */
    static String intro(String html) {
        int head = html.indexOf("动漫介绍");
        if (head < 0) return "";
        int tail = html.indexOf("推荐动漫", head);
        if (tail < 0) tail = Math.min(html.length(), head + 8000);
        String block = html.substring(head, tail);
        Matcher m = INTRO_DIV_RE.matcher(block);
        if (!m.find()) return "";
        String s = HtmlKit.unescape(HtmlKit.stripHtml(m.group(1))).replaceAll("\\s+", " ").trim();
        // 站点正文里也可能带一个「简介：」前缀（列表卡那样），有就剥。
        if (s.startsWith("简介：")) s = s.substring(3);
        return s;
    }

    /** 详情页 meta 块：原名 / 别名 / 地区 / 年代，整块拼成一段行文本。 */
    static String metaLines(String html) {
        int start = html.indexOf("原名：");
        if (start < 0) return "";
        int end = html.indexOf("更新至", start);
        if (end < 0 || end - start > 4000) end = Math.min(html.length(), start + 2000);
        String block = html.substring(start, end);
        Matcher m = META_ROW_RE.matcher(block);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String s = HtmlKit.unescape(HtmlKit.stripHtml(m.group(1))).replaceAll("\\s+", " ").trim();
            if (s.length() == 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    /** 「更新至 第01集」那行。 */
    static String updateBadge(String html) {
        Matcher m = UPDATE_BADGE_RE.matcher(html);
        if (!m.find()) return "";
        return HtmlKit.unescape(HtmlKit.stripHtml(m.group(1))).replaceAll("\\s+", " ").trim();
    }

    /** 详情页类型标签：meta 块里 /list?genre=… 链接的文本。 */
    static JSONArray detailTags(String html) {
        JSONArray out = new JSONArray();
        int start = html.indexOf("原名：");
        int end = start < 0 ? 0 : html.indexOf("更新至", start);
        if (end < 0) end = Math.min(html.length(), start + 4000);
        String block = start < 0 ? "" : html.substring(start, end);
        Matcher m = GENRE_LINK_RE.matcher(block);
        Set<String> seen = new HashSet<String>();
        while (m.find()) {
            String s = HtmlKit.unescape(HtmlKit.stripHtml(m.group(1))).replaceAll("\\s+", " ").trim();
            if (s.length() == 0 || !seen.add(s)) continue;
            out.put(s);
        }
        return out;
    }

    /** 详情页分类面包屑：/list/?country=cn 那段链接文本。 */
    static String detailCategory(String html) {
        Matcher m = BREADCRUMB_RE.matcher(html);
        if (!m.find()) return "";
        return HtmlKit.unescape(HtmlKit.stripHtml(m.group(1))).replaceAll("\\s+", " ").trim();
    }

    /**
     * 剧集锚点：「全部剧集」ep-panel 里的 <a title='第01集' href='/vod-play/{id}/{ep}.html'>。
     *
     * 切块从 ep-panel 开始（详情页和播放页都有这个 class），只收这一块 ——
     * 页面下面「其他播放源」的 <a class="swa"> 不是剧集，别混进来。
     */
    static JSONArray episodeAnchors(String html) throws Exception {
        JSONArray out = new JSONArray();
        int start = html.indexOf("全部剧集");
        if (start < 0) start = html.indexOf("ep-panel");
        if (start < 0) return out;
        int end = html.indexOf("</section>", start);
        if (end < 0) end = Math.min(html.length(), start + 20000);
        String block = html.substring(start, end);

        Matcher m = EP_ANCHOR_RE.matcher(block);
        Set<String> seen = new HashSet<String>();
        while (m.find()) {
            String title = HtmlKit.unescape(m.group(1)).trim();
            String ep = m.group(2);
            if (ep.length() == 0 || !seen.add(ep)) continue;
            JSONObject a = new JSONObject();
            a.put("ep", ep);
            a.put("title", title.length() > 0 ? title : ep);
            out.put(a);
        }
        return out;
    }

    /** 详情页「推荐动漫」里同结构卡，去掉自己。 */
    static JSONArray related(String html, String base, String selfId) {
        int start = html.indexOf("推荐动漫");
        if (start < 0) return new JSONArray();
        String block = html.substring(start);
        JSONArray all = parseCards(block, base);
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject v = all.optJSONObject(i);
            if (v == null) continue;
            if (selfId.equals(v.optString("id", ""))) continue;
            out.put(v);
        }
        return out;
    }

    /** 「页次：1/215页」→ 215。 */
    static int totalPages(String html) {
        Matcher m = PAGE_TOTAL_RE.matcher(html);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1).trim());
        } catch (Exception e) {
            return 0;
        }
    }
    /** 从 `/api/detail/{id}` / `/api/play/{id}?ep=…` 里取 id。id 是纯数字。 */
    static String videoId(String path) {
        Matcher m = Pattern.compile("/api/(?:detail|play)/([^/?#]+)").matcher(path);
        return m.find() ? m.group(1) : "";
    }

    // ── 缓存 ──────────────────────────────────────────────────────────

    /** 最多验几条线路。12 条里前 4 条通常就有一条能用，再多是浪费起播时间。 */
    private static final int MAX_PROBE_LINES = 5;

    /** 「哪条线路能播」的短缓存：id:ep → url，10 分钟。线路会变，不能久留。 */
    private static final java.util.Map<String, long[]> PICKED_AT = new java.util.HashMap<String, long[]>();
    private static final java.util.Map<String, String> PICKED = new java.util.HashMap<String, String>();
    private static final long PICK_TTL = 10 * 60 * 1000L;

    private static String pickCacheGet(String key) {
        synchronized (PICKED) {
            String url = PICKED.get(key);
            long[] at = PICKED_AT.get(key);
            if (url == null || at == null) return null;
            if (System.currentTimeMillis() - at[0] > PICK_TTL) {
                PICKED.remove(key);
                PICKED_AT.remove(key);
                return null;
            }
            return url;
        }
    }

    private static void pickCachePut(String key, String url) {
        synchronized (PICKED) {
            if (PICKED.size() > 120) { PICKED.clear(); PICKED_AT.clear(); }
            PICKED.put(key, url);
            PICKED_AT.put(key, new long[] { System.currentTimeMillis() });
        }
    }

    /** id → 剧集 ep 段数组（ep1 / zheng_pian / …）。detail() 写，play() 读。 */
    private static final java.util.Map<String, JSONArray> EP_IDS =
            new java.util.HashMap<String, JSONArray>();

    private static void rememberEps(String id, JSONArray epIds) {
        synchronized (EP_IDS) {
            if (EP_IDS.size() > 200) EP_IDS.clear();
            EP_IDS.put(id, epIds);
        }
    }

    /** 序号（1-based）→ 站点 ep 段。查不到就退 ep{序号}。 */
    static String epOf(String id, String epParam) {
        String ep = epParam == null || epParam.length() == 0 ? "1" : epParam;
        // 已经是站点形态（ep3 / zheng_pian）就原样用
        if (ep.matches("ep\\d+") || !ep.matches("\\d+")) return ep;
        try {
            int no = Integer.parseInt(ep);
            JSONArray ids;
            synchronized (EP_IDS) { ids = EP_IDS.get(id); }
            if (ids != null && no >= 1 && no <= ids.length()) {
                String site = ids.optString(no - 1, "");
                if (site.length() > 0) return site;
            }
        } catch (Exception ignored) {}
        return "ep" + ep;
    }

    /** 详情元数据缓存。play() 不读它 —— 线路地址会换（见类注释 ③）。 */
    private static final java.util.Map<String, JSONObject> META_CACHE =
            new java.util.HashMap<String, JSONObject>();

    private static void cache(String id, JSONObject meta) {
        synchronized (META_CACHE) {
            if (META_CACHE.size() > 200) META_CACHE.clear();
            META_CACHE.put(id, meta);
        }
    }

    // ── 分类表 ────────────────────────────────────────────────────────

    /**
     * slug → /list 的查询串。「latest」不是 /list 的分类（走 /latest/ 路由），
     * 它的值就是自己的路由，list() 里特判。
     */
    static final String[][] CATEGORIES_TABLE = {
        { "jp",      "country=jp" },
        { "cn",      "country=cn" },
        { "us",      "country=us" },
        { "movie",   "type=movie" },
        { "剧场版",  "type=剧场版" },
        { "OVA",     "type=OVA" },
        { "TV",      "type=TV" },
        { "latest",  "/latest/" },
    };

    /** slug → 查询串，list() 用。 */
    static final java.util.Map<String, String> CATEGORIES =
            new java.util.HashMap<String, String>();
    static {
        for (String[] c : CATEGORIES_TABLE) CATEGORIES.put(c[0], c[1]);
    }

    // ── 正则 ──────────────────────────────────────────────────────────

    /** 一个 <li …>…</li> 块。卡片全在 li 里，这是最外层可重复的切分单元。 */
    private static final Pattern LI_RE = Pattern.compile(
            "<li\\b[^>]*>.*?</li>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** /vod/{id}.html —— 只认纯数字 id。 */
    private static final Pattern VOD_ID_RE = Pattern.compile(
            "/vod/(\\d+)\\.html");

    /** <a … title="标题" href="/vod/…">。 */
    private static final Pattern TITLE_ATTR_RE = Pattern.compile(
            "title=[\"']([^\"']+)[\"'][^>]*href=[\"']/vod/\\d+\\.html");
    /** <h6> / <h5> 里的标题文本（列表卡的第二条路）。 */
    private static final Pattern H_TITLE_RE = Pattern.compile(
            "<h[56][^>]*>(.*?)</h[56]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** img 的 alt（首页竖排卡，标题在 alt 里）。 */
    private static final Pattern ALT_RE = Pattern.compile(
            "alt=[\"']([^\"']{2,})[\"']");

    /** data-original="/cover2/….jpg"（懒加载真地址）。 */
    private static final Pattern DATA_ORIGINAL_RE = Pattern.compile(
            "data-original=[\"']([^\"']+)[\"']");
    /** src="…jpg"（首页竖排卡直接给）。 */
    private static final Pattern IMG_SRC_RE = Pattern.compile(
            "<img[^>]*src=[\"']([^\"']*\\.(?:jpg|jpeg|png|webp))[\"']",
            Pattern.CASE_INSENSITIVE);

    /** 首页竖排卡角标：<span>更至第03集</span>（img-overlay 里）。 */
    private static final Pattern EP_TIP_RE = Pattern.compile(
            "<span>(更至[^<]*)</span>");

    /** 列表卡的分类行：<div class="small"> 动画 </div>。 */
    private static final Pattern CATE_LINE_RE = Pattern.compile(
            "<div class=\"small\">\\s*([^<]*?)\\s*</div>");

    /** 详情页封面：<img … src="/cover/{id}.jpg"。 */
    private static final Pattern DETAIL_COVER_RE = Pattern.compile(
            "<img[^>]*src=[\"'](/cover/[^\"']+)[\"']");

    /** <h1 class="names" …>标题</h1>。 */
    private static final Pattern H1_NAMES_RE = Pattern.compile(
            "<h1[^>]*class=[\"'][^\"']*\\bnames\\b[^\"']*[\"'][^>]*>(.*?)</h1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 「动漫介绍」正文 div。 */
    private static final Pattern INTRO_DIV_RE = Pattern.compile(
            "<div class=\"small\"[^>]*>(.*?)</div>", Pattern.DOTALL);

    /** meta 块的一行：<div class="mb-2" …>原名： …</div> 或 <span …>地区： …</span>。 */
    private static final Pattern META_ROW_RE = Pattern.compile(
            "<(?:div|span)[^>]*>\\s*([^<>]*(?:原名|别名|地区|年代|首播)：[^<>]*)<",
            Pattern.DOTALL);

    /** <div class="mb-1" style="color: red;">更新至 第01集</div>。 */
    private static final Pattern UPDATE_BADGE_RE = Pattern.compile(
            "color:\\s*red;?\">([^<]*)<");

    /** meta 块里的类型链接：<a href="/list?genre=…">动作</a>。 */
    private static final Pattern GENRE_LINK_RE = Pattern.compile(
            "<a[^>]*href=[\"']/list\\?genre=[^\"']*[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 面包屑分类：<a href="/list/?country=cn"> 中国动漫 </a>。 */
    private static final Pattern BREADCRUMB_RE = Pattern.compile(
            "<a[^>]*href=[\"']/list/\\?[^\"']*[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 剧集锚点：<a … title='第01集' href='/vod-play/{id}/{ep}.html' …>。 */
    private static final Pattern EP_ANCHOR_RE = Pattern.compile(
            "title=[\"']([^\"']*)[\"'][^>]*href=[\"']/vod-play/\\d+/([^\"']+?)\\.html[\"']");

    /** 「页次：1/215页」→ 215。 */
    private static final Pattern PAGE_TOTAL_RE = Pattern.compile(
            "页次：\\s*\\d+/\\s*(\\d+)\\s*页");
}
