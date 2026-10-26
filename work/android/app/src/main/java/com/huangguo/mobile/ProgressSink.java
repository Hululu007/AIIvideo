package com.huangguo.mobile;

import org.json.JSONObject;

/**
 * 播放进度上报 —— 一条桥指令的全部实现。
 *
 * ── 为什么单独一个类 ────────────────────────────────────────────────
 * 2026-10-18 之前它和**源配置、代理核心、播放器控制**一起挤在
 * {@code UserSource}（2492 行）里，只因为历史上它们都借同一座桥。
 * 拆开之后这条链路上没有任何共享字段 —— 它只读一个 JSON、写一次 AppStore。
 *
 * ── 这条指令补的是什么洞 ────────────────────────────────────────────
 * 播放器（feed-player.js 的 remember()）只把进度写进 localStorage 的
 * `hgpref:progress`，那是**页面自己的**记忆。原生侧 AppStore.progress 一直空着，
 * 于是「我的 → 观看历史」永远没东西，也永远同步不上去。
 */
final class ProgressSink {

    private ProgressSink() {}

    /**
     * 记一条观看进度，落到 AppStore 里，于是 api('library') 能读到它、
     * 进而被同步到账号后端。
     *
     * ── 为什么需要这一段 ──────────────────────────────────────────────
     * 播放器（feed-player.js 的 remember()）只把进度写进了 localStorage 的
     * `hgpref:progress`。那是**页面自己的**记忆，原生侧的 AppStore.progress 一直是空的，
     * 于是「我的 → 观看历史」永远没东西，也永远同步不上去。
     * 而 AppStore 里 `recordProgress(JSONObject)` 是**现成的**，只是零调用者 ——
     * 原版大概是从抓 HTML 那条路里调的，那条路整块删掉时把它一起带走了。
     *
     * 入参：{id, episode, position, duration, title, cover[, episodeCount]}
     * id 必须是纯数字 —— recordProgress 里有 matches("\\d+")。
     */
    static JSONObject report(String body, JSONObject out) {
        try {
            String query = body;
            int mark = body.indexOf('?');
            if (mark >= 0) query = body.substring(mark + 1);
            String payload = UserSource.param(query, "payload");
            if (payload.length() == 0) return UserSource.fail(out, "进度指令没有 payload");

            JSONObject record = new JSONObject(payload);
            String id = record.optString("id", "").trim();
            if (!id.matches("\\d+")) return UserSource.fail(out, "视频 id 不是纯数字：" + id);

            MainActivity host = MainActivity.host;
            if (host == null) return UserSource.fail(out, "Activity 不在，进度没地方存");
            AppStore store = host.store();
            if (store == null) return UserSource.fail(out, "拿不到 AppStore");

            // 打出来：源身份决定了观看历史的过滤，传空的话记录就会变成「谁都算」。
            UserSource.log("记进度 " + id + " source=["
                    + record.optString("source", "<没有这个字段>") + "]");
            store.recordProgress(record);
            out.put("ok", true);
            return out;
        } catch (Throwable e) {
            return UserSource.fail(out, "记进度失败：" + AiiHttp.describe(e));
        }
    }
}
