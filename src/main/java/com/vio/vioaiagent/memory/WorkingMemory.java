package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.memory.model.MemoryEntry;
import com.vio.vioaiagent.memory.model.MemoryType;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 工作记忆 — 当前任务上下文 + 最近对话（不参与压缩）。
 *
 * <p>容量：最多 3 轮完整对话（每轮 = user message + assistant response）+
 * 当前 step 序列。超出部分移入短期记忆。
 *
 * @author vio
 */
@Slf4j
public class WorkingMemory {

    /** 保留的最近完整对话轮数 */
    private static final int RECENT_ROUNDS = 3;
    /** 每轮消息数（user + assistant = 2） */
    private static final int MESSAGES_PER_ROUND = 2;

    private final Deque<MemoryEntry> conversations = new ConcurrentLinkedDeque<>();
    private final Deque<MemoryEntry> stepResults = new ConcurrentLinkedDeque<>();
    private String currentTask;

    /** 记录一条对话 */
    public void addConversation(MemoryEntry entry) {
        conversations.addLast(entry);
        // 超出保留轮数 → 移入短期记忆候选
        while (conversations.size() > RECENT_ROUNDS * MESSAGES_PER_ROUND) {
            MemoryEntry old = conversations.pollFirst();
            if (old != null) log.debug("工作记忆移出: {}", old.id());
        }
    }

    /** 记录当前步的结果 */
    public void addStepResult(String description) {
        MemoryEntry entry = MemoryEntry.of(MemoryType.TOOL_RESULT, description, "current");
        stepResults.addLast(entry);
    }

    /** 设置当前任务 */
    public void setCurrentTask(String task) { this.currentTask = task; }

    /** 获取当前任务 */
    public String getCurrentTask() { return currentTask; }

    /** 获取最近 N 条对话 */
    public List<MemoryEntry> recentConversations() {
        return new ArrayList<>(conversations);
    }

    /** 获取所有工作记忆内容 */
    public List<MemoryEntry> getAll() {
        List<MemoryEntry> all = new ArrayList<>(conversations);
        all.addAll(stepResults);
        return all;
    }

    /** 估算工作记忆 Token 数 */
    public int estimatedTokens() {
        int total = 0;
        for (MemoryEntry e : conversations) total += e.tokens();
        for (MemoryEntry e : stepResults) total += e.tokens();
        if (currentTask != null) total += TokenEstimator.estimate(currentTask);
        return total;
    }

    /** 提取超出保留轮数的旧对话（供移入短期记忆） */
    public List<MemoryEntry> drainOverflow() {
        List<MemoryEntry> overflow = new ArrayList<>();
        while (conversations.size() > RECENT_ROUNDS * MESSAGES_PER_ROUND) {
            MemoryEntry e = conversations.pollFirst();
            if (e != null) overflow.add(e);
        }
        return overflow;
    }

    /** 清空步结果 */
    public void clearSteps() { stepResults.clear(); }

    /** 完全清空 */
    public void clear() { conversations.clear(); stepResults.clear(); currentTask = null; }
}
