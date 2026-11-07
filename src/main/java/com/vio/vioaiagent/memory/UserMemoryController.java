package com.vio.vioaiagent.memory;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.common.Result;
import com.vio.vioaiagent.config.SecurityProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

/** 用户记忆授权/查询/删除/导出 API；不允许通过请求体指定其他 userId。 */
@RestController
@RequestMapping("/memory")
@ConditionalOnBean(UserMemoryService.class)
public class UserMemoryController {
    private final UserMemoryService service;
    private final SecurityProperties security;

    public UserMemoryController(UserMemoryService service, SecurityProperties security) {
        this.service = service;
        this.security = security == null ? SecurityProperties.defaults() : security;
    }

    @PostMapping
    public Result<UserMemoryService.MemoryExport> remember(@RequestBody MemoryBody body,
                                                            @RequestHeader(value = "X-User-Id", required = false) String ignored) {
        UserMemoryEntry entry = service.remember(user(), body == null ? null : body.conversationId,
                body == null ? null : body.type, body == null ? null : body.content,
                body == null ? 0.5 : body.confidence, body == null ? null : body.consentId,
                body == null || body.ttlSeconds <= 0 ? null : Duration.ofSeconds(Math.min(body.ttlSeconds, 315_360_000L)));
        return Result.success(new UserMemoryService.MemoryExport(entry.memoryId(), entry.type(), entry.contentDigest(),
                entry.keyVersion(), entry.consentId(), entry.effectiveFrom(), entry.expiresAt()));
    }

    @GetMapping
    public Result<List<UserMemoryService.MemoryView>> list(@RequestParam(defaultValue = "") String q,
                                                            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(service.recall(user(), q, limit));
    }

    @GetMapping("/export")
    public Result<List<UserMemoryService.MemoryExport>> export(@RequestParam(defaultValue = "500") int limit) {
        return Result.success(service.export(user(), limit));
    }

    @DeleteMapping("/{memoryId}")
    public Result<Void> delete(@PathVariable String memoryId) {
        if (!service.delete(user(), memoryId)) throw new IllegalArgumentException("记忆不存在");
        return Result.success();
    }

    private String user() {
        String principal = RequestContext.getPrincipalId();
        if (principal != null && !principal.isBlank()) return principal;
        Long id = RequestContext.getUserId();
        if (id != null) return String.valueOf(id);
        if (security.isEnabled()) throw new IllegalStateException("缺少认证身份");
        return "anonymous";
    }

    public static class MemoryBody {
        public String conversationId;
        public String type;
        public String content;
        public double confidence = 0.5;
        public String consentId;
        public long ttlSeconds;
    }
}
