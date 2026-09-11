package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.Result;
import com.vio.vioaiagent.rag.catalog.KnowledgeCatalog;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final ApplicationAvailability availability;
    private final HealthEndpoint healthEndpoint;
    private final KnowledgeCatalog knowledgeCatalog;

    public ReadyController(ApplicationAvailability availability) {
        this(availability, null, null);
    }

    /**
     * 生产构造器：除应用生命周期外，尽可能检查 Actuator 聚合健康状态（包括
     * PostgreSQL）。HealthEndpoint 在精简测试/本地 profile 中可以不存在。
     */
    public ReadyController(ApplicationAvailability availability,
                           ObjectProvider<HealthEndpoint> healthEndpointProvider) {
        this(availability, healthEndpointProvider, null);
    }

    /**
     * 受保护的详细 readiness 构造器：除基础健康状态外，回读当前 active
     * knowledge/index 指针。没有目录或指针时 details 保持 503，不能把
     * 仅 JVM 存活误报为可切流。
     */
    @Autowired
    public ReadyController(ApplicationAvailability availability,
                           ObjectProvider<HealthEndpoint> healthEndpointProvider,
                           ObjectProvider<KnowledgeCatalog> knowledgeCatalogProvider) {
        this.availability = availability;
        this.healthEndpoint = healthEndpointProvider == null
                ? null : healthEndpointProvider.getIfAvailable();
        this.knowledgeCatalog = knowledgeCatalogProvider == null
                ? null : knowledgeCatalogProvider.getIfAvailable();
    }

    @Operation(summary = "就绪检查 (Readiness)")
    @GetMapping
    public ResponseEntity<Result<String>> ready() {
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC
                || !dependenciesHealthy()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.error(HttpStatus.SERVICE_UNAVAILABLE.value(), "service is not ready"));
        }
        return ResponseEntity.ok(Result.success("ready"));
    }

    /**
     * 机器可读的受保护 readiness 详情，供 N7 probe 记录迁移/发布指针摘要。
     * 不返回数据库 URL、凭据或原始异常。
     */
    @Operation(summary = "详细就绪检查")
    @GetMapping("/details")
    public ResponseEntity<Result<Map<String, Object>>> details() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "v1.1");
        body.put("checkedAt", Instant.now().toString());
        body.put("readinessState", availability.getReadinessState().name());
        boolean lifecycleReady = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
        boolean dependencyReady = dependenciesHealthy();
        body.put("lifecycleReady", lifecycleReady);
        body.put("dependenciesHealthy", dependencyReady);
        List<String> blockingReasons = new ArrayList<>();
        if (!lifecycleReady) blockingReasons.add("LIFECYCLE_NOT_ACCEPTING_TRAFFIC");
        if (!dependencyReady) blockingReasons.add("DEPENDENCY_UNHEALTHY");
        if (knowledgeCatalog != null) {
            try {
                var active = knowledgeCatalog.activeIndex().orElse(null);
                body.put("activeIndexReleaseId", active == null ? null : active.id());
                body.put("activeKnowledgeReleaseId", active == null ? null : active.knowledgeReleaseId());
                body.put("releasePointerReady", active != null);
                if (active == null) blockingReasons.add("RELEASE_POINTER_MISSING");
            } catch (RuntimeException ex) {
                body.put("releasePointerReady", false);
                body.put("releasePointerReason", "CATALOG_UNAVAILABLE");
                blockingReasons.add("CATALOG_UNAVAILABLE");
            }
        } else {
            body.put("releasePointerReady", false);
            body.put("releasePointerReason", "CATALOG_NOT_CONFIGURED");
            blockingReasons.add("CATALOG_NOT_CONFIGURED");
        }
        boolean ready = lifecycleReady && dependencyReady && Boolean.TRUE.equals(body.get("releasePointerReady"));
        body.put("status", ready ? "READY" : "BLOCKED");
        body.put("blockingReasons", List.copyOf(blockingReasons));
        Result<Map<String, Object>> result;
        if (ready) {
            result = Result.success(body);
        } else {
            // 保留统一错误 code/HTTP 503，同时把已脱敏的机器可读详情放在 data，
            // 让 readiness probe 能区分生命周期、依赖和 release 指针阻断原因。
            result = Result.error(HttpStatus.SERVICE_UNAVAILABLE.value(), "service is not ready");
            result.setData(body);
        }
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(result);
    }

    private boolean dependenciesHealthy() {
        if (healthEndpoint == null) return true;
        try {
            HealthComponent health = healthEndpoint.health();
            return health != null && Status.UP.equals(health.getStatus());
        } catch (RuntimeException ex) {
            // Actuator contributor（例如数据库连接池）可能在启动/故障切换期间
            // 抛出异常。readiness 必须 fail-closed，且不能把原始异常/连接信息
            // 写入响应或日志证据。
            return false;
        }
    }
}
