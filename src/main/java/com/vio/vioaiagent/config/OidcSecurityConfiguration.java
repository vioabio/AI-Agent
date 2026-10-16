package com.vio.vioaiagent.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import com.vio.vioaiagent.security.OidcSessionLifetime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OIDC 配置启动门禁。
 *
 * <p>这是 v1.1 的第一步 OIDC 落地：先保证生产不会在缺 issuer/JWKS/audience、
 * 不安全回调或不安全 Session cookie 时启动。真正的 Authorization Code 回调、
 * token exchange 和 JWT/JWKS 验签仍需在 staging 接入后启用；本类不做网络发现，
 * 因而不会在本地测试阶段偷偷访问身份提供商。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OidcProperties.class, OidcPrincipalPolicyProperties.class})
public class OidcSecurityConfiguration {
    private final OidcProperties properties;
    private final Environment environment;

    public OidcSecurityConfiguration(OidcProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        String profile = environment == null ? "local" : String.join(",", environment.getActiveProfiles());
        if (profile.isBlank()) profile = environment == null ? "local" : environment.getProperty("spring.profiles.active", "local");
        List<String> errors = properties.validationErrors(profile);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("OIDC 配置未通过 fail-closed 校验: " + String.join("; ", errors));
        }
    }

    /**
     * OIDC 过滤链。
     *
     * <p>启用时同时装配两条身份路径，二者职责不同：
     * <ul>
     *   <li><b>浏览器会话</b>（R01）：Authorization Code + PKCE，登录后身份存放在
     *       HttpOnly 会话 cookie 中；这是多人使用的正路，避免共享 API Key 变成一个
     *       主体。</li>
     *   <li><b>Bearer JWT</b>：保留给服务账号与 API 客户端，仍走 Resource Server 验签。</li>
     * </ul>
     * 关闭 OIDC 时整条链 permitAll，让既有 MVC API-Key 拦截器继续作为默认路径，
     * 本地/测试行为不变。
     *
     * <p>两个容易踩错的地方：
     * <ol>
     *   <li>会话策略必须是 {@code IF_REQUIRED}。原先的 {@code STATELESS} 会让
     *       oauth2Login 的授权请求/回调无法通过会话关联，登录在回调阶段必然失败；</li>
     *   <li>未认证时的默认行为是 302 跳转到 IdP。这对浏览器正确，但会让前端
     *       fetch/SSE 拿到一个跨域重定向而不是 401。因此按请求类型分流：
     *       API 客户端得到 401 JSON，浏览器导航得到跳转。</li>
     * </ol>
     */
    @Bean
    public SecurityFilterChain oidcSecurityFilterChain(
            HttpSecurity http,
            ObjectProvider<CorsProperties> corsProvider,
            ObjectProvider<ClientRegistrationRepository> clientRegistrationProvider,
            ObjectProvider<OidcClientSecretResolver> secretResolverProvider,
            OidcSessionLifetime sessionLifetime)
            throws Exception {
        if (!properties.enabled()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .csrf(AbstractHttpConfigurer::disable)
                    .logout(AbstractHttpConfigurer::disable)
                    .httpBasic(AbstractHttpConfigurer::disable)
                    .formLogin(AbstractHttpConfigurer::disable);
            return http.build();
        }

        CorsProperties cors = corsProvider.getIfAvailable();
        Set<String> allowedOrigins = cors == null ? Set.of() : cors.allowedOrigins().stream()
                .map(String::trim).collect(Collectors.toUnmodifiableSet());
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // Keep the cookie/header names aligned with the existing browser adapter.
        csrfRepository.setCookieName("CSRF-TOKEN");
        csrfRepository.setHeaderName("X-CSRF-TOKEN");
        // The SPA lives at /, while the backend has context-path=/api. A cookie
        // restricted to /api is invisible to document.cookie on the SPA page.
        csrfRepository.setCookiePath("/");
        csrfRepository.setCookieCustomizer(cookie -> cookie
                .secure(properties.cookieSecure()).sameSite(properties.cookieSameSite()));

        http.authorizeHttpRequests(auth -> auth
                        // Platform probes must not require an end-user JWT.
                        // `/ready/details` remains protected by the MVC
                        // interceptor (API-key/probe credential) after this
                        // framework-level permit rule.
                        .requestMatchers("/health", "/ready", "/ready/details",
                                "/auth/session", "/actuator/health/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 登录入口与 IdP 回调必须匿名可达：
                        // 回调本身是对 IdP 的响应，请求里不可能带本应用的会话。
                        .requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**",
                                "/logout", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(oidcJwtAuthenticationConverter())))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                // 必须在 CsrfFilter 之前：先做 Origin 边界，再校验 CSRF token。
                .addFilterBefore(new OidcOriginFilter(allowedOrigins), CsrfFilter.class)
                .addFilterAfter(new OidcAbsoluteSessionFilter(sessionLifetime, properties),
                        BearerTokenAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(apiAwareAuthenticationEntryPoint())
                        .accessDeniedHandler(apiAwareAccessDeniedHandler()))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies(properties.cookieName(), "CSRF-TOKEN", "JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value())))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);

        ClientRegistrationRepository registrations = clientRegistrationProvider.getIfAvailable();
        if (registrations == null) {
            throw new IllegalStateException(
                    "OIDC 已启用但缺少 ClientRegistrationRepository；"
                            + "浏览器登录无法工作。请检查 clientId/authorizationUri/tokenUri/"
                            + "redirectUris 与 client secret 注入是否完整。");
        }
        http.oauth2Login(login -> login
                .clientRegistrationRepository(registrations)
                .userInfoEndpoint(endpoint -> endpoint
                        .userAuthoritiesMapper(oidcBrowserAuthoritiesMapper()))
                .authorizationEndpoint(endpoint -> endpoint
                        .authorizationRequestRepository(
                                new HttpSessionOAuth2AuthorizationRequestRepository()))
                .successHandler(new OidcLoginSuccessHandler(sessionLifetime))
                .loginPage("/oauth2/authorization/" + OidcClientRegistrationFactory.REGISTRATION_ID));
        return http.build();
    }

    /** Roles are derived only after Spring has validated the OIDC ID token/UserInfo. */
    @Bean
    @ConditionalOnProperty(prefix = "vio.security.oidc", name = "enabled", havingValue = "true")
    public OidcRoleAuthoritiesMapper oidcBrowserAuthoritiesMapper() {
        return new OidcRoleAuthoritiesMapper(properties.roleClaim());
    }

    @Bean
    @ConditionalOnProperty(prefix = "vio.security.oidc", name = "enabled", havingValue = "true")
    public OidcSessionWebServerCustomizer oidcSessionWebServerCustomizer(ServerProperties serverProperties) {
        return new OidcSessionWebServerCustomizer(properties, serverProperties);
    }

    /**
     * 未认证请求的分流处理。
     *
     * <p>判定依据是请求是否期望 JSON/SSE 而不是 HTML：前端以 fetch/EventSource 访问
     * {@code /api/**}，拿到 302 跳转会被当成 CORS 失败或"神秘空响应"；
     * 而浏览器直接导航（Accept 含 text/html）应当被送到 IdP。
     */
    private AuthenticationEntryPoint apiAwareAuthenticationEntryPoint() {
        LoginUrlAuthenticationEntryPoint browserRedirect =
                new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/"
                        + OidcClientRegistrationFactory.REGISTRATION_ID);
        return (request, response, authException) -> {
            if (expectsApiResponse(request)) {
                writeProblem(response, HttpStatus.UNAUTHORIZED,
                        "UNAUTHENTICATED", "需要通过 OIDC 登录或提供 Bearer token");
                return;
            }
            browserRedirect.commence(request, response, authException);
        };
    }

    /** 已认证但权限不足：API 客户端得到 403 JSON，浏览器仍返回 403（不暴露跳转）。 */
    private AccessDeniedHandler apiAwareAccessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                writeProblem(response, HttpStatus.FORBIDDEN,
                        "FORBIDDEN", "当前主体无权访问该资源");
    }

    /**
     * 判断调用方期望结构化响应。
     *
     * <p>只依据明确的信号（SSE、fetch 的 Accept、XHR 头），不靠 URL 猜测：
     * 浏览器点击链接导航到 {@code /api/...} 也应得到跳转而不是 JSON。
     */
    private static boolean expectsApiResponse(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) return true;
        if (request.getHeader("X-Requested-With") != null) return true;
        if (accept == null) return true; // curl/脚本默认无 Accept，按 API 处理
        return !accept.contains(MediaType.TEXT_HTML_VALUE);
    }

    private static void writeProblem(HttpServletResponse response, HttpStatus status,
                                     String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status.value()
                + ",\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    /**
     * OIDC 客户端注册（R01）。仅在启用时装配，避免关闭 OIDC 的部署被迫提供
     * clientId/secret。
     *
     * <p>只使用显式 URI，不做 OIDC Discovery：启动期不应产生到 IdP 的网络请求。
     */
    @Bean
    @ConditionalOnProperty(prefix = "vio.security.oidc", name = "enabled", havingValue = "true")
    public ClientRegistrationRepository oidcClientRegistrationRepository(
            ObjectProvider<OidcClientSecretResolver> secretResolverProvider) {
        OidcClientSecretResolver resolver = secretResolverProvider.getIfAvailable();
        if (resolver == null) {
            throw new IllegalStateException("缺少 OidcClientSecretResolver，无法解析 client secret 引用");
        }
        return OidcClientRegistrationFactory.create(properties, resolver);
    }

    @Bean
    @ConditionalOnProperty(prefix = "vio.security.oidc", name = "enabled", havingValue = "true")
    public JwtDecoder oidcJwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(properties.issuerUri());
        OAuth2TokenValidator<Jwt> audience = token -> token.getAudience().contains(properties.audience())
                ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
                : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                        new org.springframework.security.oauth2.core.OAuth2Error("invalid_token", "Invalid audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
        return decoder;
    }

    @Bean
    @ConditionalOnProperty(prefix = "vio.security.oidc", name = "enabled", havingValue = "true")
    public JwtAuthenticationConverter oidcJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(properties.subjectClaim());
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(properties.roleClaim());
        authorities.setAuthorityPrefix("ROLE_");
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    public OidcProperties properties() { return properties; }
}
