package com.vio.vioaiagent.security;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.common.ResultCode;
import com.vio.vioaiagent.config.OidcProperties;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.security.OidcPrincipalPolicyAdapter;
import com.vio.vioaiagent.security.PrincipalPolicy;
import com.vio.vioaiagent.security.PrincipalPolicyDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;

/**
 * API Key 认证拦截器 — 安全体系第一层.
 *
 * <p>拦截所有 /api/ai/** 请求, 从 HTTP Header 中提取 API Key 进行验证.
 * 验证通过后将用户信息注入 RequestContext, 验证失败返回 401 响应.
 *
 * <p>支持的 Header（按优先级）：
 * <ol>
 *   <li>{@code X-API-Key} — API Key</li>
 *   <li>{@code Authorization: Bearer <key>} — Bearer Token 格式</li>
 * </ol>
 *
 * <p>排除路径:
 * <ul>
 *   <li>{@code /api/health} — 健康检查不需要认证</li>
 *   <li>{@code /api/swagger-ui/**} — Swagger 不需要认证</li>
 * </ul>
 *
 * @author vio
 */
@Slf4j
public class ApiKeyAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_API_KEY = "X-API-Key";

    private final TokenStore tokenStore;
    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;
    /** OIDC policy adapter；为空时保留旧的 API-Key/测试兼容语义。 */
    private OidcPrincipalPolicyAdapter oidcPolicyAdapter;
    /** OIDC 配置；仅用于取 issuer 以派生内部归属键（ADR-002 步骤 1）。 */
    private OidcProperties oidcProperties;

    public ApiKeyAuthInterceptor(TokenStore tokenStore, SecurityProperties properties) {
        this(tokenStore, properties, new ClientIpResolver(
                properties == null ? java.util.List.of() : properties.trustedProxyCidrs()));
    }

    /** 允许 Spring 和单测复用同一可信代理解析器，同时保留旧构造器。 */
    public ApiKeyAuthInterceptor(TokenStore tokenStore, SecurityProperties properties,
                                 ClientIpResolver clientIpResolver) {
        this.tokenStore = tokenStore;
        this.properties = properties;
        this.clientIpResolver = clientIpResolver == null
                ? ClientIpResolver.denyForwardedHeaders()
                : clientIpResolver;
        this.objectMapper = new ObjectMapper();
    }

    /** Spring 可选注入；旧程序化构造器不需要改变。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOidcPolicyAdapter(OidcPrincipalPolicyAdapter adapter) {
        this.oidcPolicyAdapter = adapter;
    }

    /** Spring 可选注入 OIDC 配置，用于内部归属键的 issuer 维度。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOidcProperties(OidcProperties oidcProperties) {
        this.oidcProperties = oidcProperties;
    }

    /**
     * 已校验配置里的 issuer。
     *
     * <p>这里读的是 **application 配置的 issuerUri**，而不是 token 的 {@code iss} claim：
     * {@code JwtDecoder} 已经用 {@code createDefaultWithIssuer(issuerUri)} 校验过二者一致，
     * 因此两者等价；用配置值可以避免为了派生归属键再去解析一遍 token。
     * 配置缺失时返回空串，交由 {@link InternalPrincipalKey} fail-closed 拒绝。
     */
    private String oidcIssuer() {
        return oidcProperties == null ? "" : oidcProperties.issuerUri();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                            Object handler) throws Exception {
        // Local session identity is authoritative even when the legacy API-Key module is disabled.
        Authentication local = SecurityContextHolder.getContext().getAuthentication();
        if (local != null && local.isAuthenticated() && local.getPrincipal() instanceof LocalAccountService.Account account) {
            java.time.Instant deadline = (java.time.Instant) request.getSession().getAttribute("localAccountDeadline");
            Set<String> tools = com.vio.vioaiagent.tools.governance.ToolCapabilityCatalog.all().values().stream()
                    .filter(card -> card.allowedRoles().contains("trainer")).map(card -> card.name()).collect(java.util.stream.Collectors.toSet());
            TokenBinding binding = TokenBinding.oidc(account.id(), "local", Set.of("trainer"), tools,
                    "local-account-v1-" + account.version(), 60, deadline);
            PrincipalPolicy policy = new PrincipalPolicy(account.id(), "local", Set.of("trainer"), tools,
                    binding.policyVersion(), deadline, binding);
            RequestContext.setPrincipalId(account.id());
            RequestContext.setOwnerId(account.id());
            RequestContext.setUserId(Long.valueOf(stablePrincipalId(account.id())));
            RequestContext.setUsername(account.username());
            RequestContext.setTenantId("local"); RequestContext.setRoles(Set.of("trainer"));
            RequestContext.setPrincipalPolicy(policy); RequestContext.setTokenBinding(binding);
            tokenStore.register(binding);
            return true;
        }
        // 安全模块未启用 → 放行所有请求
        if (!properties.isEnabled()) {
            return true;
        }

        // 注入 TraceId（无论是否认证都注入, 便于追踪）
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        RequestContext.setTraceId(traceId);
        RequestContext.setRequestUri(request.getRequestURI());
        String clientIp = clientIpResolver.resolve(request);
        RequestContext.setClientIp(clientIp);

        // When the OIDC Resource Server has already authenticated a bearer JWT,
        // preserve that principal and do not require a second API-Key header.
        // AnonymousAuthenticationToken is intentionally excluded so the local
        // API-Key compatibility path remains unchanged.
        Authentication oidc = SecurityContextHolder.getContext().getAuthentication();
        if (oidc != null && oidc.isAuthenticated() && !(oidc instanceof AnonymousAuthenticationToken)) {
            PrincipalPolicyDecision policyDecision = oidcPolicyAdapter == null
                    ? PrincipalPolicyDecision.notApplicable()
                    : oidcPolicyAdapter.resolve(oidc, OidcSessionLifetime.validatedDeadline(request));
            if (policyDecision.applicable()) {
                if (!policyDecision.allowed() || policyDecision.policy() == null) {
                    RequestContext.clear();
                    // 身份已经通过 JWT 验证，但租户/角色/工具策略不完整时
                    // 返回 403；拒绝原因只使用稳定的策略类别，不泄露 claims。
                    writeForbidden(response, "OIDC 主体未通过租户/角色工具策略");
                    return false;
                }
                PrincipalPolicy policy = policyDecision.policy();
                RequestContext.setPrincipalId(policy.principalId());
                RequestContext.setUserId(Long.valueOf(stablePrincipalId("oidc:" + policy.principalId())));
                RequestContext.setUsername(policy.principalId());
                RequestContext.setTenantId(policy.tenantId());
                RequestContext.setRoles(policy.roles());
                RequestContext.setPrincipalPolicy(policy);
                RequestContext.setTokenBinding(policy.tokenBinding());
                // ADR-002 步骤 1：归属键在身份边界算一次，承载 issuer+tenant+subject。
                // 与上面的 principalId（裸 sub）并存：现有数据行仍是旧格式，
                // 直接改 owner 语义会让既有资源"消失"，迁移见 ADR-002 §3。
                RequestContext.setOwnerId(InternalPrincipalKey.fromOidc(
                        oidcIssuer(), policy.tenantId(), policy.principalId()));
                // 将短期、无 Secret 的 binding 放入现有 TokenStore，使网关仍
                // 经过同一 scope/配额/撤销校验，不通过旁路放行 OIDC 工具。
                if (policy.tokenBinding() != null) tokenStore.register(policy.tokenBinding());
                return true;
            }
            String principal = oidc.getName();
            RequestContext.setPrincipalId(principal);
            RequestContext.setUserId(Long.valueOf(stablePrincipalId("oidc:" + principal)));
            RequestContext.setUsername(principal);
            RequestContext.setTenantId(null);
            RequestContext.setRoles(Set.of());
            RequestContext.setPrincipalPolicy(null);
            // 该分支没有策略快照，因此拿不到 tenant。按 ADR-002 的 fail-closed 原则
            // 不派生归属键（宁可为空让下游拒绝），而不是退回裸 sub 充当 owner。
            RequestContext.setOwnerId(null);
            // 线程可能来自上一个 API-Key 请求；OIDC 不能继承旧的短期绑定，
            // 否则网关会把 stale TokenBinding 误当成当前主体的授权策略。
            RequestContext.setTokenBinding(null);
            return true;
        }

        // 浏览器 CORS 预检不携带业务凭据。放行预检，让 CorsFilter 校验 origin，
        // 真实 GET/POST/DELETE 请求仍必须经过下面的 API Key 认证。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpStatus.NO_CONTENT.value());
            return true;
        }

        // 提取 API Key
        String apiKey = extractApiKey(request);
        if (apiKey == null || apiKey.isBlank()) {
            // preHandle 返回 false 时 Spring 不一定会回调当前拦截器的
            // afterCompletion；显式清理，避免认证失败请求在线程池复用时
            // 把 trace/principal 泄漏到下一次请求。
            RequestContext.clear();
            writeUnauthorized(response, "缺少 API Key, 请在 Header 中提供 X-API-Key");
            return false;
        }

        // 验证 API Key
        TokenBinding binding = tokenStore.validate(apiKey);
        if (binding == null) {
            log.warn("API Key 验证失败: {} (IP: {})", maskKey(apiKey), clientIp);
            RequestContext.clear();
            writeUnauthorized(response, "API Key 无效或已过期");
            return false;
        }

        // 注入用户身份到 RequestContext。不能使用 String.hashCode()：碰撞容易且
        // 不适合作为跨请求/跨实例的主体键。使用 SHA-256 的前 64 位生成稳定数字
        // 兼容字段，同时保留 principalId 供角色映射和审计使用；API Key 本身不落上下文。
        String principalId = stablePrincipalId(binding.tokenId());
        RequestContext.setPrincipalId(principalId);
        RequestContext.setUserId(Long.valueOf(principalId));
        RequestContext.setUsername("api-key:" + maskKey(apiKey));
        RequestContext.setTenantId(null);
        RequestContext.setRoles(Set.of());
        RequestContext.setPrincipalPolicy(null);
        // ADR-002 步骤 1：API Key 是本地唯一主体，没有 issuer/tenant 维度，
        // 因此归属键就等于已有的稳定主体键，**保持历史取值不变**，
        // 避免改变既有 API Key 部署的数据归属语义。
        RequestContext.setOwnerId(InternalPrincipalKey.legacyOwnerFor(principalId));
        // 将已验证的绑定传给同一请求内的工具治理层；绑定对象只存在于
        // ThreadLocal，不会把原始 API Key 写入 Run/事件/审计持久化结构。
        RequestContext.setTokenBinding(binding);

        log.debug("API Key 认证通过: {} → 协议: {}", maskKey(apiKey), binding.protocolId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 请求结束后清理 RequestContext, 防止 ThreadLocal 内存泄漏
        RequestContext.clear();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从 HTTP Header 提取 API Key.
     */
    private String extractApiKey(HttpServletRequest request) {
        // 方式 1: X-API-Key header
        String apiKey = request.getHeader(HEADER_API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }

        // 方式 2: Authorization: Bearer <key>
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            return auth.substring(7).trim();
        }

        return null;
    }

    /**
     * 写入 401 未授权响应（JSON 格式, 符合项目 Result 规范）.
     */
    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = Map.of(
                "code", ResultCode.UNAUTHORIZED.getCode(),
                "message", message,
                "timestamp", System.currentTimeMillis()
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = Map.of(
                "code", ResultCode.FORBIDDEN.getCode(),
                "message", message,
                "timestamp", System.currentTimeMillis()
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /**
     * 脱敏显示 API Key（只显示前 6 位）.
     */
    private String maskKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 6) + "***";
    }

    /**
     * 为 API Key 派生不含秘密内容的稳定主体 ID。
     *
     * <p>前 63 位映射到正数 Long，以兼容当前数据库和旧 Controller 的 userId
     * 字段；SHA-256 将不同令牌的碰撞概率降到可接受范围。</p>
     */
    private String stablePrincipalId(String tokenId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(tokenId.getBytes(StandardCharsets.UTF_8));
            long value = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong() & Long.MAX_VALUE;
            // 0 仅作为极低概率的摘要结果保留为合法、可配置的主体值。
            return Long.toString(value == 0 ? 1 : value);
        } catch (NoSuchAlgorithmException ex) {
            // JRE 必须提供 SHA-256；若运行时破坏该保证，应拒绝启动/请求而不是降级
            // 到易碰撞的 hashCode。
            throw new IllegalStateException("JRE 不支持 SHA-256，无法建立稳定主体", ex);
        }
    }
}
