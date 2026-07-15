package com.vio.vioaiagent.security;

/**
 * 工具操作危险等级 — HITL 审批的分级依据.
 *
 * <p>三级划分：
 * <ul>
 *   <li>{@link #HIGH} — 高危：需要用户显式批准（如 execute_command）</li>
 *   <li>{@link #MEDIUM} — 中危：首次执行需确认，会话内后续可放行（如 write_file）</li>
 *   <li>{@link #SAFE} — 安全：无需审批（如 web_search）</li>
 * </ul>
 *
 * @author vio
 */
public enum DangerLevel {
    /** 高危：必须等待用户显式批准 */
    HIGH,
    /** 中危：同一会话中首次需确认，后续可放行 */
    MEDIUM,
    /** 安全：无需审批，直接执行 */
    SAFE
}
