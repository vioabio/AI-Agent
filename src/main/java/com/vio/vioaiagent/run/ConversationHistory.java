package com.vio.vioaiagent.run;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.*;

/** Include only completed prior turns from this owner/conversation; never tool/system roles. */
final class ConversationHistory {
    private ConversationHistory() { }

    static List<Message> window(AgentRun run, List<ChatMessage> stored) {
        if (stored == null) return List.of();
        var eligible = stored.stream().filter(m -> run.userId().equals(m.userId())
                && run.conversationId().equals(m.conversationId())
                && !run.id().equals(m.runId()) && !m.createdAt().isAfter(run.createdAt()))
                .sorted(Comparator.comparing(ChatMessage::createdAt).thenComparing(ChatMessage::id)).toList();
        Set<String> completed = new HashSet<>();
        eligible.stream().filter(m -> "assistant".equals(m.role())).forEach(m -> completed.add(m.runId()));
        List<Message> result = new ArrayList<>();
        int remaining = 12000;
        for (int i = eligible.size() - 1; i >= 0 && result.size() < 20; i--) {
            var m = eligible.get(i);
            if (!completed.contains(m.runId()) || !("user".equals(m.role()) || "assistant".equals(m.role()))) continue;
            if (m.content().length() > remaining) break;
            remaining -= m.content().length();
            result.add("user".equals(m.role()) ? new UserMessage(m.content()) : new AssistantMessage(m.content()));
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }
}
