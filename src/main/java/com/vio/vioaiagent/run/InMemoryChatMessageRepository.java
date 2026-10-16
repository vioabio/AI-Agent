package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@org.springframework.context.annotation.Profile("!prod & !demo")
@Primary
public class InMemoryChatMessageRepository implements ChatMessageRepository {
    private final CopyOnWriteArrayList<ChatMessage> messages = new CopyOnWriteArrayList<>();

    @Override
    public ChatMessage save(ChatMessage message) {
        messages.addIfAbsent(message);
        return message;
    }

    @Override
    public List<ChatMessage> findByConversationForUser(String conversationId, String userId) {
        return messages.stream()
                .filter(m -> conversationId.equals(m.conversationId()) && userId.equals(m.userId()))
                .sorted(Comparator.comparing(ChatMessage::createdAt).thenComparing(ChatMessage::id))
                .toList();
    }

    @Override
    public void deleteByConversation(String conversationId) {
        messages.removeIf(m -> conversationId.equals(m.conversationId()));
    }
}
