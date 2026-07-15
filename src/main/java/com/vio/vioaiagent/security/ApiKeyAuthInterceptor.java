package com.vio.vioaiagent.security;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.common.ResultCode;
import com.vio.vioaiagent.config.SecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;
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

    public ApiKeyAuthInterceptor(TokenStore tokenStore, SecurityProperties properties) {
        this.tokenStore = tokenStore;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                            Object handler) throws Exception {
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
        RequestContext.setClientIp(getClientIp(request));

        // 提取 API Key
        String apiKey = extractApiKey(request);
        if (apiKey == null || apiKey.isBlank()) {
            writeUnauthorized(response, "缺少 API Key, 请在 Header 中提供 X-API-Key");
            return false;
        }

        // 验证 API Key
        TokenBinding binding = tokenStore.validate(apiKey);
        if (binding == null) {
            log.warn("API Key 验证失败: {} (IP: {})", maskKey(apiKey), getClientIp(request));
            writeUnauthorized(response, "API Key 无效或已过期");
            return false;
        }

        // 注入用户身份到 RequestContext
        RequestContext.setUserId(Long.valueOf(binding.tokenId().hashCode() & 0x7FFFFFFF));
        RequestContext.setUsername("api-key:" + maskKey(apiKey));

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
        if (auth != null && auth.toLowerCase().startsWith("bearer ")) {
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

    /**
     * 获取客户端真实 IP.
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip;
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
}
