package com.qairt.qwen3geniedemo;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String TAG = "Qwen3GenieDemo";
    private static final int MAX_TOKENS = 32;
    private static final int THREAD_COUNT = 3;
    private static final String SYSTEM_PROMPT = "You are a vehicle assistant. Reply briefly in Chinese.";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView outputView;
    private TextView statusView;
    private TextView metricsView;
    private TextView evidenceView;
    private EditText promptEdit;
    private ProgressBar progressBar;
    private Button runButton;
    private long nativeHandle = 0;
    private long sessionCreateMs = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity.onCreate");
        setContentView(createContentView());
        if (getIntent().getBooleanExtra("autorun", false)) {
            String prompt = getIntent().getStringExtra("prompt");
            if (prompt != null && !prompt.isEmpty()) {
                promptEdit.setText(prompt);
            }
            Log.i(TAG, "Autorun requested, prompt length=" + promptEdit.getText().length());
            runQuery();
        }
    }

    @Override
    protected void onDestroy() {
        long handle = nativeHandle;
        nativeHandle = 0;
        if (handle != 0) {
            executor.execute(() -> GenieNative.release(handle));
        }
        executor.shutdown();
        super.onDestroy();
    }

    private View createContentView() {
        int pad = dp(16);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scrollView.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("Qwen2.5 QNN HTP Demo");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setText(defaultStatusText());
        statusView.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        promptEdit = new EditText(this);
        promptEdit.setMinLines(3);
        promptEdit.setText("把空调调到24度，只回答意图。");
        promptEdit.setGravity(Gravity.TOP | Gravity.START);
        root.addView(promptEdit, new LinearLayout.LayoutParams(-1, -2));

        runButton = new Button(this);
        runButton.setText("Run");
        runButton.setOnClickListener(v -> runQuery());
        root.addView(runButton, new LinearLayout.LayoutParams(-1, -2));

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(-2, -2));

        metricsView = new TextView(this);
        metricsView.setTextSize(14);
        metricsView.setPadding(0, dp(10), 0, dp(10));
        metricsView.setText(defaultMetricsText());
        root.addView(metricsView, new LinearLayout.LayoutParams(-1, -2));

        outputView = new TextView(this);
        outputView.setTextSize(15);
        outputView.setMovementMethod(new ScrollingMovementMethod());
        outputView.setText("Output will appear here.");
        outputView.setMinLines(3);
        LinearLayout.LayoutParams outputParams = new LinearLayout.LayoutParams(-1, -2);
        outputParams.topMargin = dp(8);
        outputParams.bottomMargin = dp(8);
        root.addView(outputView, outputParams);

        evidenceView = new TextView(this);
        evidenceView.setTextSize(13);
        evidenceView.setPadding(0, dp(8), 0, dp(8));
        evidenceView.setText("Runtime evidence\nNot collected yet.");
        root.addView(evidenceView, new LinearLayout.LayoutParams(-1, -2));

        return scrollView;
    }

    private String defaultStatusText() {
        File modelRoot = getModelRoot();
        return "Genie: " + GenieNative.version()
                + "\nRoute: Genie / QnnHtp / Qwen2.5-0.5B / SM8550 V73"
                + "\nModel root: " + modelRoot.getAbsolutePath()
                + "\nQuality note: HTP natural-language quality baseline.";
    }

    private String defaultMetricsText() {
        return "Metrics\n"
                + "Session init: -\n"
                + "Query wall time: -\n"
                + "End-to-end time: -\n"
                + "Prompt chars: -\n"
                + "Output chars: -\n"
                + "Output chars/s: -\n"
                + "Prompt tokens: -\n"
                + "Generated tokens: -\n"
                + "TTFT: -\n"
                + "Prompt rate: -\n"
                + "Token rate: -\n"
                + "Max tokens: " + MAX_TOKENS + " | Threads: " + THREAD_COUNT;
    }

    private String formatMetrics(RunMetrics metrics) {
        return "Metrics\n"
                + "Session init: " + formatMs(metrics.sessionCreateMs) + "\n"
                + "Query wall time: " + metrics.queryMs + " ms\n"
                + "End-to-end time: " + metrics.totalMs + " ms\n"
                + "Prompt chars: " + metrics.promptChars + "\n"
                + "Output chars: " + metrics.outputChars + "\n"
                + "Output chars/s: " + formatRate(metrics.outputCharsPerSecond) + "\n"
                + "Prompt tokens: " + metrics.profile.promptTokens + "\n"
                + "Generated tokens: " + metrics.profile.generatedTokens + "\n"
                + "TTFT: " + formatMicrosAsMs(metrics.profile.timeToFirstTokenUs) + "\n"
                + "Prompt rate: " + formatRate(metrics.profile.promptTokensPerSecond) + " tok/s\n"
                + "Token rate: " + formatRate(metrics.profile.generatedTokensPerSecond) + " tok/s\n"
                + "Max tokens: " + MAX_TOKENS + " | Threads: " + THREAD_COUNT;
    }

    private String formatMs(long value) {
        return value < 0 ? "reused" : value + " ms";
    }

    private String formatRate(double value) {
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    private String formatMicrosAsMs(long value) {
        return value < 0 ? "-" : String.format(java.util.Locale.US, "%.3f ms", value / 1000.0);
    }

    private File getModelRoot() {
        return new File("/data/local/tmp/genie_qwen25_quality");
    }

    private void runQuery() {
        String userPrompt = promptEdit.getText().toString();
        String prompt = buildChatPrompt(userPrompt);
        File modelRoot = getModelRoot();
        Log.i(TAG, "runQuery start, modelRoot=" + modelRoot.getAbsolutePath()
                + ", promptLength=" + prompt.length());
        setBusy(true);
        outputView.setText("Output\nRunning...");
        metricsView.setText("Metrics\nRunning...");
        evidenceView.setText("Runtime evidence\nCollecting after query...");

        executor.execute(() -> {
            try {
                long totalStart = System.nanoTime();
                long createMsForRun = -1;
                if (nativeHandle == 0) {
                    Log.i(TAG, "Creating native Genie session.");
                    long createStart = System.nanoTime();
                    nativeHandle = GenieNative.create(modelRoot.getAbsolutePath(), MAX_TOKENS, THREAD_COUNT);
                    createMsForRun = (System.nanoTime() - createStart) / 1_000_000L;
                    sessionCreateMs = createMsForRun;
                    Log.i(TAG, "Native Genie session created, handle=" + nativeHandle);
                }
                long start = System.nanoTime();
                Log.i(TAG, "Native query start.");
                String result = GenieNative.query(nativeHandle, prompt);
                ProfileMetrics profile = parseProfileMetrics(GenieNative.profileJson(nativeHandle));
                long queryMs = (System.nanoTime() - start) / 1_000_000L;
                long totalMs = (System.nanoTime() - totalStart) / 1_000_000L;
                int outputChars = result == null ? 0 : result.length();
                double outputCharsPerSecond = queryMs > 0 ? outputChars * 1000.0 / queryMs : 0.0;
                RunMetrics metrics = new RunMetrics(
                        createMsForRun,
                        queryMs,
                        totalMs,
                        userPrompt.length(),
                        outputChars,
                        outputCharsPerSecond,
                        profile);
                String evidence = GenieNative.runtimeEvidence();
                Log.i(TAG, "Runtime evidence:\n" + evidence);
                Log.i(TAG, "Native query finished, queryMs=" + queryMs
                        + ", totalMs=" + totalMs
                        + ", resultLength=" + outputChars
                        + ", outputCharsPerSecond=" + outputCharsPerSecond);
                mainHandler.post(() -> {
                    statusView.setText(defaultStatusText());
                    metricsView.setText(formatMetrics(metrics));
                    evidenceView.setText("Runtime evidence\n" + evidence);
                    outputView.setText("Output\n" + result);
                    setBusy(false);
                });
            } catch (Throwable t) {
                Log.e(TAG, "runQuery failed", t);
                mainHandler.post(() -> {
                    outputView.setText("Output\n" + t);
                    setBusy(false);
                });
            }
        });
    }

    private void setBusy(boolean busy) {
        runButton.setEnabled(!busy);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String buildChatPrompt(String userPrompt) {
        return "<|im_start|>system\n"
                + SYSTEM_PROMPT
                + "<|im_end|>\n"
                + "<|im_start|>user\n"
                + userPrompt
                + "<|im_end|>\n"
                + "<|im_start|>assistant\n";
    }

    private ProfileMetrics parseProfileMetrics(String profileJson) {
        ProfileMetrics empty = new ProfileMetrics();
        try {
            JSONObject root = new JSONObject(profileJson == null ? "{}" : profileJson);
            JSONArray components = root.optJSONArray("components");
            if (components == null) {
                return empty;
            }
            for (int i = 0; i < components.length(); i++) {
                JSONObject component = components.optJSONObject(i);
                if (component == null) {
                    continue;
                }
                JSONArray events = component.optJSONArray("events");
                if (events == null) {
                    continue;
                }
                for (int j = 0; j < events.length(); j++) {
                    JSONObject event = events.optJSONObject(j);
                    if (event == null || !"GenieDialog_query".equals(event.optString("type"))) {
                        continue;
                    }
                    empty.promptTokens = metricLong(event, "num-prompt-tokens");
                    empty.generatedTokens = metricLong(event, "num-generated-tokens");
                    empty.timeToFirstTokenUs = metricLong(event, "time-to-first-token");
                    empty.promptTokensPerSecond = metricDouble(event, "prompt-processing-rate");
                    empty.generatedTokensPerSecond = metricDouble(event, "token-generation-rate");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse Genie profile JSON.", e);
        }
        return empty;
    }

    private long metricLong(JSONObject event, String key) {
        JSONObject metric = event.optJSONObject(key);
        return metric == null ? -1 : metric.optLong("value", -1);
    }

    private double metricDouble(JSONObject event, String key) {
        JSONObject metric = event.optJSONObject(key);
        return metric == null ? 0.0 : metric.optDouble("value", 0.0);
    }

    private static final class RunMetrics {
        final long sessionCreateMs;
        final long queryMs;
        final long totalMs;
        final int promptChars;
        final int outputChars;
        final double outputCharsPerSecond;
        final ProfileMetrics profile;

        RunMetrics(long sessionCreateMs,
                   long queryMs,
                   long totalMs,
                   int promptChars,
                   int outputChars,
                   double outputCharsPerSecond,
                   ProfileMetrics profile) {
            this.sessionCreateMs = sessionCreateMs;
            this.queryMs = queryMs;
            this.totalMs = totalMs;
            this.promptChars = promptChars;
            this.outputChars = outputChars;
            this.outputCharsPerSecond = outputCharsPerSecond;
            this.profile = profile;
        }
    }

    private static final class ProfileMetrics {
        long promptTokens = -1;
        long generatedTokens = -1;
        long timeToFirstTokenUs = -1;
        double promptTokensPerSecond = 0.0;
        double generatedTokensPerSecond = 0.0;
    }
}
