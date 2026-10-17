package com.huangguo.mobile;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * 骨架屏上那道来回扫的高光。
 *
 * 就是一块矩形，靠一个 -1 → 1.5 无限循环的 ValueAnimator 推动 phase，onDraw 里
 * 用 phase 算出 LinearGradient 的横向起点，每次换一个新 shader 再画一遍。
 * 可见性跟着 View 自己的生命周期走：可见且挂在窗口上就 start，否则 cancel。
 */
final class ShimmerView extends View {
    private final ValueAnimator animator;
    private final Paint paint;
    private float phase;

    ShimmerView(Context context) {
        super(context);
        this.paint = new Paint();
        ValueAnimator valueAnimatorOfFloat = ValueAnimator.ofFloat(-1.0f, 1.5f);
        this.animator = valueAnimatorOfFloat;
        valueAnimatorOfFloat.setDuration(1700L);
        valueAnimatorOfFloat.setRepeatCount(-1);
        valueAnimatorOfFloat.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                ShimmerView.this.phase = ((Float) anim.getAnimatedValue()).floatValue();
                ShimmerView.this.invalidate();
            }
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float startX = width * this.phase;
        this.paint.setShader(new LinearGradient(startX, 0.0f, startX + width, 0.0f,
                new int[]{-15263202, -13618118, -15263202}, new float[]{0.0f, 0.5f, 1.0f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0.0f, 0.0f, width, getHeight(), this.paint);
        this.paint.setShader(null);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == 0 && isAttachedToWindow()) {
            this.animator.start();
        } else {
            this.animator.cancel();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == 0) {
            this.animator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        this.animator.cancel();
        super.onDetachedFromWindow();
    }
}
