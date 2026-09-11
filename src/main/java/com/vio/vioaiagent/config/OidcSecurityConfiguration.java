package com.vio.vioaiagent.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

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
     * Resource-server filter chain.  It is deliberately fail-closed when OIDC is
     * enabled, while the disabled branch permits all requests so the existing
     * MVC API-Key interceptor remains the default local/test path.
     */
    @Bean
    public SecurityFilterChain oidcSecurityFilterChain(HttpSecurity http,
                                                       ObjectProvider<CorsProperties> corsProvider)
            throws Exception {
        if (!properties.enabled()) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .csrf(AbstractHttpConfigurer::disable)
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
        http.authorizeHttpRequests(auth -> auth
                        // Platform probes must not require an end-user JWT.
                        // `/ready/details` remains protected by the MVC
                        // interceptor (API-key/probe credential) after this
                        // framework-level permit rule.
                        .requestMatchers("/health", "/ready", "/ready/details",
                                "/actuator/health/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(oidcJwtAuthenticationConverter())))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .addFilterBefore(new OidcOriginFilter(allowedOrigins), CsrfFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
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
