package com.qairt.qwen3htp;

final class RunMetrics {
    final long sessionCreateMs;
    final long queryMs;
    final long totalMs;
    final int promptChars;
    final int outputChars;
    final double outputCharsPerSecond;
    final ProfileMetrics profile;
    final int maxOutputTokens;

    RunMetrics(long sessionCreateMs, long queryMs, long totalMs,
               int promptChars, int outputChars, double outputCharsPerSecond,
               ProfileMetrics profile, int maxOutputTokens) {
        this.sessionCreateMs = sessionCreateMs;
        this.queryMs = queryMs;
        this.totalMs = totalMs;
        this.promptChars = promptChars;
        this.outputChars = outputChars;
        this.outputCharsPerSecond = outputCharsPerSecond;
        this.profile = profile;
        this.maxOutputTokens = maxOutputTokens;
    }
}
