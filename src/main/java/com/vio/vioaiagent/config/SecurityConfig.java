package com.vio.vioaiagent.config;

import com.vio.vioaiagent.security.ApiKeyAuthInterceptor;
import com.vio.vioaiagent.security.CommandGuard;
import com.vio.vioaiagent.security.PathGuard;
import com.vio.vioaiagent.security.TokenStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 安全配置 — 注册安全拦截器和围栏 Bean.
 *
 * <p>通过 {@code vio.security.enabled} 控制安全模块开关.
 * 启用后, ApiKeyAuthInterceptor 拦截 /api/ai/** 请求进行认证.
 *
 * <p>设计决策：不使用 Spring Security, 而是基于 Spring MVC 原生的
 * HandlerInterceptor + WebMvcConfigurer. 原因：
 * <ul>
 *   <li>Spring Security 在 3.5.x 上的自动配置与 Spring AI 存在冲突风险</li>
 *   <li>手写拦截器更轻量, 完全可控, 面试时能讲清楚每一行代码</li>
 *   <li>不引入额外的 Maven 依赖</li>
 * </ul>
 *
 * @author vio
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig implements WebMvcConfigurer {

    private final SecurityProperties securityProperties;

    public SecurityConfig(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    /**
     * 注册 API Key 认证拦截器.
     *
     * <p>拦截路径: /api/ai/**
     * <p>排除路径: /api/health, /api/swagger-ui/**, /api/v3/api-docs/**
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyAuthInterceptor())
                .addPathPatterns("/api/ai/**")
                .excludePathPatterns(
                        "/api/health",
                        "/api/swagger-ui/**",
                        "/api/v3/api-docs/**"
                )
                .order(1);
    }

    /**
     * API Key 认证拦截器 Bean.
     */
    @Bean
    public ApiKeyAuthInterceptor apiKeyAuthInterceptor() {
        return new ApiKeyAuthInterceptor(tokenStore(), securityProperties);
    }

    /**
     * 令牌存储 Bean.
     */
    @Bean
    public TokenStore tokenStore() {
        return new TokenStore(securityProperties);
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
