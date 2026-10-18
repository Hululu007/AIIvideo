package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link VlcSession} 的规则回归。
 *
 * <p>这些规则以前散在 {@code VlcPlayerActivity} 的五个集合里，**完全没法测** ——
 * 要验「滑回失败过的页会不会重新给机会」得在真机上来回滑，而且时序一错就复现不了。
 * 搬出来之后它们变成了纯逻辑，可以在这里钉死。
 */
public class VlcSessionTest {

    /** 地址缓存跨页保留：滑回来直接用，不再问站点。 */
    @Test public void 地址缓存跨页保留() {
        VlcSession s = new VlcSession();
        assertFalse(s.hasUrl(3));
        assertNull(s.urlOf(3));
        s.rememberUrl(3, "https://cdn/a.m3u8");
        assertTrue(s.hasUrl(3));
        assertEquals("https://cdn/a.m3u8", s.urlOf(3));
    }

    /** 空地址不写进缓存 —— 写进去会让「有没有地址」永远为真。 */
    @Test public void 空地址不写缓存() {
        VlcSession s = new VlcSession();
        s.rememberUrl(1, "");
        s.rememberUrl(1, null);
        assertFalse(s.hasUrl(1));
    }

    /**
     * 并发解析只能有一个赢家。
     *
     * 失败的话同一页会被解析两次：两次都拿到地址，第二次的 setMedia 会把
     * 正在播的流取消掉（VLC 日志 Cancellation + Failed to create demuxer），黑屏。
     */
    @Test public void 同一页只允许一个解析者() {
        VlcSession s = new VlcSession();
        assertTrue("第一个应该拿到令牌", s.beginResolve(2));
        assertFalse("第二个必须被挡住", s.beginResolve(2));
        assertTrue(s.isResolving(2));
        s.endResolve(2);
        assertFalse(s.isResolving(2));
        assertTrue("上一个结束之后可以再解析", s.beginResolve(2));
    }

    /** 失败标记不是黑名单：滑回来要清掉，重新给机会。 */
    @Test public void 失败标记可以被清掉() {
        VlcSession s = new VlcSession();
        s.markFailed(4);
        assertTrue(s.hasFailed(4));
        s.clearFailed(4);
        assertFalse("滑回来必须重新给机会，上一次可能只是网络抖了", s.hasFailed(4));
    }

    /**
     * 换线游标：每次取下一条就前进一格，到上限收手。
     *
     * 这是「播不动就换下一条」那条兜底的账本。它数错的话要么漏掉能用的线，
     * 要么无限重试让屏幕一直闪。
     */
    @Test public void 换线游标逐条前进并在上限收手() {
        VlcSession s = new VlcSession();
        assertEquals(0, s.takeNextAlt(7, 10));
        assertEquals(1, s.takeNextAlt(7, 10));
        assertEquals(2, s.takeNextAlt(7, 10));
        assertEquals(3, s.takeNextAlt(7, 10));
        assertEquals("超过 MAX_ALT_TRIES 必须收手", -1, s.takeNextAlt(7, 10));
        assertEquals(VlcSession.MAX_ALT_TRIES, s.altCursor(7));
    }

    /** 备选比上限少时，用完备选就收手，不会去取不存在的下标。 */
    @Test public void 备选数量少于上限时提前收手() {
        VlcSession s = new VlcSession();
        assertEquals(0, s.takeNextAlt(1, 2));
        assertEquals(1, s.takeNextAlt(1, 2));
        assertEquals(-1, s.takeNextAlt(1, 2));
    }

    /** player ↔ 地址：换一条线路之后要能看出「这个 player 现在挂的是哪条」。 */
    @Test public void player与地址的对应() {
        VlcSession s = new VlcSession();
        Object playerA = new Object();
        assertNull(s.playingUrlOf(playerA));
        s.setPlayingUrl(playerA, "https://cdn/a");
        assertEquals("https://cdn/a", s.playingUrlOf(playerA));
        s.clearPlayingUrl(playerA);
        assertNull(s.playingUrlOf(playerA));
    }

    /** 换一部片：所有状态一起清，不能漏 —— 漏一个就会出现「上一部的地址播这一部」。 */
    @Test public void reset清空全部状态() {
        VlcSession s = new VlcSession();
        Object player = new Object();
        s.rememberUrl(0, "https://cdn/a");
        s.beginResolve(1);
        s.markFailed(2);
        s.takeNextAlt(3, 5);
        s.setPlayingUrl(player, "https://cdn/a");
        s.setWasPlaying(true);

        s.reset();

        assertFalse(s.hasUrl(0));
        assertFalse(s.isResolving(1));
        assertFalse(s.hasFailed(2));
        assertEquals(0, s.altCursor(3));
        assertNull(s.playingUrlOf(player));
        assertFalse(s.wasPlaying());
    }

    /**
     * 换掉一对 player 时只清「谁挂着哪条地址」，页码缓存要留着。
     *
     * 地址是**页码**的属性，和哪个 MediaPlayer 实例在放无关。释放重建之后
     * 同一个页码应该还能直接用同一个地址 —— 清掉的话每换一次 player 都要
     * 重新问站点要一遍，AGE 那条线路一次十几秒。
     */
    @Test public void 换player只清地址对应不动页码缓存() {
        VlcSession s = new VlcSession();
        Object playerA = new Object();
        s.rememberUrl(2, "https://cdn/b");
        s.setPlayingUrl(playerA, "https://cdn/b");

        s.forgetPlayers();

        assertNull("旧实例的地址对应必须消失", s.playingUrlOf(playerA));
        assertEquals("页码缓存不受影响", "https://cdn/b", s.urlOf(2));
    }

    /**
     * 起播成功就把换线游标归零 —— 下一次报错仍从第一条备选试起。
     *
     * 不清的话，一集里先因为网络抖换过两条线，之后正常播完换集又出问题时，
     * 游标还停在 2，会直接跳过前两条本来能用的线路。
     */
    @Test public void 起播成功后换线游标归零() {
        VlcSession s = new VlcSession();
        assertEquals(0, s.takeNextAlt(5, 3));
        assertEquals(1, s.takeNextAlt(5, 3));
        s.resetAlt(5);
        assertEquals(0, s.altCursor(5));
        assertEquals("归零之后能重新从第一条试", 0, s.takeNextAlt(5, 3));
    }
}
