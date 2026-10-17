package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * 内容模式门禁：整个应用只有「公开」和「私密」两态，这里是唯一的判定处。
 *
 * 当前态就是内存里的 {@link #active} 一个字符串（{@link #PUBLIC} / {@link #PRIVATE}），
 * 不落盘 —— 进程重启回到公开态，前端冷启动自己再走一遍解锁。落盘的只有解锁凭证：
 * SharedPreferences 文件 "content-access" 里的 privateUnlocked（密码验过没有）
 * 和 adultConfirmed（年龄门槛确认没有）。两个都真，私密态才算真正可用。
 *
 * 桥指令里谁先过门：
 * <ul>
 *   <li>enter-private —— 验密码，第一次验对就把 privateUnlocked 写盘；已经确认过年龄
 *       的话当场切到私密态。密码不对抛 IllegalArgumentException。</li>
 *   <li>confirm-private-age —— 年龄门槛点过之后调，写 adultConfirmed 并切私密态；
 *       没解锁就调会抛 IllegalStateException。</li>
 *   <li>leave-private —— 直接回公开态，不动盘上的解锁凭证（下次不用再输密码）。</li>
 *   <li>content-status —— 只读，任何时候都能问，不受门禁管。</li>
 *   <li>其余所有带 source 字段的指令 —— MainActivity.dispatch 先调 {@link #require}，
 *       要求 source 与当前态一致；私密态还额外要求 unlocked && adultConfirmed，
 *       否则抛「内容已切换，请重新打开」。这就是切换模式后必须 rebindContent 的原因。</li>
 * </ul>
 */
final class ContentMode {
    static final String PRIVATE = "huangguo";
    static final String PUBLIC = "hongguo";
    private static ContentMode instance;
    private volatile String active = PUBLIC;
    private final SharedPreferences preferences;

    private ContentMode(Context context) {
        this.preferences = context.getSharedPreferences("content-access", 0);
    }

    static synchronized ContentMode get(Context context) {
        if (instance == null) {
            instance = new ContentMode(context.getApplicationContext());
        }
        return instance;
    }

    String active() {
        return this.active;
    }

    void startPublic() {
        this.active = PUBLIC;
    }

    boolean unlocked() {
        return this.preferences.getBoolean("privateUnlocked", false);
    }

    boolean adultConfirmed() {
        return this.preferences.getBoolean("adultConfirmed", false);
    }

    void confirmAdult() {
        this.preferences.edit().putBoolean("adultConfirmed", true).apply();
    }

    synchronized JSONObject enterPrivate(String password) throws Exception {
        if (!unlocked()) {
            if (!"ssgnb".equals(password)) {
                throw new IllegalArgumentException("密码不正确");
            }
            this.preferences.edit().putBoolean("privateUnlocked", true).apply();
        }
        if (adultConfirmed()) {
            this.active = PRIVATE;
        }
        return status();
    }

    synchronized JSONObject finishPrivateEntry() throws Exception {
        if (!unlocked()) {
            throw new IllegalStateException("请先验证密码");
        }
        confirmAdult();
        this.active = PRIVATE;
        return status();
    }

    void require(String source) {
        if (!PUBLIC.equals(source) && !PRIVATE.equals(source)) {
            throw new IllegalArgumentException("无效内容来源");
        }
        if (source.equals(this.active)) {
            if (!PRIVATE.equals(source)) {
                return;
            }
            if (unlocked() && adultConfirmed()) {
                return;
            }
        }
        throw new IllegalStateException("内容已切换，请重新打开");
    }

    JSONObject status() throws Exception {
        return new JSONObject()
                .put("source", this.active)
                .put("unlocked", unlocked())
                .put("needsAgeConfirmation", unlocked() && !adultConfirmed());
    }
}
