package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.Result;
import com.vio.vioaiagent.observability.AgentMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 指标端点 — 暴露 Agent 运行时的各项指标。
 *
 * @author vio
 */
@Tag(name = "监控", description = "Agent 指标与健康检查")
@RestController
@RequestMapping("/api")
public class MetricsController {

    private final AgentMetrics metrics;

    public MetricsController(AgentMetrics metrics) {
        this.metrics = metrics;
    }

    @Operation(summary = "获取 Agent 运行指标")
    @GetMapping("/metrics")
    public Result<Map<String, Object>> getMetrics() {
        return Result.success(metrics.snapshot());
    }
}
