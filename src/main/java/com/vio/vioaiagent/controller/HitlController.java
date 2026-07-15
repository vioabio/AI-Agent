package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.Result;
import com.vio.vioaiagent.security.ApprovalResult;
import com.vio.vioaiagent.security.HitlManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * HITL 审批控制器 — 暴露人工审批 API 给前端。
 *
 * @author vio
 */
@Tag(name = "HITL 审批", description = "高危操作人工审批接口")
@RestController
@RequestMapping("/api/hitl")
public class HitlController {

    private final HitlManager hitlManager;

    public HitlController(HitlManager hitlManager) {
        this.hitlManager = hitlManager;
    }

    @Operation(summary = "用户批准指定工具调用")
    @PostMapping("/approve")
    public Result<String> approve(
            @Parameter(description = "工具名称") @RequestParam String toolName,
            @Parameter(description = "会话 ID") @RequestParam String sessionId) {
        hitlManager.userApprove(toolName, sessionId);
        return Result.success("已批准: " + toolName);
    }

    @Operation(summary = "查询指定工具的危险等级")
    @GetMapping("/danger-level")
    public Result<String> getDangerLevel(
            @Parameter(description = "工具名称") @RequestParam String toolName) {
        return Result.success(hitlManager.getDangerLevel(toolName).name());
    }

    @Operation(summary = "审批决策")
    @PostMapping("/decide")
    public Result<ApprovalResult> decide(
            @Parameter(description = "工具名称") @RequestParam String toolName,
            @Parameter(description = "会话 ID") @RequestParam String sessionId) {
        return Result.success(hitlManager.approve(toolName, sessionId));
    }
}
