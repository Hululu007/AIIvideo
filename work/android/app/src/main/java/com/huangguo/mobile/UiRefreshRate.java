package com.huangguo.mobile;

import android.app.Activity;
import android.os.Build;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

/**
 * 把窗口和 View 都往面板能给的最高刷新率上顶。
 *
 * 高的那档要自己挑：getMode() 返回的是当前这档，不一定是最高；
 * 但也不能直接用 getSupportedModes() 里最大的那个 —— 分辨率不同的模式
 * （有些机器高刷只在降到 1080p 时才开）切过去会改画质，所以只认和当前
 * 模式同宽同高、且不超过 120.5Hz 的候选。
 */
final class UiRefreshRate {
    private UiRefreshRate() {
    }

    static void apply(Activity activity, View view) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        Display.Mode currentMode = display.getMode();
        float targetRate = 0.0f;
        for (Display.Mode supportedMode : display.getSupportedModes()) {
            if (supportedMode.getPhysicalWidth() == currentMode.getPhysicalWidth()
                    && supportedMode.getPhysicalHeight() == currentMode.getPhysicalHeight()
                    && supportedMode.getRefreshRate() <= 120.5f) {
                targetRate = Math.max(targetRate, supportedMode.getRefreshRate());
            }
        }
        if (targetRate == 0.0f) {
            targetRate = currentMode.getRefreshRate();
        }
        WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        attributes.preferredRefreshRate = targetRate;
        if (Build.VERSION.SDK_INT >= 35) {
            attributes.setFrameRateBoostOnTouchEnabled(true);
        }
        activity.getWindow().setAttributes(attributes);
        if (Build.VERSION.SDK_INT >= 36 && (view instanceof ViewGroup)) {
            ((ViewGroup) view).propagateRequestedFrameRate(targetRate, true);
        } else if (Build.VERSION.SDK_INT >= 35) {
            view.setRequestedFrameRate(targetRate);
        }
    }
}
