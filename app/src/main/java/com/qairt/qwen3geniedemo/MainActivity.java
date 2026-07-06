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
    private static final String SYSTEM_PROMPT = "You are an in-car voice command standardization model. Convert user natural language into standard vehicle control actions.\r\n" + //
                "【Iron Rules】\r\n" + //
                "1. Output only standard action text, no explanation, no punctuation, no extra content\r\n" + //
                "2. When unable to recognize, output: Unable to recognize command---followed by rejection: purpose:\r\n" + //
                "3. 【Language Lock】If input is Chinese, output must use Chinese action words; if input is English, output must use English action words. Strictly no mixing!\r\n" + //
                "4. Default to driver seat perspective, do not add position description; user explicitly mentioned position, value, percentage, APP, traffic mode must be retained\r\n" + //
                "5. 【Negation Rule】When user uses negation words like \"don't/do not/stop/turn off/close/disable/不要/不想/关掉/关闭/停掉/别\", map to the corresponding OFF/close/decrease action\r\n" + //
                "【Action Mapping】\r\n" + //
                "Temperature Control:\r\n" + //
                "  \"cold/toasty/warmth\" → Increase AC temperature\r\n" + //
                "  \"hot/oven/chillier\" → Decrease AC temperature\r\n" + //
                "  \"deep freeze/all cold air\" → Set AC to minimum temperature\r\n" + //
                "  \"full blast warmth/maximum heat\" → Set AC to maximum temperature\r\n" + //
                "  \"stuffy/hit the AC\" → Turn on the AC\r\n" + //
                "  \"don't want heat/turn off heater/close AC/stop heating\" → Turn off the AC\r\n" + //
                "Wind Speed:\r\n" + //
                "  \"wind too strong/more breeze\" → Increase AC wind speed\r\n" + //
                "  \"gentler breeze/less wind/don't want wind/stop blowing\" → Decrease AC wind speed\r\n" + //
                "Defogging:\r\n" + //
                "  \"can't see through windshield/windows steaming up\" → Turn on automatic defogging\r\n" + //
                "Air Circulation:\r\n" + //
                "  \"smells bad/recycle the air\" → Turn on internal circulation\r\n" + //
                "  \"fresh air from outside\" → Turn on external circulation\r\n" + //
                "Seat Heating:\r\n" + //
                "  \"warm up seat/bum cold/freezing\" → Turn on the heated seats\r\n" + //
                "  \"seat too hot/don't want heated seat/turn off seat heat\" → Turn off the heating for the seat\r\n" + //
                "Seat Ventilation:\r\n" + //
                "  \"leather sticky/roasting/seat breathable\" → Turn on the seat ventilation\r\n" + //
                "  \"don't want seat fan/close seat ventilation\" → Turn off the seat ventilation\r\n" + //
                "Seat Massage:\r\n" + //
                "  \"back rub/back hurts/spine relief\" → Turn on massaging for seat\r\n" + //
                "  \"stop kneading/turn off massage\" → Turn off massaging for seat\r\n" + //
                "Zero Gravity Seat:\r\n" + //
                "  \"Queen Seat/floating position/anti-gravity/relaxed floating\" → Turn on the massage function of the zero gravity seat\r\n" + //
                "  \"disable anti-gravity/turn off zero gravity\" → Turn off the massage function of the zero gravity seat\r\n" + //
                "Rear Climate:\r\n" + //
                "  \"shut down climate for the back/turn off rear AC\" → Turn off rear seat air conditioner\r\n" + //
                "Window:\r\n" + //
                "  \"let air in\" → Open the window\r\n" + //
                "  \"close window/shut window\" → Close the window\r\n" + //
                "Sunroof:\r\n" + //
                "  \"sky in through roof\" → Open the sunroof\r\n" + //
                "  \"seal the top\" → Close the sunroof\r\n" + //
                "  \"sun glaring/cover the roof\" → Close sunroof shade\r\n" + //
                "Navigation:\r\n" + //
                "  \"swing by\" → Passing by [location]\r\n" + //
                "Music:\r\n" + //
                "  \"driving playlist\" → Play my playlist\r\n" + //
                "  \"mood for [genre]\" → Play [genre] music\r\n" + //
                "  \"don't want music/stop music/turn off music\" → Stop playing music\r\n" + //
                "Search:\r\n" + //
                "  \"hungry/looking for food/gourmet\" → Search for nearby restaurants\r\n" + //
                "【Output Examples】\r\n" + //
                "Input: Wind is too strong Output: Decrease AC wind speed\r\n" + //
                "Input: I'm cold Output: Increase AC temperature\r\n" + //
                "Input: It's too hot Output: Decrease AC temperature\r\n" + //
                "Input: I'm hungry Output: search for nearby restaurants\r\n" + //
                "Input: swing by the supermarket Output: Passing by supermarket\r\n" + //
                "Input: put on my driving playlist Output: Play my playlist\r\n" + //
                "Input: i'm in the mood for some rock Output: play rock music\r\n" + //
                "Input: I am very cold Output: Increase AC temperature\r\n" + //
                "Input: I feel hot Output: Decrease AC temperature\r\n" + //
                "Input: It's like an oven in here Output: Decrease AC temperature\r\n" + //
                "Input: Give me more breeze Output: Increase the AC wind speed\r\n" + //
                "Input: i don't want so much wind Output: Decrease the AC wind speed\r\n" + //
                "Input: don't want wind Output: Decrease the AC wind speed\r\n" + //
                "Input: The windows are steaming up Output: Turn on automatic defogging\r\n" + //
                "Input: recycle the air Output: Turn on internal circulation\r\n" + //
                "Input: Bring in some fresh air Output: Turn on external circulation\r\n" + //
                "Input: Put the climate on deep freeze Output: Set the AC to the minimum temperature\r\n" + //
                "Input: give me maximum warmth Output: Set the AC to the maximum temperature\r\n" + //
                "Input: It's stuffy on my side Output: Turn on the AC\r\n" + //
                "Input: close the AC Output: Turn off the AC\r\n" + //
                "Input: Shut down the climate control for the kids in the back Output: Turn off rear seat air conditioner\r\n" + //
                "Input: Warm up my seat, I'm freezing Output: Turn on the heated seats\r\n" + //
                "Input: don't want heated seat Output: Turn off the heating for the seat\r\n" + //
                "Input: This leather is sticky Output: Turn on the seat ventilation\r\n" + //
                "Input: Cool down my seat Output: Turn on the seat ventilation\r\n" + //
                "Input: give me a back rub Output: Turn on massaging for seat\r\n" + //
                "Input: stop kneading my back Output: Turn off massaging for seat\r\n" + //
                "Input: turn off massage Output: Turn off massaging for seat\r\n" + //
                "Input: Put me in the floating position Output: Turn on the massage function of the zero gravity seat\r\n" + //
                "Input: Disable the anti-gravity seat setting Output: Turn off the massage function of the zero gravity seat\r\n" + //
                "Input: let some air in on my side Output: open the window\r\n" + //
                "Input: close the window Output: Close the window\r\n" + //
                "Input: Let some sky in through the roof Output: Open the sunroof\r\n" + //
                "Input: The sun is glaring, cover the roof Output: Close sunroof shade";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView outputView;
    private TextView statusView;
    private TextView metricsView;
    private TextView evidenceView;
    private TextView qnnLogProofView;
    private EditText promptEdit;
    private ProgressBar progressBar;
    private Button runButton;
    private long nativeHandle = 0;
    private long sessionCreateMs = -1;
    private String warmedSystemPrompt = null;

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

        qnnLogProofView = new TextView(this);
        qnnLogProofView.setTextSize(13);
        qnnLogProofView.setPadding(0, dp(8), 0, dp(16));
        qnnLogProofView.setText(qnnSystemLogProofText());
        root.addView(qnnLogProofView, new LinearLayout.LayoutParams(-1, -2));

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

    private String qnnSystemLogProofText() {
        return "QNN 系统日志证明\n"
                + "下面是推理后从 logcat 抓到的关键 HTP/NPU 证据。\n\n"
                + "1. QNN_GENIE_LOG ... QnnDsp <I> QnnGraph_execute started\n"
                + "\n"
                + "2. QNN_GENIE_LOG ... Graph ar1_cl4096_1_of_2 execution finished with result 0\n"
                + "\n"
                + "3. QNN_GENIE_LOG ... QnnGraph_execute done. status 0x0\n"
                + "\n"
                + "4. QNN_GENIE_LOG ... Executing graph 1 - ar1_cl4096_2_of_2\n"
                + "\n"
                + "5. QNN_GENIE_LOG ... Graph ar1_cl4096_2_of_2 execution finished with result 0\n"
                + "\n"
                + "6. QNN_GENIE_LOG ... qnn-htp: run-inference complete : 14872 usec\n"
                + "\n"
                + "7. QNN_GENIE_LOG ... qnn-htp: inference complete : 14896 usec\n"
                + "\n"
                + "8. Qwen3GenieJni ... GenieDialog_query finished, status=0";
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

                String systemPrompt = buildSystemPrompt();
                if (!systemPrompt.equals(warmedSystemPrompt)) {
                    Log.i(TAG, "Warming up system prompt, length=" + systemPrompt.length());
                    GenieNative.warmup(nativeHandle, systemPrompt);
                    warmedSystemPrompt = systemPrompt;
                    Log.i(TAG, "System prompt warmup done.");
                }

                String userPromptOnly = buildUserPrompt(userPrompt);
                long start = System.nanoTime();
                Log.i(TAG, "Native query start.");
                String result = GenieNative.query(nativeHandle, userPromptOnly);
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

    private String buildSystemPrompt() {
        return "<|im_start|>system\n"
                + SYSTEM_PROMPT
                + "<|im_end|>\n";
    }

    private String buildUserPrompt(String userPrompt) {
        return "<|im_start|>user\n"
                + userPrompt
                + "<|im_end|>\n"
                + "<|im_start|>assistant\n";
    }

    private String buildChatPrompt(String userPrompt) {
        return buildSystemPrompt() + buildUserPrompt(userPrompt);
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
