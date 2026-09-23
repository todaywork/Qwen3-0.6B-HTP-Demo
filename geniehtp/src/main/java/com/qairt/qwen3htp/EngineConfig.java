package com.qairt.qwen3htp;

/**
 * 推理引擎配置。通过 {@link Builder} 构建；未设置的项使用与 Demo 一致的默认值。
 *
 * <p>useMmap 为 {@code null} 时使用 AAR 编译期开关（BuildConfig.USE_MMAP，
 * 按渠道在 geniehtp/build.gradle 的 productFlavors 中写死）；显式设置 true/false 则运行期覆盖。
 * 模型根目录缺省时由 {@link GenieHtpEngine#defaultModelRoot} 解析。
 */
public final class EngineConfig {

    /** 模型兜底根目录（应用私有 models/&lt;channel&gt; 不存在时使用）。 */
    public static final String DEFAULT_MODEL_ROOT = "/data/local/tmp/genie_qwen3_quality";

    private static final int     DEFAULT_CONTEXT_SIZE      = 512;
    private static final int     DEFAULT_MAX_TOKENS        = 256;
    private static final int     DEFAULT_MAX_OUTPUT_TOKENS = 128;
    private static final int     DEFAULT_THREAD_COUNT      = 4;
    private static final boolean DEFAULT_GREEDY            = true;
    private static final int     DEFAULT_TOP_K             = 40;
    private static final float   DEFAULT_TOP_P             = 0.95f;
    private static final float   DEFAULT_TEMPERATURE       = 0.0f;
    private static final float   DEFAULT_PRESENCE_PENALTY  = 0.0f;

    private final String  modelRoot;
    private final int     contextSize;
    private final int     maxTokens;
    private final int     maxOutputTokens;
    private final int     threadCount;
    private final boolean greedy;
    private final int     topK;
    private final float   topP;
    private final float   temperature;
    private final float   presencePenalty;
    private final Boolean useMmap;

    private EngineConfig(Builder b) {
        if (b.contextSize <= 0)
            throw new IllegalArgumentException("contextSize must be greater than zero");
        if (b.maxTokens < 1 || b.maxTokens > b.contextSize)
            throw new IllegalArgumentException("maxTokens must be in range [1, contextSize]");
        if (b.maxOutputTokens < 1 || b.maxOutputTokens > b.maxTokens)
            throw new IllegalArgumentException("maxOutputTokens must be in range [1, maxTokens]");
        this.modelRoot = b.modelRoot;
        this.contextSize = b.contextSize;
        this.maxTokens = b.maxTokens;
        this.maxOutputTokens = b.maxOutputTokens;
        this.threadCount = b.threadCount;
        this.greedy = b.greedy;
        this.topK = b.topK;
        this.topP = b.topP;
        this.temperature = b.temperature;
        this.presencePenalty = b.presencePenalty;
        this.useMmap = b.useMmap;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** null 表示使用 {@link GenieHtpEngine#defaultModelRoot} 解析。 */
    public String modelRoot()                       { return modelRoot; }
    public int contextSize()                        { return contextSize; }
    public int maxTokens()                          { return maxTokens; }
    public int maxOutputTokens()                    { return maxOutputTokens; }
    public int threadCount()                        { return threadCount; }
    public boolean greedy()                         { return greedy; }
    public int topK()                               { return topK; }
    public float topP()                             { return topP; }
    public float temperature()                      { return temperature; }
    public float presencePenalty()                  { return presencePenalty; }
    /** null = 用 AAR 编译期开关；非 null = 运行期覆盖。 */
    public Boolean useMmap()                        { return useMmap; }

    public static final class Builder {
        private String  modelRoot;
        private int     contextSize      = DEFAULT_CONTEXT_SIZE;
        private int     maxTokens        = DEFAULT_MAX_TOKENS;
        private int     maxOutputTokens  = DEFAULT_MAX_OUTPUT_TOKENS;
        private int     threadCount      = DEFAULT_THREAD_COUNT;
        private boolean greedy           = DEFAULT_GREEDY;
        private int     topK             = DEFAULT_TOP_K;
        private float   topP             = DEFAULT_TOP_P;
        private float   temperature      = DEFAULT_TEMPERATURE;
        private float   presencePenalty  = DEFAULT_PRESENCE_PENALTY;
        private Boolean useMmap;

        public Builder modelRoot(String value)            { this.modelRoot = value; return this; }
        public Builder contextSize(int value)             { this.contextSize = value; return this; }
        public Builder maxTokens(int value)               { this.maxTokens = value; return this; }
        public Builder maxOutputTokens(int value)         { this.maxOutputTokens = value; return this; }
        public Builder threadCount(int value)             { this.threadCount = value; return this; }
        public Builder greedy(boolean value)              { this.greedy = value; return this; }
        public Builder topK(int value)                    { this.topK = value; return this; }
        public Builder topP(float value)                  { this.topP = value; return this; }
        public Builder temperature(float value)           { this.temperature = value; return this; }
        public Builder presencePenalty(float value)       { this.presencePenalty = value; return this; }
        public Builder useMmap(Boolean value)             { this.useMmap = value; return this; }

        public EngineConfig build() {
            return new EngineConfig(this);
        }
    }
}
