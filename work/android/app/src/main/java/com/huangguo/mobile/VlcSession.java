package com.huangguo.mobile;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 一次播放会话的**状态** —— 五个集合、换线游标、续播现场。
 *
 * ── 为什么从 Activity 里拿出来 ──────────────────────────────────────
 * 2026-10-18 之前这些全挂在 {@code VlcPlayerActivity} 的字段里，和 UI、
 * libVLC、RecyclerView 的翻页逻辑搅在一起。后果有两个：
 *
 *   ① 没有一个地方能回答「这一集现在是什么状态」—— 得同时看五个集合，
 *      而它们的生命周期规则不同（urlCache 跨页保留，failed 滑回来要清，
 *      playingUrl 跟着 player 实例走）。
 *   ② 完全没法测。播放器是 App 里最难测的一块，而这些规则**恰恰是纯逻辑**：
 *      「滑回失败过的页要重新给机会」「换过线的页要记住试到第几条」。
 *
 * ── 边界 ────────────────────────────────────────────────────────────
 * 这里只管状态，不碰 Android 视图、不碰 MediaPlayer 的方法调用
 * （{@link #playingUrlOf} 的 key 在运行时是 MediaPlayer 实例，但这里只当
 * 不透明对象用，所以单元测试可以用任意对象钉住它）。
 */
final class VlcSession {

    /** 一集最多换几条备选。再多是浪费用户时间，不如让他自己换源。 */
    static final int MAX_ALT_TRIES = 4;

    /** player 实例 → 它当前挂着哪个地址。判「这条已经在这个 player 上了，别重设」。 */
    private final Map<Object, String> playingUrl = new HashMap<Object, String>();

    /** 页码 → 已解析到的播放地址。跨页保留，滑回来直接用。 */
    private final Map<Integer, String> urlCache = new HashMap<Integer, String>();

    /** 正在解析地址的页码。防止同一页被并发解析两次。 */
    private final Set<Integer> resolving = new HashSet<Integer>();

    /**
     * 解析失败的页码。
     *
     * <b>不是黑名单</b>：用户滑走再回来要重新给机会（上一次可能只是网络抖）。
     * 所以 {@link #activate} 每次都清这一页。真正的去重交给 {@link #resolving}。
     */
    private final Set<Integer> failed = new HashSet<Integer>();

    /** 页码 → 已经试到第几条备选线路。换线重试的游标。 */
    private final Map<Integer, Integer> altTried = new HashMap<Integer, Integer>();

    /** 退到后台那一刻是不是正在播。回来时只在这条为真才自动续播。 */
    private boolean wasPlaying;

    // ── 地址 ──────────────────────────────────────────────────────────

    /** 这一页已经解析出的地址，没有返回 null。 */
    String urlOf(int pos) { return urlCache.get(pos); }

    boolean hasUrl(int pos) { return urlCache.containsKey(pos); }

    void rememberUrl(int pos, String url) {
        if (url == null || url.length() == 0) return;
        urlCache.put(pos, url);
    }

    // ── 解析中 ────────────────────────────────────────────────────────

    /**
     * 抢一个「我来解析」的令牌。已经有别人在解析这一页时返回 false。
     *
     * 用法：{@code if (!session.beginResolve(pos)) return;}
     */
    boolean beginResolve(int pos) { return resolving.add(pos); }

    void endResolve(int pos) { resolving.remove(pos); }

    boolean isResolving(int pos) { return resolving.contains(pos); }

    // ── 失败 ──────────────────────────────────────────────────────────

    void markFailed(int pos) { failed.add(pos); }

    void clearFailed(int pos) { failed.remove(pos); }

    boolean hasFailed(int pos) { return failed.contains(pos); }

    // ── player ↔ 地址 ─────────────────────────────────────────────────

    String playingUrlOf(Object player) { return playingUrl.get(player); }

    void setPlayingUrl(Object player, String url) {
        if (player == null) return;
        playingUrl.put(player, url);
    }

    void clearPlayingUrl(Object player) { playingUrl.remove(player); }

    /**
     * 换掉一对 player 时清掉「谁挂着哪条地址」。
     *
     * 只清这一张表，不动 {@link #urlCache}：地址是**页码**的属性，和哪个
     * MediaPlayer 实例在放无关。释放 player 之后重建一对新的，同一个页码
     * 应该还能直接用同一个地址，不必再问站点要一次。
     */
    void forgetPlayers() { playingUrl.clear(); }

    // ── 换线 ──────────────────────────────────────────────────────────

    /**
     * 取这一页的下一条备选下标。用完了（或超过 {@link #MAX_ALT_TRIES}）返回 -1。
     *
     * 每调用一次游标前进一格 —— 调用方只管「给我下一条」，
     * 不用自己维护「试到第几条了」。
     */
    int takeNextAlt(int pos, int available) {
        int tried = altTried.containsKey(pos) ? altTried.get(pos) : 0;
        if (tried >= MAX_ALT_TRIES || tried >= available) return -1;
        altTried.put(pos, tried + 1);
        return tried;
    }

    int altCursor(int pos) {
        Integer v = altTried.get(pos);
        return v == null ? 0 : v;
    }

    void resetAlt(int pos) { altTried.remove(pos); }

    // ── 续播 ──────────────────────────────────────────────────────────

    boolean wasPlaying() { return wasPlaying; }

    void setWasPlaying(boolean value) { wasPlaying = value; }

    // ── 整体 ──────────────────────────────────────────────────────────

    /**
     * 换一集（换 payload）时整体清空。
     *
     * 以前这段是五句 {@code clear()} 平铺在 Activity 里 —— 加第六个集合时
     * 很容易漏一句，而漏掉的表现是「上一部的地址被当成这一部的」，
     * 排查要对着日志猜很久。
     */
    void reset() {
        playingUrl.clear();
        urlCache.clear();
        resolving.clear();
        failed.clear();
        altTried.clear();
        wasPlaying = false;
    }
}
