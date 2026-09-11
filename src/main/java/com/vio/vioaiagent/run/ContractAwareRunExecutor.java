package com.vio.vioaiagent.run;

import com.vio.vioaiagent.rag.contract.AnswerContract;

/**
 * 可选的结构化 Run 执行器扩展。
 *
 * <p>RunService 仍保留 {@link RunExecutor} 的文本兼容边界；实现此接口的执行器
 * 可以额外返回版本、引用和限制，供事件流和会话持久化使用。这样旧执行器无需
 * 一次性改造，迁移期间也不会因为缺少 RAG 依赖而改变生命周期语义。</p>
 */
public interface ContractAwareRunExecutor extends RunExecutor {

    /** 执行并返回面向客户端的回答契约。 */
    AnswerContract executeContract(AgentRunContext context) throws Exception;

    /** 当前执行是否实际走版本化知识检索；关闭时不应伪造 retrieval 事件。 */
    default boolean usesKnowledgeGrounding() {
        return true;
    }

    /**
     * 返回回答使用的发布血缘。旧执行器无需实现；N4 领域执行器可覆盖该方法
     * 提供 data/knowledge/index release，RunService 会把它写入 answer_projection。
     */
    default AnswerLineage answerLineage(AgentRunContext context, AnswerContract contract) {
        return AnswerLineage.empty();
    }
}
