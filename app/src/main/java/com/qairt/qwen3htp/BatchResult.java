package com.qairt.qwen3htp;

final class BatchResult {
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
            compare = expected.trim().equals(output != null ? output.trim() : "") ? "一致" : "不一致";
        }
        return new BatchResult(index, prompt, expected, inputTokenIds,
                output, compare, metrics, null);
    }

    static BatchResult failure(int index, String prompt, String expected,
                               String inputTokenIds, Throwable error) {
        return new BatchResult(index, prompt, expected, inputTokenIds, "", "失败", null,
                error != null ? error.toString() : "Unknown error");
    }
}
