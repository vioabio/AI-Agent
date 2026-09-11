package com.vio.vioaiagent.config;

import com.vio.vioaiagent.security.ApiKeyAuthInterceptor;
import com.vio.vioaiagent.security.ClientIpResolver;
import com.vio.vioaiagent.security.CommandGuard;
import com.vio.vioaiagent.security.PathGuard;
import com.vio.vioaiagent.security.TokenStore;
import com.vio.vioaiagent.security.GlobalQuotaStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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

    private final SecurityProperties securityProperties;
    private final boolean legacyAiEnabled;
    private ObjectProvider<GlobalQuotaStore> quotaProvider;

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
                // /ai/** 是兼容旧接口；/chat/**、/hitl/**、/knowledge/**、/pokemon/**、
                // /answers/** 和 /admin/** 是 v1.0/v1.1 业务/运营主链；/metrics/** 是诊断快照，
                // 不能在生产安全开关开启时匿名暴露。
                // context-path=/api 由容器处理，MVC 这里不能重复写 /api。
                .addPathPatterns("/ai/**", "/chat/**", "/hitl/**", "/knowledge/**",
                        "/answers/**", "/admin/**", "/metrics/**", "/pokemon/**", "/memory/**",
                        "/ready/details")
                .excludePathPatterns(
                        "/health",
                        "/swagger-ui/**",
                        "/v3/api-docs/**"
                )
                .order(1);
    }

    /**
     * API Key 认证拦截器 Bean.
     */
    @Bean
    public ApiKeyAuthInterceptor apiKeyAuthInterceptor() {
        return new ApiKeyAuthInterceptor(tokenStore(), securityProperties, clientIpResolver());
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
