package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@org.springframework.context.annotation.Profile("!prod")
@Primary
public class InMemoryConversationRepository implements ConversationRepository {
    private final ConcurrentMap<String, Conversation> conversations = new ConcurrentHashMap<>();

    @Override
    public Conversation save(Conversation conversation) {
        conversations.put(conversation.id(), conversation);
        return conversation;
    }

    @Override
    public Optional<Conversation> findById(String conversationId) {
        return Optional.ofNullable(conversations.get(conversationId));
    }

    @Override
    public Optional<Conversation> findByIdForUser(String conversationId, String userId) {
        if (conversationId == null || userId == null) return Optional.empty();
        return Optional.ofNullable(conversations.get(conversationId)).filter(c -> userId.equals(c.userId()));
    }

    @Override
    public List<Conversation> findForUser(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) return List.of();
        return conversations.values().stream().filter(item -> userId.equals(item.userId()))
                .sorted(Comparator.comparing(Conversation::updatedAt).reversed()
                        .thenComparing(Conversation::id))
                .limit(Math.min(limit, 201)).toList();
    }

    @Override
    public List<Conversation> findForUser(String userId, int limit, int offset) {
        if (userId == null || userId.isBlank() || limit <= 0 || offset < 0) return List.of();
        return conversations.values().stream().filter(item -> userId.equals(item.userId()))
                .sorted(Comparator.comparing(Conversation::updatedAt).reversed()
                        .thenComparing(Conversation::id))
                .skip(offset).limit(Math.min(limit, 201)).toList();
    }

    @Override
    public boolean updateTitleForUser(String conversationId, String userId, String title) {
        if (title == null || title.isBlank()) return false;
        Conversation current = conversations.get(conversationId);
        if (current == null || !userId.equals(current.userId())) return false;
        Conversation updated = new Conversation(current.id(), current.userId(), title.trim(),
                current.createdAt(), java.time.Instant.now());
        return conversations.replace(conversationId, current, updated);
    }

    @Override
    public void deleteForUser(String conversationId, String userId) {
        findByIdForUser(conversationId, userId).ifPresent(c -> conversations.remove(c.id(), c));
    }
}
