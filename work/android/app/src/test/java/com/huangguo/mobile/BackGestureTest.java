package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 手势返回的进度换算。原来这几个判断埋在 MainActivity 里，跟着 Activity 字段一起
 * 没法测；拆出来之后全是数，直接断言。
 */
public class BackGestureTest {

    private static final float EPS = 0.0001f;

    @Test
    public void 没有触点是退化到系统进度() {
        assertEquals(0.42f, BackGesture.fraction(Float.NaN, 1, 0.42f, 1080, 0, 0), EPS);
    }

    @Test
    public void 右侧手势直接用系统进度() {
        // swipeEdge == 2 那条路原来就没实现，保持原样：拿系统的值。
        assertEquals(0.31f, BackGesture.fraction(200f, 2, 0.31f, 1080, 0, 100), EPS);
    }

    @Test
    public void 还没量到宽度时用系统进度() {
        assertEquals(0.5f, BackGesture.fraction(900f, 1, 0.5f, 0, 0, 100), EPS);
    }

    @Test
    public void 左边划按右边界算位移() {
        // left=0, width=1000, touch=800 → 位移 200 → 200 + min(lead,70) = 270 → 0.27
        assertEquals(0.27f, BackGesture.fraction(800f, 1, 0f, 1000, 0, 100), EPS);
    }

    @Test
    public void 提前量封顶在三成五() {
        // 位移 20，35% 是 7，leadPx 给 100 也只能加 7 → 0.027
        assertEquals(0.027f, BackGesture.fraction(980f, 1, 0f, 1000, 0, 100), EPS);
    }

    @Test
    public void 位移超过边界时夹到一() {
        // touch 跑到左边界外面（负数），位移算出来比宽度还大 → 夹 1
        assertEquals(1.0f, BackGesture.fraction(-200f, 1, 0f, 1000, 0, 0), EPS);
    }
}
