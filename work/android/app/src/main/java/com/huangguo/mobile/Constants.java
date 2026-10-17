package com.huangguo.mobile;

/** 原 androidx.media3.common.C / exoplayer.Renderer 里被借用的常量。值取自 smali。 */
public final class Constants {
    private Constants() {}

    /** C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS  —— smali 里是 0xbb8L */
    public static final int DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS = 3000;

    /** Renderer.DEFAULT_DURATION_TO_PROGRESS_US  —— smali 里是 0x2710L */
    public static final long DEFAULT_DURATION_TO_PROGRESS_US = 10000L;
}
