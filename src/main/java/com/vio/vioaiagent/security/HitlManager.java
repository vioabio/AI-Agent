package com.vio.vioaiagent.security;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HITL（Human-in-the-Loop）审批管理器 — 安全体系第二层.
 *
 * <p>在工具执行前对高危/中危操作进行人工审批.
 * 审批决策根据工具的危险等级和会话内是否已授权自动判断.
 *
 * <p>使用方式（集成到 ToolCallAgent 的 act() 方法中）：
 * <pre>{@code
 * ApprovalResult result = hitlManager.approve(toolName, sessionId, params);
 * switch (result.action()) {
 *     case APPROVE, APPROVE_ALL -> executeTool();
 *     case DENY -> return "操作被拒绝: " + result.reason();
 *     case SKIP -> return "操作已跳过";
 *     case MODIFY -> executeToolWithModifiedParams();
 * }
 * }</pre>
 *
 * @author vio
 */
@Slf4j
public class HitlManager {

    /** 工具名 → 危险等级的静态映射 */
    private static final Map<String, DangerLevel> TOOL_DANGER_LEVELS = Map.ofEntries(
            Map.entry("terminal_operation", DangerLevel.HIGH),
            Map.entry("execute_terminal_command", DangerLevel.HIGH),
            Map.entry("write_file", DangerLevel.MEDIUM),
            Map.entry("delete_file", DangerLevel.HIGH),
            Map.entry("read_file", DangerLevel.SAFE),
            Map.entry("web_search", DangerLevel.SAFE),
            Map.entry("web_scraping", DangerLevel.SAFE),
            Map.entry("searchWeb", DangerLevel.SAFE),
            Map.entry("scrapeWebPage", DangerLevel.SAFE),
            Map.entry("generatePDF", DangerLevel.MEDIUM),
            Map.entry("download_resource", DangerLevel.MEDIUM),
            Map.entry("downloadResource", DangerLevel.MEDIUM)
    );

    /** 会话内已批准的工具集合（sessionId → toolNames） */
    private final Map<String, Set<String>> sessionApprovals = new ConcurrentHashMap<>();

    /**
     * 审批工具调用.
     *
     * @param toolName  工具名称
     * @param sessionId 会话 ID
     * @return 审批结果
     */
    public ApprovalResult approve(String toolName, String sessionId) {
        DangerLevel level = getDangerLevel(toolName);

        switch (level) {
            case SAFE:
                return ApprovalResult.approve(sessionId);

            case MEDIUM:
                // 会话内首次需确认，之后放行
                if (isAlreadyApprovedInSession(toolName, sessionId)) {
                    log.info("HITL: 会话内已授权 {} → 直接放行", toolName);
                    return ApprovalResult.approve(sessionId);
                }
                // 首次 → 标记为已审批并放行（可扩展为等待用户确认）
                markApprovedInSession(toolName, sessionId);
                log.info("HITL: 中危工具 {} 首次执行 → 自动授权（会话: {}）", toolName, sessionId);
                return ApprovalResult.approve(sessionId);

            case HIGH:
                // 高危 → 检查是否已授权
                if (isAlreadyApprovedInSession(toolName, sessionId)) {
                    log.info("HITL: 高危工具 {} 已在会话中授权 → 放行", toolName);
                    return ApprovalResult.approve(sessionId);
                }
                // 当前实现：高危操作需要用户显式确认（默认拒绝，等待扩展为用户交互）
                log.warn("HITL: 高危工具 {} 需要用户显式批准（会话: {}）→ 当前默认策略：拒绝",
                        toolName, sessionId);
                return ApprovalResult.deny(sessionId,
                        "高危操作 [" + toolName + "] 需要用户显式批准. "
                                + "当前 HITL 策略要求用户通过审批 UI 确认此操作.");

            default:
                return ApprovalResult.deny(sessionId, "未知工具危险等级: " + toolName);
        }
    }

    /**
     * 获取工具的危险等级.
     */
    public DangerLevel getDangerLevel(String toolName) {
        return TOOL_DANGER_LEVELS.getOrDefault(toolName, DangerLevel.SAFE);
    }

    /**
     * 用户手动批准高危操作.
     *
     * @param toolName  工具名称
     * @param sessionId 会话 ID
     */
    public void userApprove(String toolName, String sessionId) {
        markApprovedInSession(toolName, sessionId);
        log.info("用户已批准: {} (会话: {})", toolName, sessionId);
    }

    /**
     * 清除会话的所有审批记录.
     */
    public void clearSession(String sessionId) {
        sessionApprovals.remove(sessionId);
    }

    // ==================== 私有方法 ====================

    private boolean isAlreadyApprovedInSession(String toolName, String sessionId) {
        Set<String> approved = sessionApprovals.get(sessionId);
        return approved != null && (approved.contains(toolName) || approved.contains("*"));
    }

    private void markApprovedInSession(String toolName, String sessionId) {
        sessionApprovals.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                .add(toolName);
    }
}
