package com.vio.vioaiagent.config;

import com.vio.vioaiagent.security.ApiKeyAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 为未来用户资源保留安全拦截扩展点。
 *
 * <p>反馈与运营路径已经由 {@link SecurityConfig} 统一注册；这里不能再次
 * 注册同一个拦截器，否则每次请求会执行两遍认证和 ThreadLocal 清理。</p>
 */
@Configuration
public class FeedbackWebSecurityConfig implements WebMvcConfigurer {
    private final ApiKeyAuthInterceptor interceptor;

    public FeedbackWebSecurityConfig(ApiKeyAuthInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/users/**")
                .order(1);
    }
}
