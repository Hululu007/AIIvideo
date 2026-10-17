package com.huangguo.mobile;

/**
 * 原 com.google.common.base.Ascii 的 ASCII 控制字符常量。
 * 只用到字节值，Guava 本身不需要。
 */
public final class Ctl {
    private Ctl() {}

    public static final byte VT = 0x0b;
    public static final byte FF = 0x0c;
    public static final byte CR = 0x0d;
    public static final byte SO = 0x0e;
    public static final byte SI = 0x0f;
    public static final byte DLE = 0x10;
    public static final byte DC2 = 0x12;
    public static final byte DC4 = 0x14;
    public static final byte NAK = 0x15;
    public static final byte SYN = 0x16;
    public static final byte ETB = 0x17;
    public static final byte CAN = 0x18;
    public static final byte EM = 0x19;
    public static final byte SUB = 0x1a;
    public static final byte ESC = 0x1b;
    public static final byte FS = 0x1c;
    public static final byte GS = 0x1d;
    public static final byte RS = 0x1e;
    public static final byte US = 0x1f;
}
