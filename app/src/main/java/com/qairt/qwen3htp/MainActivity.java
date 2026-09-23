package com.qairt.qwen3htp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.core.content.FileProvider;

public class MainActivity extends Activity {
    private static final String  TAG                          = "Qwen3HtpDemo";
    public static final  String  EXTRA_MAX_ALL_TOKEN          = "max_all_token";
    public static final  String  EXTRA_MAX_OUTPUT_TOKENS      = "max_output_tokens";
    public static final  String  EXTRA_CONTEXT_SIZE           = "context_size";
    private static final int     DEFAULT_MAX_ALL_TOKEN        = 256;
    private static final int     DEFAULT_MAX_OUTPUT_TOKENS    = 128;
    private static final int     DEFAULT_CONTEXT_SIZE         = 512;
    private static final int     MAX_SUPPORTED_CONTEXT_SIZE   = 4096;
    private static final int     THREAD_COUNT                 = 4;
    private static final boolean GREEDY                       = true;
    private static final int     TOP_K                        = 40;
    private static final float   TOP_P                        = 0.95f;
    private static final float   TEMPERATURE                  = 0.0f;
    private static final float   PRESENCE_PENALTY             = 0.0f;
    private static final int     MAX_HISTORY_TURNS            = 0;
    private static final File    PUSHED_BATCH_INPUT_DIRECTORY =
            new File("/data/local/tmp/nlutest");

    private final ExecutorService              executor     = Executors.newSingleThreadExecutor();
    private final Handler                      mainHandler  = new Handler(Looper.getMainLooper());
    private final List<BatchResult>            batchResults = new ArrayList<>();
    private final List<PromptBuilder.ChatTurn> chatHistory  = new ArrayList<>();

    private TextView    outputView;
    private TextView    statusView;
    private TextView    progressTextView;
    private TextView    exportStatusView;
    private TextView    exportPathView;
    private TextView    manualInputLabelView;
    private TextView    pushedDirectoryLabelView;
    private TextView    pushedDirectoryStatusView;
    private EditText    systemPromptEdit;
    private EditText    maxAllTokenEdit;
    private EditText    maxOutputTokensEdit;
    private EditText    promptEdit;
    private ProgressBar progressBar;
    private RadioButton manualSourceRadio;
    private RadioButton pushedDirectorySourceRadio;
    private CheckBox    reuseKvCacheCheckBox;
    private Button      runButton;
    private Button      stopButton;
    private Button      clearButton;
    private Button      refreshDirectoryButton;
    private Button      savePromptButton;
    private Button      resetPromptButton;
    private Button      openFolderButton;
    private Button      shareFileButton;

