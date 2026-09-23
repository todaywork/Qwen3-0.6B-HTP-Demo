package com.qairt.qwen3htp;

import android.util.Log;

final class GenieInferenceEngine implements AutoCloseable {
    private static final String TAG = "Qwen3HtpDemo";

    private final String modelRoot;
    private final String dspRoot;
    private final int htpArch;
    private final int contextSize;
    private final int maxTokens;
    private final int maxOutputTokens;
    private final int threadCount;
    private final boolean greedy;
    private final int topK;
    private final float topP;
    private final float temperature;
    private final float presencePenalty;
    private final boolean useMmap;

    private volatile long nativeHandle = 0;
    private long sessionCreateMs = -1;
    private boolean hasQueryState = false;
    private RunMetrics lastMetrics;
    private String lastInputTokenIds = "[]";

    GenieInferenceEngine(String modelRoot, String dspRoot, int htpArch, int contextSize, int maxTokens, int maxOutputTokens,
                         int threadCount, boolean greedy, int topK, float topP,
                         float temperature, float presencePenalty, boolean useMmap) {
        this.modelRoot = modelRoot;
        this.dspRoot = dspRoot;
        this.htpArch = htpArch;
        this.contextSize = contextSize;
        this.maxTokens = maxTokens;
        this.maxOutputTokens = maxOutputTokens;
        this.threadCount = threadCount;
        this.greedy = greedy;
        this.topK = topK;
        this.topP = topP;
        this.temperature = temperature;
        this.presencePenalty = presencePenalty;
        this.useMmap = useMmap;
    }

    String query(String fullPrompt) {
        return query(fullPrompt, true);
    }

    String query(String fullPrompt, boolean reuseKvCache) {
        ensureSession();
        if (!reuseKvCache) {
            resetDialog();
        }
        boolean rewind = reuseKvCache && hasQueryState;
        Log.i(TAG, "Native query start, mode=" + (rewind ? "REWIND" : "COMPLETE")
                + ", reuseKvCache=" + reuseKvCache
                + ", promptLength=" + (fullPrompt != null ? fullPrompt.length() : 0));
        long start = System.nanoTime();
        lastInputTokenIds = "[]";
        String result;
        try {
            result = GenieNative.query(nativeHandle, fullPrompt != null ? fullPrompt : "", rewind);
        } finally {
            lastInputTokenIds = GenieNative.getManualPromptTokenIds(nativeHandle);
        }
        hasQueryState = true;
        long wallMs = (System.nanoTime() - start) / 1_000_000L;
        lastMetrics = buildMetrics(fullPrompt, result, wallMs);
        return result;
    }

    void resetDialog() {
        ensureSession();
        GenieNative.reset(nativeHandle);
        hasQueryState = false;
        lastInputTokenIds = "[]";
        Log.i(TAG, "Native Genie dialog reset; next query mode=COMPLETE");
    }

    String queryStreaming(String fullPrompt, StreamCallback callback) {
        ensureSession();
        boolean rewind = hasQueryState;
        Log.i(TAG, "Native queryStreaming start, mode=" + (rewind ? "REWIND" : "COMPLETE")
                + ", promptLength=" + (fullPrompt != null ? fullPrompt.length() : 0));
        long start = System.nanoTime();
        lastInputTokenIds = "[]";
        String result;
        try {
            result = GenieNative.queryStreaming(nativeHandle,
                    fullPrompt != null ? fullPrompt : "", rewind, callback);
        } finally {
            lastInputTokenIds = GenieNative.getManualPromptTokenIds(nativeHandle);
        }
        hasQueryState = true;
        long wallMs = (System.nanoTime() - start) / 1_000_000L;
        lastMetrics = buildMetrics(fullPrompt, result, wallMs);
        return result;
    }

    RunMetrics lastMetrics() {
        return lastMetrics;
    }

    String lastInputTokenIds() {
        return lastInputTokenIds;
    }

    @Override
    public void close() {
        long handle = nativeHandle;
        nativeHandle = 0;
        hasQueryState = false;
        lastInputTokenIds = "[]";
        if (handle != 0) GenieNative.release(handle);
    }

    private void ensureSession() {
        if (nativeHandle != 0) return;
        // Load inside the inference worker so missing device libraries are reported
        // by the existing batch error handler instead of crashing Activity.onCreate.
        GenieNative.loadLibraries(htpArch);
        Log.i(TAG, "Creating native Genie session.");
        long start = System.nanoTime();
        nativeHandle = GenieNative.create(modelRoot, dspRoot, contextSize, maxTokens, maxOutputTokens,
                threadCount, greedy, topK, topP, temperature, presencePenalty, htpArch, useMmap);
        sessionCreateMs = (System.nanoTime() - start) / 1_000_000L;
        Log.i(TAG, "Native Genie session created, handle=" + nativeHandle);
    }

    private RunMetrics buildMetrics(String fullPrompt, String result, long wallMs) {
        ProfileMetrics raw = ProfileMetrics.parse(GenieNative.profileJson(nativeHandle));
        int manualPromptTokens = GenieNative.getManualPromptTokenCount(nativeHandle);

        // 保存原始值用于交叉验证
        raw.profilePromptTokens = raw.promptTokens;
        raw.manualPromptTokens = manualPromptTokens;

        // Token 交叉验证：优先使用 JNI 手动 encode 的 token 数
        if (raw.promptTokens >= 0 && manualPromptTokens >= 0) {
            raw.tokenDiff = Math.abs(raw.promptTokens - manualPromptTokens);
            raw.tokenMismatch = raw.tokenDiff > 0;
            if (raw.tokenMismatch) {
                Log.w(TAG, "TOKEN MISMATCH DETECTED: profile=" + raw.promptTokens
                        + " manual=" + manualPromptTokens + " diff=" + raw.tokenDiff
                        + " -> overriding with manual count for accuracy");
            }
            raw.promptTokens = manualPromptTokens;
        } else if (manualPromptTokens >= 0) {
            // profile 无值但手动计算有值，直接用 manual
            raw.promptTokens = manualPromptTokens;
        }

        long queryMs = raw.durationUs >= 0 ? raw.durationUs / 1000L : wallMs;
        int outputChars = result == null ? 0 : result.length();
        int promptChars = fullPrompt == null ? 0 : fullPrompt.length();
        double charsPerSecond = queryMs > 0 ? outputChars * 1000.0 / queryMs : 0.0;

        Log.i(TAG, "Metrics built: queryMs=" + queryMs
                + ", promptTokens=" + raw.promptTokens
                + ", generatedTokens=" + raw.generatedTokens
                + ", resultLength=" + outputChars);
        return new RunMetrics(sessionCreateMs, queryMs, queryMs,
                promptChars, outputChars, charsPerSecond, raw, maxOutputTokens);
    }
}
