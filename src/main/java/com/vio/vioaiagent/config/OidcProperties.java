package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * OIDC Authorization Code + PKCE 的非 Secret 配置契约。
 *
 * <p>当前 API-Key 拦截器仍是兼容路径；这组属性先把 OIDC 的 issuer、JWKS、
 * audience、回调和 Session cookie 边界固定下来。{@link OidcSecurityConfiguration}
 * 会在启用/强制时 fail-closed，避免把一组未校验的 Bearer 字符串误当成生产身份。</p>
 *
 * <p>所有 credential 字段只接受 Secret Store 引用（例如
 * {@code secret://oidc/client-secret}），绝不接受 secret value。</p>
 */
@ConfigurationProperties(prefix = "vio.security.oidc")
public class OidcProperties {
    private boolean enabled;
    private boolean required;
    private String issuerUri = "";
    private String jwksUri = "";
    private String authorizationUri = "";
    private String tokenUri = "";
    private String audience = "";
    private String clientId = "";
    private String clientSecretRef = "";
    private List<String> redirectUris = new ArrayList<>();
    private List<String> scopes = new ArrayList<>(List.of("openid", "profile"));
    private List<String> allowedAlgorithms = new ArrayList<>(List.of("RS256"));
    private boolean requirePkce = true;
    private long sessionIdleTimeoutSeconds = 1800;
    private long sessionAbsoluteTimeoutSeconds = 28_800;
    private boolean cookieSecure = true;
    private String cookieSameSite = "Lax";
    private String cookieName = "VIO_SESSION";
    private String subjectClaim = "sub";
    private String roleClaim = "roles";
    private long clockSkewSeconds = 60;

    public boolean isEnabled() { return enabled; }
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isRequired() { return required; }
    public boolean required() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public String getIssuerUri() { return trim(issuerUri); }
    public String issuerUri() { return getIssuerUri(); }
    public void setIssuerUri(String value) { issuerUri = trim(value); }

    public String getJwksUri() { return trim(jwksUri); }
    public String jwksUri() { return getJwksUri(); }
    public void setJwksUri(String value) { jwksUri = trim(value); }

    public String getAuthorizationUri() { return trim(authorizationUri); }
    public String authorizationUri() { return getAuthorizationUri(); }
    public void setAuthorizationUri(String value) { authorizationUri = trim(value); }

    public String getTokenUri() { return trim(tokenUri); }
    public String tokenUri() { return getTokenUri(); }
    public void setTokenUri(String value) { tokenUri = trim(value); }

    public String getAudience() { return trim(audience); }
    public String audience() { return getAudience(); }
    public void setAudience(String value) { audience = trim(value); }

    public String getClientId() { return trim(clientId); }
    public String clientId() { return getClientId(); }
    public void setClientId(String value) { clientId = trim(value); }

    public String getClientSecretRef() { return trim(clientSecretRef); }
    public String clientSecretRef() { return getClientSecretRef(); }
    public void setClientSecretRef(String value) { clientSecretRef = trim(value); }

    public List<String> getRedirectUris() { return immutable(redirectUris); }
    public List<String> redirectUris() { return getRedirectUris(); }
    public void setRedirectUris(List<String> values) { redirectUris = normalized(values); }

    public List<String> getScopes() { return immutable(scopes); }
    public List<String> scopes() { return getScopes(); }
    public void setScopes(List<String> values) { scopes = normalized(values); }

    public List<String> getAllowedAlgorithms() { return immutable(allowedAlgorithms); }
    public List<String> allowedAlgorithms() { return getAllowedAlgorithms(); }
    public void setAllowedAlgorithms(List<String> values) { allowedAlgorithms = normalizedAlgorithms(values); }

    public boolean isRequirePkce() { return requirePkce; }
    public boolean requirePkce() { return requirePkce; }
    public void setRequirePkce(boolean requirePkce) { this.requirePkce = requirePkce; }

    public long getSessionIdleTimeoutSeconds() { return sessionIdleTimeoutSeconds; }
    public long sessionIdleTimeoutSeconds() { return sessionIdleTimeoutSeconds; }
    public void setSessionIdleTimeoutSeconds(long value) { sessionIdleTimeoutSeconds = value; }

    public long getSessionAbsoluteTimeoutSeconds() { return sessionAbsoluteTimeoutSeconds; }
    public long sessionAbsoluteTimeoutSeconds() { return sessionAbsoluteTimeoutSeconds; }
    public void setSessionAbsoluteTimeoutSeconds(long value) { sessionAbsoluteTimeoutSeconds = value; }

    public boolean isCookieSecure() { return cookieSecure; }
    public boolean cookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }

    public String getCookieSameSite() { return trim(cookieSameSite); }
    public String cookieSameSite() { return getCookieSameSite(); }
    public void setCookieSameSite(String value) { cookieSameSite = trim(value); }

    public String getCookieName() { return trim(cookieName); }
    public String cookieName() { return getCookieName(); }
    public void setCookieName(String value) { cookieName = trim(value); }

    public String getSubjectClaim() { return trim(subjectClaim); }
    public String subjectClaim() { return getSubjectClaim(); }
    public void setSubjectClaim(String value) { subjectClaim = trim(value); }

    public String getRoleClaim() { return trim(roleClaim); }
    public String roleClaim() { return getRoleClaim(); }
    public void setRoleClaim(String value) { roleClaim = trim(value); }

    public long getClockSkewSeconds() { return clockSkewSeconds; }
    public long clockSkewSeconds() { return clockSkewSeconds; }
    public void setClockSkewSeconds(long value) { clockSkewSeconds = value; }

    /**
     * Validate the contract without performing discovery or network calls.
     * The returned messages are deliberately field-level and never contain a
     * credential value.
     */
    public List<String> validationErrors(String environment) {
        String env = trim(environment).toLowerCase(Locale.ROOT);
        // Spring may expose multiple active profiles as a comma-separated
        // value (for example "prod,region-cn").  Treating that whole string
        // as one profile would silently skip the production-only HTTPS/cookie
        // checks whenever an extra profile is active.
        boolean production = java.util.Arrays.stream(env.split("[,\\s]+"))
                .anyMatch(value -> "prod".equals(value) || "production".equals(value));
        if (!enabled) {
            return required ? List.of("OIDC 被标记为 required，但 enabled=false") : List.of();
        }
        List<String> errors = new ArrayList<>();
        requireText(errors, issuerUri, "issuerUri", 2048);
        requireText(errors, jwksUri, "jwksUri", 2048);
        requireText(errors, authorizationUri, "authorizationUri", 2048);
        requireText(errors, tokenUri, "tokenUri", 2048);
        requireText(errors, audience, "audience", 256);
        requireText(errors, clientId, "clientId", 256);
        if (!clientSecretRef.isBlank() && !isSecretReference(clientSecretRef)) {
            errors.add("clientSecretRef 必须是 Secret Store 引用，不能写入 secret value");
        }
        validateHttpsUri(errors, issuerUri, "issuerUri", production, true);
        validateHttpsUri(errors, jwksUri, "jwksUri", production, false);
        validateHttpsUri(errors, authorizationUri, "authorizationUri", production, false);
        validateHttpsUri(errors, tokenUri, "tokenUri", production, false);
        for (String redirect : redirectUris) {
            validateRedirect(errors, redirect, production);
        }
        if (redirectUris.isEmpty()) errors.add("redirectUris 至少需要一个精确回调地址");
        if (!scopes.stream().anyMatch(value -> "openid".equalsIgnoreCase(value))) {
            errors.add("scopes 必须包含 openid");
        }
        if (!requirePkce) errors.add("生产 OIDC 必须启用 PKCE");
        if (allowedAlgorithms.isEmpty() || allowedAlgorithms.stream().anyMatch(value ->
                value.isBlank() || "none".equalsIgnoreCase(value))) {
            errors.add("allowedAlgorithms 不能为空且禁止 none；当前仅支持精确配置为 [RS256]");
        } else if (!List.of("RS256").equals(allowedAlgorithms)) {
            errors.add("allowedAlgorithms 当前仅支持精确配置为 [RS256]");
        }
        if (sessionIdleTimeoutSeconds < 300 || sessionIdleTimeoutSeconds > 86_400) {
            errors.add("sessionIdleTimeoutSeconds 必须在 300..86400 之间");
        }
        if (sessionAbsoluteTimeoutSeconds < sessionIdleTimeoutSeconds
                || sessionAbsoluteTimeoutSeconds > 172_800) {
            errors.add("sessionAbsoluteTimeoutSeconds 必须不小于 idle 且不超过 172800");
        }
        if (production && !cookieSecure) errors.add("prod Session cookie 必须 Secure");
        if (!Set.of("LAX", "STRICT", "NONE").contains(getCookieSameSite().toUpperCase(Locale.ROOT))) {
            errors.add("cookieSameSite 只能是 Lax、Strict 或 None");
        } else if ("NONE".equalsIgnoreCase(getCookieSameSite()) && !cookieSecure) {
            errors.add("SameSite=None 必须同时启用 Secure");
        }
        if (!cookieName.matches("^[A-Za-z][A-Za-z0-9._-]{0,63}$")) {
            errors.add("cookieName 不是合法的 cookie 名称");
        }
        validateClaim(errors, subjectClaim, "subjectClaim");
        validateClaim(errors, roleClaim, "roleClaim");
        if (clockSkewSeconds < 0 || clockSkewSeconds > 300) errors.add("clockSkewSeconds 必须在 0..300 之间");
        return List.copyOf(errors);
    }

    /** Redacted public client settings; never include secret references. */
    public PublicClientSettings publicClientSettings() {
        return new PublicClientSettings(getIssuerUri(), getAuthorizationUri(), getClientId(),
                getRedirectUris(), getScopes(), getAllowedAlgorithms(), requirePkce);
    }

    public record PublicClientSettings(String issuerUri, String authorizationUri, String clientId,
                                       List<String> redirectUris, List<String> scopes,
                                       List<String> allowedAlgorithms, boolean requirePkce) {
        public PublicClientSettings {
            redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            allowedAlgorithms = allowedAlgorithms == null ? List.of() : List.copyOf(allowedAlgorithms);
        }
    }

    private void validateRedirect(List<String> errors, String value, boolean production) {
        if (value == null || value.isBlank() || value.length() > 2048 || value.contains("*")) {
            errors.add("redirectUri 不能为空、不能含通配符且长度受限");
            return;
        }
        try {
            URI uri = URI.create(value);
            boolean local = isLocalHost(uri.getHost());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || (!production && local && "http".equalsIgnoreCase(uri.getScheme())))) {
                errors.add("redirectUri 必须是 HTTPS（非生产仅允许 localhost HTTP）");
            }
            if (uri.getFragment() != null || uri.getUserInfo() != null || uri.getHost() == null) {
                errors.add("redirectUri 不得包含 fragment/userinfo 且必须有 host");
            }
        } catch (IllegalArgumentException ex) {
            errors.add("redirectUri 格式无效");
        }
    }

    private void validateHttpsUri(List<String> errors, String value, String field,
                                  boolean production, boolean forbidQuery) {
        if (value == null || value.isBlank()) return;
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                if (!( !production && "http".equalsIgnoreCase(uri.getScheme()) && isLocalHost(uri.getHost()))) {
                    errors.add(field + " 必须使用 HTTPS");
                }
            }
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || (forbidQuery && uri.getQuery() != null)) {
                errors.add(field + " 不得包含 userinfo/fragment，issuer 还不得包含 query");
            }
            if (production && isLocalHost(uri.getHost())) errors.add(field + " 生产禁止 localhost/loopback");
        } catch (IllegalArgumentException ex) {
            errors.add(field + " URI 格式无效");
        }
    }

    private void validateClaim(List<String> errors, String value, String field) {
        if (value == null || !value.matches("^[A-Za-z][A-Za-z0-9_-]{0,63}$")) errors.add(field + " 不是合法 claim 名称");
    }

    private void requireText(List<String> errors, String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)
                || value.chars().anyMatch(Character::isWhitespace)) errors.add(field + " 不能为空且不能含空白/控制字符");
    }

    private boolean isSecretReference(String value) {
        return value.startsWith("secret://") || value.startsWith("vault://") || value.startsWith("kms://");
    }

    private boolean isLocalHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost") || normalized.equals("127.0.0.1")
                || normalized.equals("::1") || normalized.equals("[::1]");
    }

    private static List<String> normalized(List<String> values) {
        if (values == null) return new ArrayList<>();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) if (value != null && !value.isBlank()) unique.add(value.trim());
        return new ArrayList<>(unique);
    }

    /** Keep blank algorithm entries visible so validation can fail-fast. */
    private static List<String> normalizedAlgorithms(List<String> values) {
        if (values == null) return new ArrayList<>();
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) result.add(value == null ? "" : value.trim());
        return result;
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
