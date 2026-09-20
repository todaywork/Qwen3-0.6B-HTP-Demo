package com.qairt.qwen3htp;

import android.util.Log;

public final class GenieNative {
    private static final String TAG = "Qwen3HtpDemo";

    private static boolean loaded = false;

    /**
     * 按 HTP 架构加载 native 库。stub（libQnnHtpV68/V73Stub.so）必须与模型目录 dsp 下
     * skel 架构一致，且必须先于 libQnnHtp 后端加载，否则 ctx bin 解析报
     * populateGraphBinaryInfo failed。
     */
    public static synchronized void loadLibraries(int htpArch) {
        if (htpArch != Architecture.HTP_ARCH) {
            throw new IllegalArgumentException("Unsupported htpArch: " + htpArch);
        }
        if (loaded) return;
        Log.i(TAG, "Loading native library: QnnSystem");
        System.loadLibrary("QnnSystem");
        String stub = "QnnHtpV" + htpArch + "Stub";
        Log.i(TAG, "Loading native library: " + stub);
        System.loadLibrary(stub);
        Log.i(TAG, "Loading native library: QnnHtp");
        System.loadLibrary("QnnHtp");
        Log.i(TAG, "Loading native library: Genie");
        System.loadLibrary("Genie");
        Log.i(TAG, "Loading native library: qwen3genie");
        System.loadLibrary("qwen3genie");
        loaded = true;
        Log.i(TAG, "Native libraries loaded.");
    }

    private GenieNative() {
    }

    public static native String version();

    public static native long create(String modelRoot, String dspRoot,
                                      int contextSize,
                                      int maxTokens,
                                      int maxOutputTokens,
                                      int threadCount,
                                     boolean greedy,
                                     int topK,
                                     float topP,
                                     float temperature,
                                     float presencePenalty,
                                      int htpArch);

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
