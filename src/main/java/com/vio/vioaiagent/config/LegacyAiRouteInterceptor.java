package com.vio.vioaiagent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 旧 {@code /ai/**} 兼容链的生产开关。
 *
 * <p>现代 v1.0 Run 入口位于 {@code /chat/**}，旧 GameApp/Manus 端点如果继续
 * 暴露会绕过持久 Run 事件和统一取消语义。默认本地保留兼容，生产通过配置关闭，
 * 并返回 410 促使客户端迁移。</p>
 */
public final class LegacyAiRouteInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        response.setStatus(HttpServletResponse.SC_GONE);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"legacy_ai_route_disabled\",\"message\":\"请使用 /chat/runs\"}");
        return false;
    }
}
