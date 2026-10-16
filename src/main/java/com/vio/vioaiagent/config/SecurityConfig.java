package com.vio.vioaiagent.config;

import com.vio.vioaiagent.security.ApiKeyAuthInterceptor;
import com.vio.vioaiagent.security.ClientIpResolver;
import com.vio.vioaiagent.security.CommandGuard;
import com.vio.vioaiagent.security.PathGuard;
import com.vio.vioaiagent.security.TokenStore;
import com.vio.vioaiagent.security.GlobalQuotaStore;
import com.vio.vioaiagent.security.OidcPrincipalPolicyAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 安全配置 — 注册安全拦截器和围栏 Bean.
 *
 * <p>通过 {@code vio.security.enabled} 控制安全模块开关.
 * 启用后, ApiKeyAuthInterceptor 拦截 AI、Run、知识、反馈、宝可梦资源、管理和诊断路径进行认证.
 *
 * <p>兼容层仍基于 Spring MVC 原生的 HandlerInterceptor + WebMvcConfigurer，
 * 以保持 API-Key 旧接口和本地测试行为稳定；当 OIDC 显式开启时，
 * {@link OidcSecurityConfiguration} 另外提供 JWT Resource Server/CSRF 过滤链。
 * 两条链的职责不同，不能把 API-Key 拦截器误认为完整 OIDC Session 实现。
 * 保留 MVC 兼容层的原因：
 * <ul>
 *   <li>保留旧接口的轻量兼容路径，避免迁移期间一次性改变调用方契约</li>
 *   <li>手写拦截器更轻量, 完全可控, 面试时能讲清楚每一行代码</li>
 *   <li>OIDC 依赖和过滤链通过显式开关启用，默认不改变本地 API-Key 路径</li>
 * </ul>
 *
 * @author vio
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig implements WebMvcConfigurer {

    /**
     * 需要认证的业务路径模式。
     *
     * <p>这份列表同时是「哪些路由进入身份适配层」的唯一事实来源：
     * {@link ApiKeyAuthInterceptor} 是当前唯一填充 {@code RequestContext} 主体信息的位置，
     * 任何未命中的业务路由都会让 {@code RequestContext.getPrincipalId()} 为空，
     * 从而在 {@code ResourceController.owner()} 处抛 401（即使调用方持有合法凭据）。
     *
     * <p>因此该列表必须覆盖所有对外业务根路径。新增控制器时必须同步补充，
     * 否则 {@code SecurityRouteCoverageTest} 会枚举控制器源码中的全部映射并让构建红灯，
     * 避免再次出现“手写路径漏项”的静默缺口。
     * 运行期行为由 {@code ResourceIdentityHttpTest} 用真实 HTTP 过滤链验证。
     */
    public static final List<String> AUTHENTICATED_PATH_PATTERNS = List.of(
            "/ai/**",          // 历史兼容链
            "/chat/**",        // 现代 Run 主链
            "/hitl/**",        // 人工审批
            "/knowledge/**",   // 知识生命周期
            "/answers/**",     // 答案投影与反馈
            "/admin/**",       // 运营接口
            "/metrics/**",     // 诊断快照，安全开启时不得匿名暴露
            "/pokemon/**",     // 领域事实/计算/队伍
            "/memory/**",      // 用户记忆
            "/conversations/**", // 会话资源（N2/N4）
            "/teams/**",         // 队伍资源（N2/N4；/pokemon/teams 由 /pokemon/** 覆盖）
            "/favorites/**",     // 收藏资源（N2/N4）
            "/ready/details"     // 就绪明细需要探针凭据
    );

    /**
     * 明确公开的无凭据路径。
     *
     * <p>探针、文档与只读会话状态接口必须匿名可用；业务路径不得加入此列表。
     * 该列表需要显式且最小，公开面扩大必须经过评审。
     */
    public static final List<String> PUBLIC_PATH_PATTERNS = List.of(
            "/auth/session", // Read-only bootstrap; no credentials or business resources exposed
            "/health",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            // Spring Boot 探针端点（readiness/liveness）；不带 /details 后缀，
            // 与上面的 /ready/details 精确路径区分开。
            "/ready",
            "/actuator/**"
    );

    private final SecurityProperties securityProperties;
    private final boolean legacyAiEnabled;
    private ObjectProvider<GlobalQuotaStore> quotaProvider;
    private ObjectProvider<OidcPrincipalPolicyAdapter> oidcPolicyProvider;

    /**
     * 保留给离线单元测试和少量嵌入式调用方的兼容构造器。
     *
     * <p>Spring 运行时使用下面带 {@link Autowired} 的构造器读取生产开关；
     * 直接 new 配置类时沿用本地兼容行为（旧 /ai/** 默认开启）。</p>
     */
    public SecurityConfig(SecurityProperties securityProperties) {
        this(securityProperties, true);
    }

    @Autowired
    public SecurityConfig(SecurityProperties securityProperties,
                          @Value("${vio.legacy.ai.enabled:true}") boolean legacyAiEnabled) {
        this.securityProperties = securityProperties;
        this.legacyAiEnabled = legacyAiEnabled;
    }

    /**
     * 注册 API Key 认证拦截器.
     *
     * <p>应用 context-path 已由 Spring Boot 设置为 /api，MVC 拦截器收到的是去掉
     * context-path 后的路径，因此这里不能再次写 /api，避免出现 /api/api 的边界错误。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!legacyAiEnabled) {
            // /ai/** 是历史 GameApp/Manus 兼容链；生产关闭后不能再绕过
            // 持久化 Run 事件、归属校验和现代 cancel 语义。
            registry.addInterceptor(legacyAiRouteInterceptor())
                    .addPathPatterns("/ai/**")
                    .order(0);
        }
        registry.addInterceptor(apiKeyAuthInterceptor())
                // 业务路径统一取自 AUTHENTICATED_PATH_PATTERNS：该常量是身份接线
                // 的唯一事实来源，新增根路径必须在此登记，否则 RequestContext 不会
                // 被填充（见 SecurityRouteCoverageTest 的映射回归）。
                // context-path=/api 由容器处理，MVC 这里不能重复写 /api。
                .addPathPatterns(AUTHENTICATED_PATH_PATTERNS)
                .excludePathPatterns(PUBLIC_PATH_PATTERNS)
                .order(1);
    }

    /**
     * API Key 认证拦截器 Bean.
     */
    @Bean
    public ApiKeyAuthInterceptor apiKeyAuthInterceptor() {
        ApiKeyAuthInterceptor interceptor =
                new ApiKeyAuthInterceptor(tokenStore(), securityProperties, clientIpResolver());
        if (oidcPolicyProvider != null) {
            interceptor.setOidcPolicyAdapter(oidcPolicyProvider.getIfAvailable());
        }
        return interceptor;
    }

    /**
     * 统一 HTTP 入口和可观测性入口的客户端 IP 解析策略。
     * 空 CIDR 列表时不信任任何转发头，避免直接暴露服务时被伪造。
     */
    @Bean
    public ClientIpResolver clientIpResolver() {
        return new ClientIpResolver(securityProperties.trustedProxyCidrs());
    }

    @Bean
    public LegacyAiRouteInterceptor legacyAiRouteInterceptor() {
        return new LegacyAiRouteInterceptor();
    }

    /**
     * 令牌存储 Bean.
     */
    @Bean
    public TokenStore tokenStore() {
        return new TokenStore(securityProperties,
                quotaProvider == null ? null : quotaProvider.getIfAvailable());
    }

    @Autowired(required = false)
    public void setQuotaProvider(ObjectProvider<GlobalQuotaStore> quotaProvider) {
        this.quotaProvider = quotaProvider;
    }

    @Autowired
    public void setOidcPolicyProvider(ObjectProvider<OidcPrincipalPolicyAdapter> oidcPolicyProvider) {
        this.oidcPolicyProvider = oidcPolicyProvider;
    }

    /**
     * 路径围栏 Bean.
     */
    @Bean
    public PathGuard pathGuard() {
        return new PathGuard(securityProperties);
    }

    /**
     * 命令黑名单 Bean.
     */
    @Bean
    public CommandGuard commandGuard() {
        return new CommandGuard(securityProperties);
    }
}
