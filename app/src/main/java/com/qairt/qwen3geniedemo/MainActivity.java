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

public class MainActivity extends Activity {
    private static final String TAG = "Qwen3GenieDemo";
    private static final int MAX_TOKENS = 64;
    private static final int THREAD_COUNT = 6;

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
        title.setText("Qwen3 Genie JNI Demo");
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
        promptEdit.setText("Explain artificial intelligence in one sentence.");
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

        evidenceView = new TextView(this);
        evidenceView.setTextSize(13);
        evidenceView.setPadding(0, dp(8), 0, dp(8));
        evidenceView.setText("Runtime evidence\nNot collected yet.");
        root.addView(evidenceView, new LinearLayout.LayoutParams(-1, -2));

        outputView = new TextView(this);
        outputView.setTextSize(15);
        outputView.setMovementMethod(new ScrollingMovementMethod());
        outputView.setText("Output will appear here.");
        outputView.setMinLines(8);
        LinearLayout.LayoutParams outputParams = new LinearLayout.LayoutParams(-1, -2);
        outputParams.topMargin = dp(12);
        root.addView(outputView, outputParams);

        return scrollView;
    }

    private String defaultStatusText() {
        File modelRoot = getModelRoot();
        return "Genie: " + GenieNative.version()
                + "\nModel root: " + modelRoot.getAbsolutePath();
    }

    private String defaultMetricsText() {
        return "Metrics\n"
                + "Session init: -\n"
                + "Query wall time: -\n"
                + "End-to-end time: -\n"
                + "Prompt chars: -\n"
                + "Output chars: -\n"
                + "Output chars/s: -\n"
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
                + "Max tokens: " + MAX_TOKENS + " | Threads: " + THREAD_COUNT;
    }

    private String formatMs(long value) {
        return value < 0 ? "reused" : value + " ms";
    }

    private String formatRate(double value) {
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    private File getModelRoot() {
        return new File("/data/local/tmp/qwen3-0.6b-genaitransformer");
    }

    private void runQuery() {
        String prompt = promptEdit.getText().toString();
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
                long queryMs = (System.nanoTime() - start) / 1_000_000L;
                long totalMs = (System.nanoTime() - totalStart) / 1_000_000L;
                int outputChars = result == null ? 0 : result.length();
                double outputCharsPerSecond = queryMs > 0 ? outputChars * 1000.0 / queryMs : 0.0;
                RunMetrics metrics = new RunMetrics(
                        createMsForRun,
                        queryMs,
                        totalMs,
                        prompt.length(),
                        outputChars,
                        outputCharsPerSecond);
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

    private static final class RunMetrics {
        final long sessionCreateMs;
        final long queryMs;
        final long totalMs;
        final int promptChars;
        final int outputChars;
        final double outputCharsPerSecond;

        RunMetrics(long sessionCreateMs,
                   long queryMs,
                   long totalMs,
                   int promptChars,
                   int outputChars,
                   double outputCharsPerSecond) {
            this.sessionCreateMs = sessionCreateMs;
            this.queryMs = queryMs;
            this.totalMs = totalMs;
            this.promptChars = promptChars;
            this.outputChars = outputChars;
            this.outputCharsPerSecond = outputCharsPerSecond;
        }
    }
}
