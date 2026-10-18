package com.huangguo.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 响应形状的唯一实现 —— 「我们的 JSON 长什么样」只在这里回答一次。
 *
 * ── 为什么这件事必须收口 ────────────────────────────────────────────
 * 适配器的契约是「返回我们内部 JSON 形状的字节」。以前这半截由**每个适配器
 * 自己拼**，于是同一件事有八种拼法：
 *
 * <pre>
 *   Mrds66 / Yhdm:  {items, pagination:{page, pages}}   + 信封 {data: …}
 *   Missav:         {items, page, hasMore}              + 信封 {data: …}
 *   Agedm:          裸响应，没有信封（配 "shapes":{"envelope":""}）
 * </pre>
 *
 * 前端 `nextPageOf()` 只认 `pagination.page/pages`。所以 Missav 那套平铺形状
 * **读不到翻页**，只能在配置里补一份 `shapes.list` 把路径指过去 ——
 * 配错就是「每个分类只有第一页，滑到底写已经到底了」，而且不报错。
 *
 * 也就是说：形状不一致 → 每个适配器配一份 shapes → 配错静默失效。
 * 只要形状由一份实现统一产出，shapes 那一整块配置就不需要了。
 *
 * ── 这里放什么 ──────────────────────────────────────────────────────
 * 只放**结构**，不放站点知识。适配器交上来的是「一页有哪几个条目、这是第几页、
 * 共几页」，至于这些怎么装进 JSON、信封叫什么，是这里的事。
 */
final class AdapterShape {

    private AdapterShape() {}

    /** 信封。所有适配器统一 `{"data": …}`，前端 `envelopeKey()` 缺省就是这个。 */
    static byte[] envelope(JSONObject data) throws Exception {
        JSONObject out = new JSONObject();
        out.put("data", data);
        return out.toString().getBytes("UTF-8");
    }

    /**
     * 列表响应：`{items, pagination:{page, pages, size}}`。
     *
     * 这就是前端 `nextPageOf()` 认的那套老形状，也是 `web.shapes.list`
     * 的缺省值 —— 用它就不必再配 shapes。
     *
     * @param pages 总页数。拿不到就传 0，由这里按「这一页有没有内容」兜底判断。
     */
    static JSONObject listing(JSONArray items, int page, int pages) throws Exception {
        JSONObject out = new JSONObject();
        out.put("items", items == null ? new JSONArray() : items);
        out.put("pagination", pagination(page, items == null ? 0 : items.length(), pages));
        return out;
    }

    /** 分页块。单独给一个，首页那种「不翻页但也要带一下」的场景用。 */
    static JSONObject pagination(int page, int size, int pages) throws Exception {
        JSONObject p = new JSONObject();
        p.put("page", Math.max(1, page));
        p.put("size", Math.max(0, size));
        // 拿不到总数时：这一页有内容就当作还有下一页，没内容就到底了。
        // 这是唯一安全的猜测 —— 猜「有」最多多请求一次，猜「没有」会让
        // 后面几十页永远翻不到。
        p.put("pages", pages > 0 ? Math.max(page, pages) : (size > 0 ? page + 1 : page));
        return p;
    }

    /**
     * 详情响应。
     *
     * 只做三件固定的事：给 id/title/cover 兜个类型、episodes 保证是数组、
     * episodeCount 和 episodes 对齐。**字段名一次都不改** —— 改了就要动
     * web.fields 别名表，而别名表存在的意义正是「站点侧的键名差异」，
     * 不该拿来兜我们自己的结构问题。
     */
    static JSONObject detail(JSONObject raw) throws Exception {
        JSONObject out = raw == null ? new JSONObject() : raw;
        if (!out.has("id")) out.put("id", "");
        if (!out.has("title")) out.put("title", "");
        if (!out.has("cover")) out.put("cover", "");
        JSONArray episodes = out.optJSONArray("episodes");
        if (episodes == null) {
            episodes = new JSONArray();
            out.put("episodes", episodes);
        }
        // 单集站也要给一条 —— 前端 adaptDetail 拿到空数组时详情页一集都不渲染，
        // 用户看到的是「无法拉到集数」。这是适配器的责任：把「站点没有集数体系」
        // 翻译成「有一集」，而不是把「没有」原样交上去让前端空白。
        if (episodes.length() == 0) {
            JSONObject ep = new JSONObject();
            ep.put("episodeNumber", 1);
            ep.put("title", out.optString("title", ""));
            ep.put("duration", "");
            episodes.put(ep);
        }
        out.put("episodeCount", episodes.length());
        return out;
    }

    /**
     * 播放响应：`{source, url, videoUrl, quality, lines, episodeNumber}`。
     *
     * 三个同义键（source / url / videoUrl）**故意重复**：前端 `resolveUrl`
     * 取的是 `info.source || info.url`，而别名表里 `source` 的候选是
     * `source / playUrl / video_url` —— 三方各有一套叫法。都填上，
     * 别名表怎么配都不会漏。
     *
     * @param source 首条可用地址
     * @param lines  全部线路，形状 `[{quality,label,url}]`；没分档就传 null
     */
    static JSONObject play(String source, JSONArray lines, int episodeNumber) throws Exception {
        JSONObject out = new JSONObject();
        out.put("source", source);
        out.put("url", source);
        out.put("videoUrl", source);
        out.put("quality", 0);
        out.put("lines", lines == null ? new JSONArray() : lines);
        out.put("episodeNumber", episodeNumber);
        return out;
    }

    /** 一条线路。label 给用户看，quality 给排序用（0 最好）。 */
    static JSONObject line(int index, String label, String url) throws Exception {
        JSONObject line = new JSONObject();
        line.put("quality", index);
        line.put("label", label == null || label.length() == 0 ? ("线路" + (index + 1)) : label);
        line.put("url", url);
        return line;
    }
}
