package com.vio.vioaiagent.memory.model;

/**
 * 记忆类型 — 四种记忆分类。
 *
 * @author vio
 */
public enum MemoryType {
    /** 对话记录 */
    CONVERSATION(0.7),
    /** 提取的事实 */
    FACT(1.0),
    /** 压缩摘要 */
    SUMMARY(0.8),
    /** 工具执行结果 */
    TOOL_RESULT(0.5);

    private final double sourceWeight;

    MemoryType(double sourceWeight) { this.sourceWeight = sourceWeight; }

    /** 来源权重（用于检索排序） */
    public double getSourceWeight() { return sourceWeight; }
}
