package com.qairt.qwen3htp;

import android.util.Log;

public final class GenieNative {
    private static final String TAG = "Qwen3HtpDemo";

    static {
        Log.i(TAG, "Loading native library: QnnSystem");
        System.loadLibrary("QnnSystem");
        Log.i(TAG, "Loading native library: QnnHtp");
        System.loadLibrary("QnnHtp");
        Log.i(TAG, "Loading native library: Genie");
        System.loadLibrary("Genie");
        Log.i(TAG, "Loading native library: qwen3genie");
        System.loadLibrary("qwen3genie");
        Log.i(TAG, "Native libraries loaded.");
    }

    private GenieNative() {
    }

    public static native String version();

    public static native long create(String modelRoot,
                                      int contextSize,
                                      int maxTokens,
                                      int maxOutputTokens,
                                      int threadCount,
                                     boolean greedy,
                                     int topK,
                                     float topP,
                                     float temperature,
                                     float presencePenalty);

    public static native String warmup(long handle, String systemPrompt);

    public static native String query(long handle, String prompt, boolean rewind);

    public static native void reset(long handle);

    public static native String runtimeEvidence();

    public static native String profileJson(long handle);

    public static native String queryStreaming(long handle, String prompt, boolean rewind, StreamCallback callback);

    public static native int getManualPromptTokenCount(long handle);

    public static native String getManualPromptTokenIds(long handle);

    public static native void release(long handle);
}
