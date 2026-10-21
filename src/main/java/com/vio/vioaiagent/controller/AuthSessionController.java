package com.vio.vioaiagent.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.OidcClientRegistrationFactory;
import com.vio.vioaiagent.config.OidcProperties;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.security.InternalPrincipalKey;
import com.vio.vioaiagent.security.OidcPrincipalPolicyAdapter;
import com.vio.vioaiagent.security.OidcSessionLifetime;
import com.vio.vioaiagent.security.PrincipalPolicy;
import com.vio.vioaiagent.security.PrincipalPolicyDecision;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Anonymous-readable session bootstrap; never publishes credentials or tool bindings. */
@RestController
@RequestMapping("/auth")
public class AuthSessionController {
    private final OidcProperties oidc;
    private final SecurityProperties security;
    private final OidcPrincipalPolicyAdapter adapter;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.vio.vioaiagent.security.LocalAccountService localAccounts;

    public AuthSessionController(OidcProperties oidc, SecurityProperties security,
                                 OidcPrincipalPolicyAdapter adapter) {
        this.oidc = oidc;
        this.security = security;
        this.adapter = adapter;
    }

    @GetMapping("/session")
    @Operation(operationId = "getAuthSession", summary = "读取当前浏览器会话与认证模式")
    public ResponseEntity<SessionStatus> session(HttpServletRequest request) {
        Instant serverTime = Instant.now();
        String mode = oidc.enabled() ? "oidc" : security.isEnabled() ? "api_key" : "disabled";
        String loginUrl = oidc.enabled() ? request.getContextPath() + "/oauth2/authorization/"
                + OidcClientRegistrationFactory.REGISTRATION_ID : null;
        String logoutUrl = oidc.enabled() ? request.getContextPath() + "/logout" : null;
        Object csrfValue = request.getAttribute(CsrfToken.class.getName());
        CsrfStatus csrf = new CsrfStatus(oidc.enabled(), oidc.enabled() && csrfValue instanceof CsrfToken,
                "X-CSRF-TOKEN", "CSRF-TOKEN");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (localAccounts != null) {
            var account = authentication != null && authentication.getPrincipal() instanceof com.vio.vioaiagent.security.LocalAccountService.Account a
                    ? localAccounts.find(a.id()).orElse(null) : null;
            var session = request.getSession(false);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionStatus("local", account!=null,
                    false, account==null?null:account.id(), account==null?null:"local", account==null?List.of():List.of("trainer"),
                    account==null?null:account.id(), serverTime, account==null?null:(Instant)session.getAttribute("localAccountDeadline"),
                    "/api/auth/local/login", "/api/logout", new CsrfStatus(true, csrfValue instanceof CsrfToken,"X-CSRF-TOKEN","CSRF-TOKEN")));
        }
        boolean trusted = OidcSessionLifetime.isBrowserAuthentication(authentication)
                || authentication instanceof JwtAuthenticationToken && authentication.isAuthenticated();
        PrincipalPolicyDecision decision = oidc.enabled() && trusted
                ? adapter.resolve(authentication, OidcSessionLifetime.validatedDeadline(request)) : null;
        PrincipalPolicy policy = decision != null && decision.allowed() ? decision.policy() : null;
        Instant deadline = policy == null ? null : authentication instanceof JwtAuthenticationToken jwt
                ? jwt.getToken().getExpiresAt() : OidcSessionLifetime.validatedDeadline(request);
        SessionStatus body = new SessionStatus(mode, policy != null,
                decision != null && !decision.allowed(), policy == null ? null : policy.principalId(),
                policy == null ? null : policy.tenantId(), policy == null ? List.of() : policy.roles().stream().sorted().toList(),
                // ADR-002 步骤 1：暴露身份边界写入的归属键（不在这里重算）。
                // 若用 policy 自行派生，就会出现"控制器算一遍、拦截器算一遍"的两份实现，
                // 二者一旦漂移，前端看到的主体与真实归属判定不一致。
                ownerId(policy),
                serverTime, deadline, loginUrl, logoutUrl, csrf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * 读取身份边界已写入的归属键。
     *
     * <p>只做交叉校验，不重新派生：拦截器已按 issuer+tenant+subject 生成并放入
     * {@code RequestContext}。若发现为空而策略却允许，说明接线断了，
     * 此时返回 null（而不是退回裸 sub）让调用方看到"归属未确定"。
     */
    /**
     * 归属键。
     *
     * <p>{@code /auth/session} **不经过** {@code ApiKeyAuthInterceptor}
     * （{@code /auth/**} 不在该拦截器的路径列表里），因此 {@code RequestContext}
     * 在此为空，不能从那里读归属键——这正是本方法必须自己派生的原因。
     *
     * <p>派生只依赖已由 Spring Security 校验过的 policy（issuer 取自应用配置，
     * {@code JwtDecoder} 已保证与 token 的 {@code iss} 一致），
     * 因此与拦截器写入的那个键在算法上完全一致。
     * 二者一致性由 {@code OidcBrowserSessionHttpTest} 中走真实拦截链的用例守住，
     * 避免"会话上报的主体"与"实际决定归属的主体"漂移成两份计算结果。
     */
    private String ownerId(PrincipalPolicy policy) {
        if (policy == null || oidc == null || oidc.issuerUri().isBlank()) return null;
        String derived = InternalPrincipalKey.fromOidc(oidc.issuerUri(), policy.tenantId(),
                policy.principalId());
        // 额外确认身份边界确实写过同一个键（拦截器覆盖到的路径）；
        // 未覆盖的路径（本端点）此处为空是预期行为，不作为失败。
        String fromBoundary = RequestContext.getOwnerId();
        if (InternalPrincipalKey.isInternalKey(fromBoundary) && !fromBoundary.equals(derived)) {
            // 同一请求内两处派生不一致意味着接线错误，宁可让上层看不到归属键。
            return null;
        }
        return derived;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SessionStatus(String mode, boolean authenticated, boolean accessDenied,
                                String principalId, String tenantId, List<String> roles,
                                String ownerId,
                                Instant serverTime, Instant absoluteExpiresAt, String loginUrl,
                                String logoutUrl, CsrfStatus csrf) { }

    public record CsrfStatus(boolean enabled, boolean available, String headerName, String cookieName) { }
}
