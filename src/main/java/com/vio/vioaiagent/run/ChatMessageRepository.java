package com.vio.vioaiagent.run;

import java.util.List;

public interface ChatMessageRepository {
    ChatMessage save(ChatMessage message);
    List<ChatMessage> findByConversationForUser(String conversationId, String userId);
    void deleteByConversation(String conversationId);
}

