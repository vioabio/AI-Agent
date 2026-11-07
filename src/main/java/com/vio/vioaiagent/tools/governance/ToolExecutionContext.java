package com.vio.vioaiagent.tools.governance;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.security.PrincipalPolicy;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** 工具执行所需的身份、Run 和幂等上下文。 */
public record ToolExecutionContext(
        String userId,
        String runId,
        Set<String> roles,
        String traceId,
        String idempotencyKey,
        Consumer<ToolGovernanceEvent> eventSink,
        com.vio.vioaiagent.security.TokenBinding tokenBinding,
        PrincipalPolicy principalPolicy,
        java.util.function.BiConsumer<String, String> inputConstraint) {

    public ToolExecutionContext(String userId, String runId, Set<String> roles,
                                String traceId, String idempotencyKey,
                                Consumer<ToolGovernanceEvent> eventSink,
                                com.vio.vioaiagent.security.TokenBinding tokenBinding,
                                PrincipalPolicy principalPolicy) {
        this(userId, runId, roles, traceId, idempotencyKey, eventSink, tokenBinding, principalPolicy, null);
    }

    /** 保持既有 6 参数调用方兼容；认证绑定默认为空。 */
    public ToolExecutionContext(String userId, String runId, Set<String> roles,
                                String traceId, String idempotencyKey,
                                Consumer<ToolGovernanceEvent> eventSink) {
        this(userId, runId, roles, traceId, idempotencyKey, eventSink, null, null);
    }

    /** 兼容带 TokenBinding 的旧构造器。 */
    public ToolExecutionContext(String userId, String runId, Set<String> roles,
                                String traceId, String idempotencyKey,
                                Consumer<ToolGovernanceEvent> eventSink,
                                com.vio.vioaiagent.security.TokenBinding tokenBinding) {
        this(userId, runId, roles, traceId, idempotencyKey, eventSink, tokenBinding, null);
    }

    public ToolExecutionContext {
        userId = userId == null || userId.isBlank() ? "anonymous" : userId;
        runId = runId == null || runId.isBlank() ? UUID.randomUUID().toString() : runId;
        roles = roles == null || roles.isEmpty()
                ? (principalPolicy == null ? Set.of("trainer") : principalPolicy.roles())
                : Set.copyOf(roles);
        traceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        eventSink = eventSink == null ? event -> { } : eventSink;
    }

    public static ToolExecutionContext fromRequest() {
        String principal = RequestContext.getPrincipalId();
        Long userId = RequestContext.getUserId();
        PrincipalPolicy policy = RequestContext.getPrincipalPolicy();
        String requestRunId = RequestContext.getRunId();
        if (requestRunId == null || requestRunId.isBlank()) {
            // Legacy request paths have no persisted AgentRun. Allocate one
            // request-scoped identifier and cache it in RequestContext instead
            // of reusing traceId (different identity/retention semantics).
            requestRunId = "request-" + UUID.randomUUID();
            RequestContext.setRunId(requestRunId);
        }
        String user = principal == null || principal.isBlank()
                ? (userId == null ? RequestContext.getUsername() : String.valueOf(userId))
                : principal;
        return new ToolExecutionContext(user, requestRunId,
                policy == null ? RequestContext.getRoles() : policy.roles(),
                RequestContext.getTraceId(), null, null,
                policy == null ? RequestContext.getTokenBinding() : policy.tokenBinding(), policy);
    }

    public static ToolExecutionContext system() {
        return new ToolExecutionContext("system", "system-run", Set.of("operator", "admin"),
                "system-trace", null, null, null);
    }

    public ToolExecutionContext withIdempotencyKey(String key) {
        return new ToolExecutionContext(userId, runId, roles, traceId, key, eventSink, tokenBinding, principalPolicy, inputConstraint);
    }

    public ToolExecutionContext withEventSink(Consumer<ToolGovernanceEvent> sink) {
        return new ToolExecutionContext(userId, runId, roles, traceId, idempotencyKey, sink, tokenBinding, principalPolicy, inputConstraint);
    }

    public ToolExecutionContext withTokenBinding(com.vio.vioaiagent.security.TokenBinding binding) {
        return new ToolExecutionContext(userId, runId, roles, traceId, idempotencyKey, eventSink, binding, principalPolicy, inputConstraint);
    }

    public ToolExecutionContext withPrincipalPolicy(PrincipalPolicy policy) {
        return new ToolExecutionContext(userId, runId, roles, traceId, idempotencyKey, eventSink,
                policy == null ? tokenBinding : policy.tokenBinding(), policy, inputConstraint);
    }

    /** Additional trusted run-local constraint; never supplied in model tool arguments. */
    public ToolExecutionContext withInputConstraint(java.util.function.BiConsumer<String, String> constraint) {
        var combined = inputConstraint == null ? constraint : inputConstraint.andThen(constraint);
        return new ToolExecutionContext(userId, runId, roles, traceId, idempotencyKey, eventSink,
                tokenBinding, principalPolicy, combined);
    }

    public String tenantId() {
        return principalPolicy == null ? RequestContext.getTenantId() : principalPolicy.tenantId();
    }
}
