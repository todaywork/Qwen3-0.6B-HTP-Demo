package com.qairt.qwen3htp;

import java.util.Locale;

final class MetricsText {
    private MetricsText() {}

    static String formatForUi(RunMetrics metrics) {
        if (metrics == null) return "性能指标：\n-";
        ProfileMetrics p = metrics.profile;
        String tokenVerify = "";
        if (p != null && p.tokenMismatch) {
            tokenVerify = " [profile=" + p.profilePromptTokens
                    + ", manual=" + p.manualPromptTokens
                    + ", diff=" + p.tokenDiff + "]";
        }
        return "性能指标：\n"
                + "输出 Token 上限：" + metrics.maxOutputTokens + "\n"
                + "会话初始化耗时：" + (metrics.sessionCreateMs < 0 ? "reused" : metrics.sessionCreateMs + " ms") + "\n"
                + "单条推理耗时：" + metrics.queryMs + " ms\n"
                + "端到端耗时：" + metrics.totalMs + " ms\n"
                + "首 Token 延迟：" + formatMicrosAsMs(p != null ? p.timeToFirstTokenUs : -1) + "\n"
                + "输入 Token 数：" + (p != null ? p.promptTokens : -1) + tokenVerify + "\n"
                + "生成 Token 数：" + (p != null ? p.generatedTokens : -1) + "\n"
                + "Prefill 吞吐：" + formatRate(p != null ? p.promptTokensPerSecond : 0.0) + " tok/s\n"
                + "Decode 吞吐：" + formatRate(p != null ? p.generatedTokensPerSecond : 0.0) + " tok/s\n"
                + "输出字符速度：" + formatRate(metrics.outputCharsPerSecond) + " chars/s";
    }

    static double prefillSeconds(RunMetrics metrics) {
        ProfileMetrics p = metrics != null ? metrics.profile : null;
        return p == null || p.promptTokens < 0 || p.promptTokensPerSecond <= 0
                ? 0.0 : p.promptTokens / p.promptTokensPerSecond;
    }

    static double decodeSeconds(RunMetrics metrics) {
        ProfileMetrics p = metrics != null ? metrics.profile : null;
        return p == null || p.generatedTokens < 0 || p.generatedTokensPerSecond <= 0
                ? 0.0 : p.generatedTokens / p.generatedTokensPerSecond;
    }

    private static String formatRate(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String formatMicrosAsMs(long value) {
        return value < 0 ? "-" : String.format(Locale.US, "%.3f ms", value / 1000.0);
    }
}
