package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 就绪检查 — Kubernetes Readiness Probe 端点。
 * <p>返回 ready 表示应用已完成启动，可以接收流量。
 *
 * @author vio
 */
@Tag(name = "健康检查")
@RestController
@RequestMapping("/ready")
public class ReadyController {

    @Operation(summary = "就绪检查 (Readiness)")
    @GetMapping
    public Result<String> ready() {
        return Result.success("ready");
    }
}
