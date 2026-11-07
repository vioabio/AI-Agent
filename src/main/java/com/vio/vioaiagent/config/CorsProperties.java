package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

/**
 * 浏览器跨域策略。
 *
 * <p>只接受完整的、无路径的 HTTP(S) origin。通配符会被拒绝，避免
 * {@code allowCredentials=true} 与宽泛来源组合造成凭据泄漏。</p>
 *
 * @param allowedOrigins 允许访问 API 的前端 origin 白名单
 */
@ConfigurationProperties(prefix = "vio.http.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = normalize(allowedOrigins);
    }

    /**
     * Spring 配置绑定完成后返回不可变、去重的白名单。
     */
    @Override
    public List<String> allowedOrigins() {
        return allowedOrigins;
    }

    private static List<String> normalize(List<String> origins) {
        if (origins == null) {
            return List.of();
        }
        return origins.stream()
                .map(origin -> origin == null ? "" : origin.trim())
                .filter(origin -> !origin.isEmpty())
                .peek(CorsProperties::validateOrigin)
                .distinct()
                .toList();
    }

    private static void validateOrigin(String origin) {
        if (origin.contains("*")) {
            throw new IllegalArgumentException(
                    "CORS origin 不允许使用通配符: " + origin);
        }
        URI parsed;
        try {
            parsed = URI.create(origin);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("CORS origin 格式无效: " + origin, ex);
        }
        String scheme = parsed.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || parsed.getRawAuthority() == null
                || parsed.getRawAuthority().isBlank()
                || parsed.getRawUserInfo() != null
                || parsed.getRawPath() != null && !parsed.getRawPath().isEmpty()
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "CORS origin 必须是无路径的 HTTP(S) origin: " + origin);
        }
    }
}
