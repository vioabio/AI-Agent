package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@org.springframework.context.annotation.Profile("!prod & !demo")
@Primary
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "vio.local-history.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryConversationRepository implements ConversationRepository {
    private final ConcurrentMap<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConversationConstraints> constraints = new ConcurrentHashMap<>();

    @Override
    public synchronized Conversation save(Conversation conversation) {
        var existing = conversations.get(conversation.id());
        if (existing != null && !existing.userId().equals(conversation.userId())) throw new IllegalStateException("会话归属冲突");
        conversations.put(conversation.id(), conversation);
        return conversation;
    }
    @Override
    public synchronized Optional<ConversationConstraints> constraintsForUser(String id, String userId) {
        return findByIdForUser(id, userId).isPresent() ? Optional.ofNullable(constraints.get(id)) : Optional.empty();
    }
    @Override
    public synchronized boolean replaceConstraintsForUser(String id, String userId, ConversationConstraints expected) {
        if (findByIdForUser(id, userId).isEmpty() || constraints.getOrDefault(id, ConversationConstraints.defaults()).revision() != expected.revision()) return false;
        constraints.put(id, expected.next()); return true;
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
    public synchronized void deleteForUser(String conversationId, String userId) {
        findByIdForUser(conversationId, userId).ifPresent(c -> { conversations.remove(c.id(), c); constraints.remove(c.id()); });
    }
}
