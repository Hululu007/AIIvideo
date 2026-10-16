package com.huangguo.mobile;

/**
 * 编译期桩，不打包。
 *
 * MainActivity 是 APK 里的既有类。这里只声明 UserSource 用到的那几个成员：
 *   smali: .field public static volatile host:Lcom/huangguo/mobile/MainActivity;
 *   smali: .method public restart()V
 * 签名必须一致，否则运行期 NoSuchFieldError / NoSuchMethodError。
 *
 * 继承 Activity 是为了拿到 isFinishing() / isDestroyed() —— 真实类本来就是
 * Activity 的子类，桩不继承的话这几个方法编译期找不到。
 */
public class MainActivity extends android.app.Activity {
    public static volatile MainActivity host;
    public void restart() {}

    /** smali: .method public store()Lcom/huangguo/mobile/AppStore; */
    public AppStore store() { return null; }
}
