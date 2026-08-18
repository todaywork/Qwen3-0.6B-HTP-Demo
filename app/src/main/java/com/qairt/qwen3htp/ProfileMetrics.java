package com.qairt.qwen3htp;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

final class ProfileMetrics {
    private static final String TAG = "Qwen3HtpDemo";

    long promptTokens = -1;
    long generatedTokens = -1;
    long timeToFirstTokenUs = -1;
    long durationUs = -1;
    long tokenGenerationTimeUs = -1;
    double promptTokensPerSecond = 0.0;
    double generatedTokensPerSecond = 0.0;

    // Token 交叉验证字段
    long profilePromptTokens = -1;   // Genie SDK profile 原始值
    long manualPromptTokens = -1;    // JNI 层手动 encode 值
    boolean tokenMismatch = false;
    long tokenDiff = 0;

    static ProfileMetrics parse(String profileJson) {
        ProfileMetrics result = new ProfileMetrics();
        try {
            JSONObject root = new JSONObject(profileJson == null ? "{}" : profileJson);
            JSONArray components = root.optJSONArray("components");
            if (components == null) return result;
            for (int i = 0; i < components.length(); i++) {
                JSONObject component = components.optJSONObject(i);
                JSONArray events = component != null ? component.optJSONArray("events") : null;
                if (events == null) continue;
                for (int j = 0; j < events.length(); j++) {
                    JSONObject event = events.optJSONObject(j);
                    if (event == null || !"GenieDialog_query".equals(event.optString("type"))) continue;
                    result.promptTokens = metricLong(event, "num-prompt-tokens");
                    result.generatedTokens = metricLong(event, "num-generated-tokens");
                    result.timeToFirstTokenUs = metricLong(event, "time-to-first-token");
                    result.durationUs = event.optLong("duration", -1);
                    result.tokenGenerationTimeUs = metricLong(event, "token-generation-time");
                    result.promptTokensPerSecond = metricDouble(event, "prompt-processing-rate");
                    result.generatedTokensPerSecond = metricDouble(event, "token-generation-rate");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse Genie profile JSON.", e);
        }
        return result;
    }

    private static long metricLong(JSONObject event, String key) {
        JSONObject metric = event.optJSONObject(key);
        return metric == null ? -1 : metric.optLong("value", -1);
    }

    private static double metricDouble(JSONObject event, String key) {
        JSONObject metric = event.optJSONObject(key);
        return metric == null ? 0.0 : metric.optDouble("value", 0.0);
    }
}
