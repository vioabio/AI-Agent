package com.vio.vioaiagent.run;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository {
    default Optional<ConversationConstraints> constraintsForUser(String id, String userId) { return Optional.empty(); }
    /** expected.revision is a CAS token; never silently replace a newer setting. */
    default boolean replaceConstraintsForUser(String id, String userId, ConversationConstraints expected) {
        throw new UnsupportedOperationException("当前会话仓储不支持对话偏好");
    }
    Conversation save(Conversation conversation);
    /**
     * 非授权探测仅供防止创建时覆盖同名会话；生产实现应覆盖此方法以执行唯一性检查。
     */
    default Optional<Conversation> findById(String conversationId) {
        return Optional.empty();
    }
    Optional<Conversation> findByIdForUser(String conversationId, String userId);
    default List<Conversation> findForUser(String userId, int limit) { return List.of(); }
    /** 按稳定的服务端游标 offset 读取集合；旧实现默认以内存切片兼容。 */
    default List<Conversation> findForUser(String userId, int limit, int offset) {
        if (offset < 0 || limit <= 0) return List.of();
        // Controller requests one look-ahead row (public max 200 + 1) so it can
        // determine whether a next cursor exists without a COUNT query.
        List<Conversation> values = findForUser(userId, Math.min(201, offset + limit));
        return values.stream().skip(offset).limit(limit).toList();
    }
    default boolean updateTitleForUser(String conversationId, String userId, String title) { return false; }
    /**
     * 让会话对所属用户不可见。生产实现可使用软删除以保留 append-only
     * Run/答案/审批审计；调用方不得假定该方法已经完成物理擦除。
     */
    void deleteForUser(String conversationId, String userId);
}
