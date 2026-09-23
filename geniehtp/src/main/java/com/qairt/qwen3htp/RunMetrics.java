package com.qairt.qwen3htp;

public final class RunMetrics {
    public final long sessionCreateMs;
    public final long queryMs;
    public final long totalMs;
    public final int promptChars;
    public final int outputChars;
    public final double outputCharsPerSecond;
    public final ProfileMetrics profile;
    public final int maxOutputTokens;

    public RunMetrics(long sessionCreateMs, long queryMs, long totalMs,
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
