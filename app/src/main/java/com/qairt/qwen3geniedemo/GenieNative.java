package com.qairt.qwen3geniedemo;

import android.util.Log;

public final class GenieNative {
    private static final String TAG = "Qwen3GenieDemo";

    static {
        Log.i(TAG, "Loading native library: QnnSystem");
        System.loadLibrary("QnnSystem");
        Log.i(TAG, "Loading native library: QnnGenAiTransformerModel");
        System.loadLibrary("QnnGenAiTransformerModel");
        Log.i(TAG, "Loading native library: QnnGenAiTransformer");
        System.loadLibrary("QnnGenAiTransformer");
        Log.i(TAG, "Loading native library: Genie");
        System.loadLibrary("Genie");
        Log.i(TAG, "Loading native library: qwen3genie");
        System.loadLibrary("qwen3genie");
        Log.i(TAG, "Native libraries loaded.");
    }

    private GenieNative() {
    }

    public static native String version();

    public static native long create(String modelRoot, int maxTokens, int threadCount);

    public static native String query(long handle, String prompt);

    public static native String runtimeEvidence();

    public static native void release(long handle);
}
