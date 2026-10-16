package com.huangguo.mobile;

import org.json.JSONObject;

/**
 * 编译期桩，不打包。
 *
 * AppStore 是 APK 里的既有类（2900 多行 smali），这里只声明 UserSource 要用的
 * 那一个方法。签名必须和 smali 一致：
 *   AppStore.smali: .method declared-synchronized recordProgress(Lorg/json/JSONObject;)V
 *
 * 注意它是**包级可见**（smali 里没写 public/private）—— 桩也照写，别加 public，
 * 加了不影响编译但会让「签名一致」这句话变模糊。
 */
public class AppStore {
    /**
     * 记一条观看进度。入参是 {id, episode, position, duration, title, cover[, episodeCount]}。
     * id 必须是纯数字（真实实现里有 matches("\\d+") 这一关）。
     */
    void recordProgress(JSONObject record) {}
}