    private          GenieHtpEngine         engine;
    private          int                  contextSize;
    private          PromptBuilder        promptBuilder;
    private          String               engineSystemPrompt;
    private          int                  engineMaxAllToken     = -1;
    private          int                  engineMaxOutputTokens = -1;
    private volatile boolean              stopRequested;
    private          boolean              batchBusy;
    private          File                 lastExportFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity.onCreate");
        contextSize = resolveContextSize();
        promptBuilder = new PromptBuilder().systemPrompt(SYSTEM_PROMPT);
        setContentView(createContentView());
        if (getIntent().getBooleanExtra("autorun", false)) {
            String prompt = getIntent().getStringExtra("prompt");
            if (prompt != null && !prompt.isEmpty()) {
                promptEdit.setText(prompt);
                manualSourceRadio.setChecked(true);
            }
            startBatch();
        }
    }

    @Override
    protected void onDestroy() {
        if (engine != null)
            executor.execute(() -> engine.close());
        executor.shutdown();
        super.onDestroy();
    }

    private View createContentView() {
        int pad = dp(16);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(246, 248, 251));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("Qwen3 0.6B HTP 批量推理工具");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(24, 36, 56));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setText(defaultStatusText());
        statusView.setTextColor(Color.rgb(75, 85, 99));
        statusView.setPadding(0, dp(6), 0, dp(12));
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout configCard = addCard(root, "运行配置");
        configCard.addView(label("System Prompt"));
        systemPromptEdit = new EditText(this);
        systemPromptEdit.setMinLines(2);
        systemPromptEdit.setMaxLines(2);
        systemPromptEdit.setGravity(Gravity.TOP | Gravity.START);
        systemPromptEdit.setText(SYSTEM_PROMPT);
        systemPromptEdit.setTextSize(13);
        systemPromptEdit.setSingleLine(false);
        systemPromptEdit.setVerticalScrollBarEnabled(true);
        configCard.addView(systemPromptEdit, new LinearLayout.LayoutParams(-1, -2));

        configCard.addView(label("max_all_token（输入与输出总 Token 上限）"));
        maxAllTokenEdit = new EditText(this);
        maxAllTokenEdit.setSingleLine(true);
        maxAllTokenEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxAllTokenEdit.setText(String.valueOf(resolveInitialMaxAllToken()));
        maxAllTokenEdit.setHint("1-" + contextSize);
        configCard.addView(maxAllTokenEdit, new LinearLayout.LayoutParams(-1, -2));
        configCard.addView(hint("默认总上限 256；统计 System Prompt、历史、当前输入和输出。"
                + "可由 Intent extra：max_all_token 传入；Context 默认 512，"
                + "可由 context_size 传入，但不能超过当前模型 binary 支持的长度。"));
        configCard.addView(label("单次输出 Token 上限"));
        maxOutputTokensEdit = new EditText(this);
        maxOutputTokensEdit.setSingleLine(true);
        maxOutputTokensEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxOutputTokensEdit.setText(String.valueOf(resolveInitialMaxOutputTokens()));
        maxOutputTokensEdit.setHint("1-" + resolveInitialMaxAllToken());
        configCard.addView(maxOutputTokensEdit, new LinearLayout.LayoutParams(-1, -2));
        configCard.addView(hint("默认 48 Token；实际输出还会受到 max_all_token 剩余预算限制。"
                + "可由 Intent extra：max_output_tokens 传入。"));

        LinearLayout promptButtons = horizontalActions();
        savePromptButton = new Button(this);
        savePromptButton.setText("保存 Prompt");
        savePromptButton.setOnClickListener(v -> saveSystemPrompt());
        promptButtons.addView(savePromptButton, actionParams(1));
        resetPromptButton = new Button(this);
        resetPromptButton.setText("恢复默认");
        resetPromptButton.setOnClickListener(v -> resetSystemPrompt());
        promptButtons.addView(resetPromptButton, actionParams(1));
        configCard.addView(promptButtons);

        LinearLayout batchCard = addCard(root, "批跑任务");
        batchCard.addView(label("1. 选择批跑来源（单选）"));
        batchCard.addView(hint("程序只读取选中的来源，不会再根据输入框是否为空自动切换。"));

        RadioGroup sourceGroup = new RadioGroup(this);
        sourceGroup.setOrientation(RadioGroup.VERTICAL);
        sourceGroup.setPadding(0, 0, 0, dp(8));

        manualSourceRadio = new RadioButton(this);
        manualSourceRadio.setId(View.generateViewId());
        manualSourceRadio.setText("输入框内容");
        manualSourceRadio.setTextSize(15);
        sourceGroup.addView(manualSourceRadio, new RadioGroup.LayoutParams(-1, -2));

        pushedDirectorySourceRadio = new RadioButton(this);
        pushedDirectorySourceRadio.setId(View.generateViewId());
        pushedDirectorySourceRadio.setText("推送目录中的 Excel");
        pushedDirectorySourceRadio.setTextSize(15);
        sourceGroup.addView(pushedDirectorySourceRadio, new RadioGroup.LayoutParams(-1, -2));
        batchCard.addView(sourceGroup, new LinearLayout.LayoutParams(-1, -2));

        manualInputLabelView = label("输入框语料");
        batchCard.addView(manualInputLabelView);
        batchCard.addView(hint("每行一条语料；需要预期结果时，使用“语料<TAB>预期结果”。"));
        promptEdit = new EditText(this);
        promptEdit.setMinLines(3);
        promptEdit.setMaxLines(5);
        promptEdit.setText(SAMPLE_BATCH_TEXT);
        promptEdit.setHint("例如：\n打开车窗\n关闭天窗<TAB>{\"intent\":\"close_sunroof\"}");
        promptEdit.setGravity(Gravity.TOP | Gravity.START);
        promptEdit.setTextSize(14);
        promptEdit.setSingleLine(false);
        promptEdit.setVerticalScrollBarEnabled(true);
        batchCard.addView(promptEdit, new LinearLayout.LayoutParams(-1, -2));

        pushedDirectoryLabelView = label("推送目录");
        pushedDirectoryLabelView.setPadding(0, dp(12), 0, dp(4));
        batchCard.addView(pushedDirectoryLabelView);
        pushedDirectoryStatusView = hint("");
        pushedDirectoryStatusView.setTextIsSelectable(true);
        batchCard.addView(pushedDirectoryStatusView, new LinearLayout.LayoutParams(-1, -2));
        refreshDirectoryButton = new Button(this);
        refreshDirectoryButton.setText("刷新目录状态");
        refreshDirectoryButton.setOnClickListener(v -> refreshPushedDirectoryStatus(true));
        batchCard.addView(refreshDirectoryButton, new LinearLayout.LayoutParams(-1, -2));

        batchCard.addView(label("2. Dialog 状态"));
        reuseKvCacheCheckBox = new CheckBox(this);
        reuseKvCacheCheckBox.setText("复用 KV Cache（REWIND）");
        reuseKvCacheCheckBox.setChecked(false);
        reuseKvCacheCheckBox.setTextSize(15);
        batchCard.addView(reuseKvCacheCheckBox, new LinearLayout.LayoutParams(-1, -2));
        batchCard.addView(hint("默认关闭：每条语料执行 reset + COMPLETE，与 Genie CLI 的独立请求语义一致。"
                + "打开后：本批第 1 条使用 COMPLETE，后续语料使用 REWIND 复用 KV Cache。"));

        runButton = new Button(this);
        runButton.setOnClickListener(v -> startBatch());
        LinearLayout.LayoutParams runParams = new LinearLayout.LayoutParams(-1, -2);
        runParams.topMargin = dp(10);
        batchCard.addView(runButton, runParams);

        LinearLayout batchButtons = horizontalActions();
        stopButton = new Button(this);
        stopButton.setText("停止批跑");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopRequested = true);
        batchButtons.addView(stopButton, actionParams(1));
        clearButton = new Button(this);
        clearButton.setOnClickListener(v -> clearBatchUi());
        batchButtons.addView(clearButton, actionParams(1));
        batchCard.addView(batchButtons);

        sourceGroup.setOnCheckedChangeListener((group, checkedId) -> updateBatchSourceUi());
        pushedDirectorySourceRadio.setChecked(true);
        refreshPushedDirectoryStatus(false);

        LinearLayout progressCard = addCard(root, "执行进度");
        progressTextView = new TextView(this);
        progressTextView.setTextSize(14);
        progressTextView.setText("当前进度：0 / 0\n当前语料：-");
        progressCard.addView(progressTextView, new LinearLayout.LayoutParams(-1, -2));
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        progressCard.addView(progressBar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout resultCard = addCard(root, "推理结果");
        outputView = new TextView(this);
        outputView.setTextSize(15);
        outputView.setMovementMethod(new ScrollingMovementMethod());
        outputView.setText("推理结果会显示在这里。");
        outputView.setMinLines(6);
        outputView.setTextColor(Color.rgb(17, 24, 39));
        LinearLayout.LayoutParams outputParams = new LinearLayout.LayoutParams(-1, -2);
        outputParams.topMargin = dp(8);
        resultCard.addView(outputView, outputParams);

        LinearLayout exportCard = addCard(root, "Excel 导出");
        exportStatusView = new TextView(this);
        exportStatusView.setTextSize(14);
        exportStatusView.setText("状态：未生成");
        exportCard.addView(exportStatusView, new LinearLayout.LayoutParams(-1, -2));
        exportPathView = new TextView(this);
        exportPathView.setTextSize(13);
        exportPathView.setText("文件：-");
        exportPathView.setPadding(0, dp(6), 0, dp(8));
        exportPathView.setTextIsSelectable(true);
        exportCard.addView(exportPathView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout exportButtons = horizontalActions();
        openFolderButton = new Button(this);
        openFolderButton.setText("打开所在目录");
        openFolderButton.setEnabled(false);
        openFolderButton.setOnClickListener(v -> openExportFolder());
        exportButtons.addView(openFolderButton, actionParams(1));
        shareFileButton = new Button(this);
        shareFileButton.setText("分享文件");
        shareFileButton.setEnabled(false);
        shareFileButton.setOnClickListener(v -> shareExportFile());
        exportButtons.addView(shareFileButton, actionParams(1));
        exportCard.addView(exportButtons);
        return scrollView;
    }

    private LinearLayout addCard(LinearLayout root, String titleText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), Color.rgb(226, 232, 240));
        card.setBackground(bg);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(24, 36, 56));
        title.setPadding(0, 0, 0, dp(8));
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        root.addView(card, params);
        return card;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(55, 65, 81));
        view.setPadding(0, 0, 0, dp(4));
        return view;
    }

    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setTextColor(Color.rgb(107, 114, 128));
        view.setPadding(0, 0, 0, dp(6));
        return view;
    }

    private LinearLayout horizontalActions() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(0, dp(8), 0, 0);
        return layout;
    }

    private LinearLayout.LayoutParams actionParams(int weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, weight);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        return params;
    }

    private String modelRoot() {
        return GenieHtpEngine.defaultModelRoot(this);
    }

    private String defaultStatusText() {
        int arch = GenieHtpEngine.htpArch();
        return "Genie: 在开始推理时加载"
                + "\nRoute: Genie / QnnHtp / Qwen3-0.6B / HTP " + archLabel(arch)
                + "\nHTP arch: " + arch + " (Build.SOC_MODEL='" + socModel()
                + "', channel " + BuildConfig.FLAVOR + ")"
                + "\nTarget device: " + GenieHtpEngine.target()
                + "\nModel root: " + modelRoot();
    }

    /**
     * Build.SOC_MODEL 的取值（本机 S32X1 上该属性为空串）。
     */
    private static String socModel() {
        return Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null ? Build.SOC_MODEL : "";
    }

    private static String archLabel(int arch) {
        return "V" + arch;
    }

    private int resolveInitialMaxAllToken() {
        int value = getIntent().getIntExtra(EXTRA_MAX_ALL_TOKEN, DEFAULT_MAX_ALL_TOKEN);
        return value >= 1 && value <= contextSize
                ? value : Math.min(DEFAULT_MAX_ALL_TOKEN, contextSize);
    }

    private int resolveInitialMaxOutputTokens() {
        int maxAllTokens = resolveInitialMaxAllToken();
        int value = getIntent().getIntExtra(
                EXTRA_MAX_OUTPUT_TOKENS, DEFAULT_MAX_OUTPUT_TOKENS);
        return value >= 1 && value <= maxAllTokens
                ? value : Math.min(DEFAULT_MAX_OUTPUT_TOKENS, maxAllTokens);
    }

    private int resolveContextSize() {
        int value = getIntent().getIntExtra(EXTRA_CONTEXT_SIZE, DEFAULT_CONTEXT_SIZE);
        return value >= 1 && value <= MAX_SUPPORTED_CONTEXT_SIZE
                ? value : DEFAULT_CONTEXT_SIZE;
    }

    private int parseMaxAllToken() {
        String raw = maxAllTokenEdit.getText().toString().trim();
        if (raw.isEmpty())
            throw new IllegalArgumentException("max_all_token 不能为空");
        final int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("max_all_token 必须是整数");
        }
        if (value < 1 || value > contextSize) {
            throw new IllegalArgumentException("max_all_token 必须在 1 到 " + contextSize + " 之间");
        }
        return value;
    }

    private int parseMaxOutputTokens(int maxAllTokens) {
        String raw = maxOutputTokensEdit.getText().toString().trim();
        if (raw.isEmpty())
            throw new IllegalArgumentException("单次输出 Token 上限不能为空");
        final int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("单次输出 Token 上限必须是整数");
        }
        if (value < 1 || value > maxAllTokens) {
            throw new IllegalArgumentException(
                    "单次输出 Token 上限必须在 1 到 max_all_token（" + maxAllTokens + "）之间");
        }
        return value;
    }

    private void startBatch() {
        final List<BatchInput> inputs;
        final int maxAllToken;
        final int maxOutputTokens;
        final boolean reuseKvCache;
        final String currentSystemPrompt;
        final String inputSource;
        try {
            inputs = loadBatchInputs();
            maxAllToken = parseMaxAllToken();
            maxOutputTokens = parseMaxOutputTokens(maxAllToken);
            reuseKvCache = reuseKvCacheCheckBox.isChecked();
            currentSystemPrompt = systemPromptEdit.getText().toString();
            inputSource = manualSourceRadio.isChecked()
                    ? "manual" : PUSHED_BATCH_INPUT_DIRECTORY.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "BATCH_AUTOMATION EVENT=REJECTED error=" + automationError(e));
            String message = "运行配置或批量语料无效：\n" + e.getMessage();
            outputView.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }
        if (inputs.isEmpty()) {
            Log.e(TAG, "BATCH_AUTOMATION EVENT=REJECTED error=no_inputs");
            Toast.makeText(this, "选中的来源没有可批跑语料", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.i(TAG, "BATCH_AUTOMATION EVENT=STARTED total=" + inputs.size()
                + " input=" + inputSource
                + " reuseKvCache=" + reuseKvCache);

        stopRequested = false;
        batchResults.clear();
        chatHistory.clear();
        lastExportFile = null;
        exportStatusView.setText("状态：未生成");
        exportPathView.setText("文件：-");
        openFolderButton.setEnabled(false);
        shareFileButton.setEnabled(false);
        outputView.setText("推理结果会显示在这里。");
        setBusy(true);

        executor.execute(() -> {
            try {
                promptBuilder.systemPrompt(currentSystemPrompt);
                String builtSystemPrompt = promptBuilder.buildSystemPrompt();
                if (engine == null || !builtSystemPrompt.equals(engineSystemPrompt)
                        || maxAllToken != engineMaxAllToken
                        || maxOutputTokens != engineMaxOutputTokens) {
                    if (engine != null)
                        engine.close();
                    engine = new GenieHtpEngine(this, EngineConfig.builder()
                            .modelRoot(modelRoot())
                            .contextSize(contextSize)
                            .maxTokens(maxAllToken)
                            .maxOutputTokens(maxOutputTokens)
                            .threadCount(THREAD_COUNT)
                            .greedy(GREEDY)
                            .topK(TOP_K)
                            .topP(TOP_P)
                            .temperature(TEMPERATURE)
                            .presencePenalty(PRESENCE_PENALTY)
                            .build());
                    engine.prepare();
                    engineSystemPrompt = builtSystemPrompt;
                    engineMaxAllToken = maxAllToken;
                    engineMaxOutputTokens = maxOutputTokens;
                }

                // Every batch starts from a clean dialog. The switch only controls whether
                // subsequent items in this batch reuse the preceding KV cache.
                engine.resetDialog();

                for (int i = 0; i < inputs.size() && !stopRequested; i++) {
                    BatchInput input = inputs.get(i);
                    int current = i + 1;
                    mainHandler.post(() -> updateProgress(current, inputs.size(), input.prompt));
                    try {
                        long totalStart = System.nanoTime();
                        String fullPrompt = promptBuilder.buildChatPrompt(
                                input.prompt, chatHistory, MAX_HISTORY_TURNS);
                        Log.i(TAG, "startBatch fullPrompt==" + fullPrompt);
                        String result = engine.query(fullPrompt, reuseKvCache);

                        chatHistory.add(new PromptBuilder.ChatTurn(input.prompt, result));
                        long totalMs = (System.nanoTime() - totalStart) / 1_000_000L;
                        RunMetrics raw = engine.lastMetrics();
                        RunMetrics metrics = raw == null ? null : new RunMetrics(
                                raw.sessionCreateMs, raw.queryMs, totalMs, raw.promptChars,
                                raw.outputChars, raw.outputCharsPerSecond, raw.profile,
                                raw.maxOutputTokens);
                        BatchResult batchResult = BatchResult.success(
                                current, input.prompt, input.expected,
                                engine.lastInputTokenIds(), result, metrics);
                        batchResults.add(batchResult);
                        mainHandler.post(() -> appendBatchResult(batchResult, inputs.size()));
                    } catch (Throwable itemError) {
                        Log.e(TAG, "Batch item failed, index=" + current, itemError);
                        BatchResult batchResult = BatchResult.failure(
                                current, input.prompt, input.expected,
                                engine != null ? engine.lastInputTokenIds() : "[]", itemError);
                        batchResults.add(batchResult);
                        mainHandler.post(() -> appendBatchResult(batchResult, inputs.size()));
                    }
                }

                File exported = exportBatchResults();
                int failedCount = 0;
                for (BatchResult result : batchResults) {
                    if (result.error != null)
                        failedCount++;
                }
                if (exported != null) {
                    String completionStatus = stopRequested ? "stopped"
                            : (failedCount > 0 ? "partial_failure" : "success");
                    Log.i(TAG, "BATCH_AUTOMATION EVENT=COMPLETED status="
                            + completionStatus
                            + " processed=" + batchResults.size()
                            + " failed=" + failedCount
                            + " total=" + inputs.size()
                            + " output=" + exported.getAbsolutePath());
                } else {
                    Log.i(TAG, "BATCH_AUTOMATION EVENT=COMPLETED status=no_results"
                            + " processed=0 failed=0 total=" + inputs.size() + " output=-");
                }
                mainHandler.post(() -> {
                    if (exported != null) {
                        lastExportFile = exported;
                        exportStatusView.setText(stopRequested
                                ? "状态：已停止，已导出当前结果" : "状态：已生成");
                        exportPathView.setText("文件：\n" + exported.getAbsolutePath());
                        openFolderButton.setEnabled(true);
                        shareFileButton.setEnabled(true);
                    } else {
                        exportStatusView.setText("状态：无结果可导出");
                    }
                    setBusy(false);
                });
            } catch (Throwable t) {
                Log.e(TAG, "BATCH_AUTOMATION EVENT=FAILED error=" + automationError(t));
                Log.e(TAG, "startBatch failed", t);
                mainHandler.post(() -> {
                    outputView.setText("推理失败：\n" + t);
                    exportStatusView.setText("状态：生成失败");
                    setBusy(false);
                });
            }
        });
    }

    private static String automationError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty())
            message = error.getClass().getSimpleName();
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private List<BatchInput> loadBatchInputs() throws Exception {
        if (manualSourceRadio.isChecked()) {
            String rawText = promptEdit.getText().toString();
            if (rawText.trim().isEmpty()) {
                throw new IllegalArgumentException("已选择“输入框内容”，请至少输入一条语料");
            }
            return parseBatchInputs(rawText);
        }

        List<File> files = findPushedBatchFiles();
        if (files.isEmpty()) {
            throw new IllegalArgumentException("已选择“推送目录中的 Excel”，但目录内没有 .xlsx 文件：\n"
                    + PUSHED_BATCH_INPUT_DIRECTORY.getAbsolutePath());
        }
        List<BatchInput> inputs = new ArrayList<>();
        for (File file : files)
            inputs.addAll(BatchXlsxReader.readInputs(file));
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("找到 " + files.size()
                    + " 个 Excel，但没有读取到有效语料（A 列第 2 行起）");
        }
        Toast.makeText(this, "已从 " + files.size() + " 个 Excel 读取 "
                + inputs.size() + " 条语料", Toast.LENGTH_LONG).show();
        return inputs;
    }

    private List<File> findPushedBatchFiles() {
        File[] files = PUSHED_BATCH_INPUT_DIRECTORY.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(Locale.US).endsWith(".xlsx")
                && !file.getName().startsWith("~$"));
        if (files == null || files.length == 0)
            return new ArrayList<>();
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return new ArrayList<>(Arrays.asList(files));
    }

    private void refreshPushedDirectoryStatus(boolean showToast) {
        List<File> files = findPushedBatchFiles();
        StringBuilder status = new StringBuilder()
                .append(PUSHED_BATCH_INPUT_DIRECTORY.getAbsolutePath()).append('\n');
        if (files.isEmpty()) {
            status.append("未发现可读取的 .xlsx 文件");
        } else {
            status.append("发现 ").append(files.size()).append(" 个 Excel：");
            for (File file : files)
                status.append("\n• ").append(file.getName());
        }
        pushedDirectoryStatusView.setText(status.toString());
        if (showToast) {
            Toast.makeText(this, files.isEmpty() ? "目录中没有 Excel"
                    : "已发现 " + files.size() + " 个 Excel", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateBatchSourceUi() {
        if (manualSourceRadio == null || runButton == null)
            return;
        boolean manual = manualSourceRadio.isChecked();
        boolean controlsEnabled = !batchBusy;
        manualSourceRadio.setEnabled(controlsEnabled);
        pushedDirectorySourceRadio.setEnabled(controlsEnabled);
        promptEdit.setEnabled(controlsEnabled && manual);
        promptEdit.setAlpha(manual ? 1.0f : 0.45f);
        manualInputLabelView.setText(manual ? "输入框语料 · 当前来源" : "输入框语料");
        manualInputLabelView.setAlpha(manual ? 1.0f : 0.55f);
        refreshDirectoryButton.setEnabled(controlsEnabled && !manual);
        pushedDirectoryLabelView.setText(manual ? "推送目录" : "推送目录 · 当前来源");
        pushedDirectoryStatusView.setAlpha(manual ? 0.55f : 1.0f);
        runButton.setText(manual ? "开始批跑输入框内容" : "开始批跑推送目录");
        clearButton.setText(manual ? "清空输入框" : "清空结果");
    }

    private List<BatchInput> parseBatchInputs(String rawText) {
        List<BatchInput> inputs = new ArrayList<>();
        for (String line : rawText.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty())
                continue;
            int tab = trimmed.indexOf('\t');
            String prompt = tab >= 0 ? trimmed.substring(0, tab).trim() : trimmed;
            String expected = tab >= 0 ? trimmed.substring(tab + 1).trim() : "";
            if (!prompt.isEmpty())
                inputs.add(new BatchInput(prompt, expected));
        }
        return inputs;
    }

    private void saveSystemPrompt() {
        promptBuilder.systemPrompt(systemPromptEdit.getText().toString());
        Toast.makeText(this, "System Prompt 已应用", Toast.LENGTH_SHORT).show();
    }

    private void resetSystemPrompt() {
        systemPromptEdit.setText(SYSTEM_PROMPT);
        promptBuilder.systemPrompt(SYSTEM_PROMPT);
        Toast.makeText(this, "已恢复默认 System Prompt", Toast.LENGTH_SHORT).show();
    }

    private void updateProgress(int current, int total, String prompt) {
        progressBar.setProgress(total > 0 ? Math.round(current * 100f / total) : 0);
        progressTextView.setText("当前进度：" + current + " / " + total + "\n当前语料：" + prompt);
    }

    private static final int MAX_DISPLAYED_RESULTS = 10;

    private void appendBatchResult(BatchResult result, int total) {
        updateProgress(result.index, total, result.prompt);
        // 结果列表最多刷新前 10 条；第 11 条时再刷新一次给出提示，之后不再刷新，
        // 避免大批量时全量重建文本导致界面卡顿。每条语料的性能指标随结果块一起显示，
        // 完整结果始终写入导出 Excel。
        if (result.index <= MAX_DISPLAYED_RESULTS + 1) {
            outputView.setText(formatAllResults());
        }
    }

    private String formatBatchResult(BatchResult result) {
        StringBuilder out = new StringBuilder();
        out.append('#').append(result.index).append(' ').append(result.prompt).append('\n');
        if (result.expected != null && !result.expected.isEmpty()) {
            out.append("预期结果：\n").append(result.expected).append('\n');
        }
        out.append("推理结果：\n").append(result.error == null
                ? (result.output != null ? result.output : "") : result.error);
        if (result.compareResult != null && !result.compareResult.isEmpty()) {
            out.append("\n比对结果：").append(result.compareResult);
        }
        out.append("\n\n").append(MetricsText.formatForUi(result.metrics));
        return out.toString();
    }

    private String formatAllResults() {
        StringBuilder out = new StringBuilder();
        int shown = Math.min(batchResults.size(), MAX_DISPLAYED_RESULTS);
        for (int i = 0; i < shown; i++) {
            if (out.length() > 0)
                out.append("\n\n");
            out.append(formatBatchResult(batchResults.get(i)));
        }
        if (batchResults.size() > MAX_DISPLAYED_RESULTS) {
            out.append("\n\n…… 界面仅显示前 ").append(MAX_DISPLAYED_RESULTS)
                    .append(" 条，后续结果不再刷新，完整结果见导出 Excel。");
        }
        return out.toString();
    }

    private File exportBatchResults() throws Exception {
        if (batchResults.isEmpty())
            return null;
        File dir = new File(getFilesDir(), "batch_results");
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File output = new File(dir, "batch_Qwen3-0.6B-HTP_" + timestamp + ".xlsx");
        BatchXlsxWriter.write(output, batchResults);
        Log.i(TAG, "Batch results exported to private package storage: "
                + output.getAbsolutePath());
        return output;
    }

    private void clearBatchUi() {
        if (!runButton.isEnabled()) {
            Toast.makeText(this, "推理运行中，请先停止", Toast.LENGTH_SHORT).show();
            return;
        }
        batchResults.clear();
        if (manualSourceRadio.isChecked())
            promptEdit.setText("");
        progressBar.setProgress(0);
        progressTextView.setText("当前进度：0 / 0\n当前语料：-");
        int maxOutputTokens;
        try {
            maxOutputTokens = parseMaxOutputTokens(parseMaxAllToken());
        } catch (IllegalArgumentException ignored) {
            maxOutputTokens = resolveInitialMaxOutputTokens();
        }
        outputView.setText("推理结果会显示在这里。");
        exportStatusView.setText("状态：未生成");
        exportPathView.setText("文件：-");
        openFolderButton.setEnabled(false);
        shareFileButton.setEnabled(false);
        lastExportFile = null;
    }

    private void openExportFolder() {
        if (lastExportFile == null) {
            Toast.makeText(this, "还没有生成 Excel 文件", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "目录：" + lastExportFile.getParentFile().getAbsolutePath(), Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开目录，请复制界面上的完整路径", Toast.LENGTH_LONG).show();
        }
    }

    private void shareExportFile() {
        File file = lastExportFile;
        if (file == null || !file.exists()) {
            Toast.makeText(this, "还没有生成可分享的 Excel 文件", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "分享 Excel 文件"));
    }

    private void setBusy(boolean busy) {
        batchBusy = busy;
        runButton.setEnabled(!busy);
        stopButton.setEnabled(busy);
        clearButton.setEnabled(!busy);
        savePromptButton.setEnabled(!busy);
        resetPromptButton.setEnabled(!busy);
        systemPromptEdit.setEnabled(!busy);
        maxAllTokenEdit.setEnabled(!busy);
        maxOutputTokensEdit.setEnabled(!busy);
        reuseKvCacheCheckBox.setEnabled(!busy);
        updateBatchSourceUi();
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    static final class BatchInput {
        final String prompt;
        final String expected;

        BatchInput(String prompt, String expected) {
            this.prompt = prompt;
            this.expected = expected;
        }
    }

    private static final String SAMPLE_BATCH_TEXT =
            "close the sunroof\n"
                    + "open the window\n"
                    + "close the window";

    private static final String SYSTEM_PROMPT =
            "English request classifier.\n" +
                    "Unsafe/illegal/harmful -> REJECT\n" +
                    "Supported vehicle control -> exact command\n" +
                    "Otherwise -> UNKNOWN\n" +
                    "Priority: REJECT > vehicle command > UNKNOWN.\n" +
                    "Output only the result.\n";
}
