package com.huangguo.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;

/**
 * 前端三档外观（system / light / dark）里，原生只负责两件 WebView 管不到的事：
 * 状态栏图标明暗、底部导航栏底色。页面本身的配色仍由 app.css 的 CSS 变量决定。
 *
 * 调用来自 MainActivity 的 "theme" 桥动作（smali 里读 mode 后转进来）。
 * 静态 override 让 Activity 重建、系统配置变化时 applyTheme() 还能沿用用户选择。
 */
public final class ThemeBridge {
    private static final int SYSTEM = -1;
    private static final int LIGHT = 0;
    private static final int DARK = 1;

    private static int sOverride = SYSTEM;

    private ThemeBridge() {}

    /** -1 跟随系统，0 浅色，1 深色。smali 的 dark() 会先读这里。 */
    public static int override() {
        return sOverride;
    }

    public static void applyMode(final Activity activity, final String mode) {
        if (activity == null) return;
        final boolean system = mode == null || mode.length() == 0 || "system".equals(mode);
        sOverride = system ? SYSTEM : ("dark".equals(mode) ? DARK : LIGHT);
        final boolean dark = sOverride == DARK || (sOverride == SYSTEM && systemDark(activity));
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                applyNow(activity, dark);
            }
        });
    }

    private static boolean systemDark(Activity activity) {
        return (activity.getResources().getConfiguration().uiMode & 0x30) == 0x20;
    }

    private static void applyNow(Activity activity, boolean dark) {
        Window window = activity.getWindow();
        if (window == null) return;
        int background = Color.parseColor(dark ? "#171B1D" : "#F5F2E9");
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
        View content = activity.findViewById(android.R.id.content);
        if (content != null) content.setBackgroundColor(background);
    }
}
