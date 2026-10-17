package com.huangguo.mobile;

/**
 * 原 bouncycastle 里被"借用"成 JSON 字段名的常量。
 *
 * 原版为什么用 bouncycastle 的 Json.TITLE 当 JSON 键，已经无从考证
 * —— 它就是一个字符串 "title"。jadx 如实还原了这个引用，但为两个字面量挂 47MB
 * 的 bouncycastle 毫无意义。值搬过来，引用改本地。
 */
public final class Json {
    private Json() {}

    /** Json.TITLE */
    public static final String TITLE = "title";

    /** Json.LABEL */
    public static final String LABEL = "label";
}
