package com.qairt.qwen3htp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BatchResult {
    private static final Pattern JSON_CODE_FENCE = Pattern.compile(
            "^```(?:json)?\\s*(.*?)\\s*```$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    final int index;
    final String prompt;
    final String expected;
    final String inputTokenIds;
    final String output;
    final String compareResult;
    final RunMetrics metrics;
    final String error;

    private BatchResult(int index, String prompt, String expected, String inputTokenIds,
                        String output,
                        String compareResult, RunMetrics metrics, String error) {
        this.index = index;
        this.prompt = prompt;
        this.expected = expected;
        this.inputTokenIds = inputTokenIds;
        this.output = output;
        this.compareResult = compareResult;
        this.metrics = metrics;
        this.error = error;
    }

    static BatchResult success(int index, String prompt, String expected,
                               String inputTokenIds, String output, RunMetrics metrics) {
        String compare = "";
        if (expected != null && !expected.trim().isEmpty()) {
            compare = compareAnswers(expected, output) ? "一致" : "不一致";
        }
        return new BatchResult(index, prompt, expected, inputTokenIds,
                output, compare, metrics, null);
    }

    static boolean compareAnswers(String expected, String actual) {
        String expectedText = expected == null ? "" : expected.trim();
        String actualText = actual == null ? "" : actual.trim();
        Object expectedJson = parseJson(expectedText);
        Object actualJson = parseJson(actualText);
        if (expectedJson != null && actualJson != null) {
            return jsonValuesEqual(expectedJson, actualJson);
        }
        return expectedText.equals(actualText);
    }

    private static Object parseJson(String text) {
        if (text.isEmpty()) return null;
        Matcher fence = JSON_CODE_FENCE.matcher(text);
        String candidate = fence.matches() ? fence.group(1).trim() : text;
        try {
            JSONTokener tokener = new JSONTokener(candidate);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) && !(value instanceof JSONArray)) return null;
            return tokener.nextClean() == 0 ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean jsonValuesEqual(Object expected, Object actual) {
        if (expected == actual) return true;
        if (expected == null || actual == null) return false;
        if (expected == JSONObject.NULL || actual == JSONObject.NULL) {
            return expected == JSONObject.NULL && actual == JSONObject.NULL;
        }
        if (expected instanceof JSONObject && actual instanceof JSONObject) {
            JSONObject expectedObject = (JSONObject) expected;
            JSONObject actualObject = (JSONObject) actual;
            if (expectedObject.length() != actualObject.length()) return false;
            Iterator<String> keys = expectedObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!actualObject.has(key)
                        || !jsonValuesEqual(expectedObject.opt(key), actualObject.opt(key))) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof JSONArray && actual instanceof JSONArray) {
            JSONArray expectedArray = (JSONArray) expected;
            JSONArray actualArray = (JSONArray) actual;
            if (expectedArray.length() != actualArray.length()) return false;
            for (int i = 0; i < expectedArray.length(); i++) {
                if (!jsonValuesEqual(expectedArray.opt(i), actualArray.opt(i))) return false;
            }
            return true;
        }
        if (expected instanceof Number && actual instanceof Number) {
            try {
                return new BigDecimal(expected.toString())
                        .compareTo(new BigDecimal(actual.toString())) == 0;
            } catch (NumberFormatException ignored) {
                return expected.equals(actual);
            }
        }
        return expected.getClass() == actual.getClass() && expected.equals(actual);
    }

    static BatchResult failure(int index, String prompt, String expected,
                               String inputTokenIds, Throwable error) {
        return new BatchResult(index, prompt, expected, inputTokenIds, "", "失败", null,
                error != null ? error.toString() : "Unknown error");
    }
}
