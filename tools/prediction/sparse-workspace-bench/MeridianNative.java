/*
 * Decompiled with CFR 0.152.
 */
package com.leclowndu93150.meridian.jni;

import java.nio.ByteBuffer;

public final class MeridianNative {
    public static final int REQUIRED_ABI_VERSION = 12;

    private MeridianNative() {
    }

    public static native int abiVersion();

    public static native long createGraph(long var0, String var2);

    public static native void destroy(long var0);

    public static native int surfaceHeight(long var0, int var2, int var3);

    public static native int sampleColumn(long var0, int var2, int var3, ByteBuffer var4);

    public static native int sampleGrid(long var0, int var2, int var3, int var4, int var5, ByteBuffer var6);

    public static native String tables(long var0);
}
