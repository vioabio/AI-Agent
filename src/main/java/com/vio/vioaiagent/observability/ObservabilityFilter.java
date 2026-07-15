package com.vio.vioaiagent.observability;

import com.vio.vioaiagent.common.RequestContext;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 可观测性入口 Filter — 每个 HTTP 请求的第一个拦截点。
 *
 * <p>职责:
 * <ol>
 *   <li>生成或继承 traceId → 注入 MDC + RequestContext</li>
 *   <li>响应头添加 X-Trace-Id（前端可获取用于问题排查）</li>
 *   <li>记录请求耗时</li>
 * </ol>
 *
 * @author vio
 */
@Slf4j
@Component
@Order(Integer.MIN_VALUE)
public class ObservabilityFilter implements Filter {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;
        long start = System.currentTimeMillis();

        // 生成或继承 traceId
        String traceId = httpReq.getHeader(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }

        // 注入上下文
        RequestContext.setTraceId(traceId);
        RequestContext.setRequestUri(httpReq.getRequestURI());
        RequestContext.setClientIp(getClientIp(httpReq));
        org.slf4j.MDC.put("traceId", traceId);
        org.slf4j.MDC.put("uri", httpReq.getRequestURI());
        org.slf4j.MDC.put("method", httpReq.getMethod());

        // 响应头
        httpResp.setHeader(HEADER_TRACE_ID, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            log.info("HTTP {} {} → {} ({}ms)",
                    httpReq.getMethod(), httpReq.getRequestURI(),
                    httpResp.getStatus(), duration);
            org.slf4j.MDC.clear();
            RequestContext.clear();
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        return ip;
    }
}
