package com.vio.vioaiagent.agent.model;

/**
 * 智能体执行状态枚举
 * <p>
 * 控制智能体的生命周期状态转换：
 * <pre>
 *   IDLE → RUNNING → FINISHED
 *                  → ERROR
 * </pre>
 *
 * @author vio
 */
public enum AgentState {

    /**
     * 空闲状态 — 智能体尚未开始执行或已执行完毕
     */
    IDLE,

    /**
     * 运行中状态 — 智能体正在执行 Agent Loop
     */
    RUNNING,

    /**
     * 已完成状态 — 任务正常完成（调用了 terminate 工具或达到最大步数）
     */
    FINISHED,

    /**
     * 错误状态 — 执行过程中发生异常
     */
    ERROR
}
