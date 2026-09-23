package com.qairt.qwen3htp;

import android.content.Context;
import android.util.Log;

import com.qairt.geniehtp.lib.BuildConfig;

import java.io.File;

/**
 * Qwen3 0.6B HTP 推理引擎公开门面（AAR 对外的唯一入口）。
 *
 * <p>使用方式：
 * <pre>
 * GenieHtpEngine engine = new GenieHtpEngine(context,
 *         EngineConfig.builder().modelRoot("/data/local/tmp/genie_qwen3_quality").build());
 * engine.prepare();
 * String answer = engine.query("你好");
 * RunMetrics metrics = engine.lastMetrics();
 * engine.close();
 * </pre>
 *
 * <p>线程约定：与 Demo 一致——请在单一后台线程串行调用 query/queryStreaming/resetDialog；
 * prepare/close 幂等且线程安全。
 */
public final class GenieHtpEngine implements AutoCloseable {
    private static final String TAG = "GenieHtpEngine";

    private final Context      context;
    private final EngineConfig config;

    private GenieInferenceEngine delegate;

    public GenieHtpEngine(Context context, EngineConfig config) {
        if (context == null || config == null)
            throw new IllegalArgumentException("context and config must not be null");
        this.context = context.getApplicationContext();
        this.config = config;
    }

    // ------------------------------------------------------------------
    // 渠道静态信息（由 AAR flavor 决定，集成方可用于界面展示/日志）
    // ------------------------------------------------------------------

    /** 本 AAR 的 HTP 架构（68 或 73）。 */
    public static int htpArch() {
        return Architecture.HTP_ARCH;
    }

    /** 本 AAR 的目标平台标识（如 SA8295P）。 */
    public static String target() {
        return Architecture.TARGET;
    }

    /** 本 AAR 的渠道名（如 v68Qnn234）。 */
    public static String channel() {
        return BuildConfig.FLAVOR;
    }

    /** 本 AAR 编译期的 use-mmap 默认值（按渠道写死于 build.gradle productFlavors）。 */
    public static boolean compileTimeUseMmap() {
        return BuildConfig.USE_MMAP;
    }

    /**
     * 默认模型根目录：应用私有 files/models/&lt;channel&gt; 存在时优先（适配
     * SELinux Enforcing / 多用户），否则回退到外部推送目录
     * {@link EngineConfig#DEFAULT_MODEL_ROOT}。
     */
    public static String defaultModelRoot(Context context) {
        File privateRoot = new File(context.getFilesDir(), "models/" + BuildConfig.FLAVOR);
        return privateRoot.isDirectory()
                ? privateRoot.getAbsolutePath()
                : EngineConfig.DEFAULT_MODEL_ROOT;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 加载 native 库并提取 DSP 运行时；幂等。首次 query 时才真正创建会话。 */
    public synchronized void prepare() {
        if (delegate != null) return;
        GenieNative.loadLibraries(Architecture.HTP_ARCH);
        String modelRoot = config.modelRoot() != null
                ? config.modelRoot() : defaultModelRoot(context);
        boolean useMmap = config.useMmap() != null
                ? config.useMmap() : BuildConfig.USE_MMAP;
        Log.i(TAG, "GenieHtpEngine prepare, channel=" + BuildConfig.FLAVOR
                + ", modelRoot=" + modelRoot + ", useMmap=" + useMmap);
        delegate = new GenieInferenceEngine(
                modelRoot, DspRuntime.prepare(context), Architecture.HTP_ARCH,
                config.contextSize(), config.maxTokens(), config.maxOutputTokens(),
                config.threadCount(), config.greedy(), config.topK(), config.topP(),
                config.temperature(), config.presencePenalty(), useMmap);
    }

    public synchronized boolean isPrepared() {
        return delegate != null;
    }

    /** Genie 版本串（内部会确保 native 库已加载，不创建会话）。 */
    public String version() {
        GenieNative.loadLibraries(Architecture.HTP_ARCH);
        return GenieNative.version();
    }

    @Override
    public synchronized void close() {
        if (delegate != null) {
            delegate.close();
            delegate = null;
        }
    }

    // ------------------------------------------------------------------
    // 推理
    // ------------------------------------------------------------------

    public String query(String prompt) {
        return query(prompt, true);
    }

    /**
     * @param reuseKvCache true=REWIND 模式复用上一轮 KV cache；false=全新对话
     */
    public String query(String prompt, boolean reuseKvCache) {
        return engine().query(prompt, reuseKvCache);
    }

    public String queryStreaming(String prompt, StreamCallback callback) {
        return engine().queryStreaming(prompt, callback);
    }

    /** 重置对话（清空 KV cache，下一条 query 从全新上下文开始）。 */
    public void resetDialog() {
        engine().resetDialog();
    }

    public RunMetrics lastMetrics() {
        return delegate != null ? delegate.lastMetrics() : null;
    }

    public String lastInputTokenIds() {
        return delegate != null ? delegate.lastInputTokenIds() : "[]";
    }

    private GenieInferenceEngine engine() {
        GenieInferenceEngine e = delegate;
        if (e == null) throw new IllegalStateException("Engine is not prepared. Call prepare() first.");
        return e;
    }
}
