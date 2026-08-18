package com.qairt.qwen3htp;

import java.util.List;

final class PromptBuilder {
    private String systemPrompt = "";

    static final class ChatTurn {
        final String user;
        final String assistant;

        ChatTurn(String user, String assistant) {
            this.user = user != null ? user : "";
            this.assistant = assistant != null ? assistant : "";
        }
    }

    PromptBuilder systemPrompt(String rawSystemPrompt) {
        systemPrompt = rawSystemPrompt != null ? rawSystemPrompt : "";
        return this;
    }

    String buildSystemPrompt() {
        return "<|im_start|>system\n" + systemPrompt + "\n<|im_end|>\n";
    }

    String buildUserPrompt(String userPrompt) {
        // Qwen3 的 no-thinking raw prompt 在 assistant 前缀后追加空 thinking 块。
        return "<|im_start|>user\n"
                + (userPrompt != null ? userPrompt : "")+"\n"
                + "<|im_end|>\n"
                + "<|im_start|>assistant\n"
                ;
    }

    String buildChatPrompt(String userPrompt, List<ChatTurn> history, int maxHistoryTurns) {
        if (maxHistoryTurns < 0) {
            throw new IllegalArgumentException("maxHistoryTurns must be >= 0");
        }
        StringBuilder prompt = new StringBuilder(buildSystemPrompt());
        int size = history != null ? history.size() : 0;
        int firstTurn = Math.max(0, size - maxHistoryTurns);
        for (int i = firstTurn; i < size; i++) {
            ChatTurn turn = history.get(i);
            prompt.append("<|im_start|>user\n").append(turn.user)
                    .append("<|im_end|>\n<|im_start|>assistant\n")
                    .append(turn.assistant).append("<|im_end|>\n");
        }
        return prompt.append(buildUserPrompt(userPrompt)).toString();
    }
}
