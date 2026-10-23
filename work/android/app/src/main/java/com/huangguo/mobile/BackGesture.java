package com.huangguo.mobile;

/**
 * 手势返回的进度换算。
 *
 * <p>系统给的是「手指在屏幕上的绝对 x」和「progress」，但我们要的是「离屏幕边缘多远、
 * 占了这个 View 宽度的百分之多少」—— 那个值才能驱动阴影和页面位移。右边那半屏
 * 方向是反的，所以要按 {@code swipeEdge} 分开算。
 *
 * <p>2026-10-23 从 {@code MainActivity} 拆出来：原来它跟 Activity 的字段绑在一起
 * （宽/左边界/提前量），一行都没法单独测。现在输入输出都是数，
 * 见 {@code BackGestureTest}。
 */
final class BackGesture {

    /** 手指离边缘的位移里，最多再多给 35% 的提前量，让阴影跟手一点。 */
    private static final float LEAD_RATIO = 0.35f;

    private BackGesture() {}

    /**
     * @param touchX    手势当前的 x（像素，可能 NaN：模拟器/某些设备不给）
     * @param swipeEdge 1 = 从左边划，2 = 从右边划（系统定义）
     * @param progress  系统给的进度，作为退化情况的答案
     * @param width     触发区域的宽度（像素），0 表示还没量到
     * @param left      触发区域左边界
     * @param leadPx    提前量上限
     */
    static float fraction(float touchX, int swipeEdge, float progress,
                          int width, int left, float leadPx) {
        if (Float.isNaN(touchX) || swipeEdge == 2 || width == 0) {
            return progress;
        }
        float f = width;
        float travelled = Math.max(0.0f, swipeEdge == 1 ? (left + f) - touchX : touchX - left);
        return Math.min(1.0f, (travelled + Math.min(leadPx, LEAD_RATIO * travelled)) / f);
    }
}
