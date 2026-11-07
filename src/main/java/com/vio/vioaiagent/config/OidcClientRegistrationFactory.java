package com.vio.vioaiagent.config;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 由 {@link OidcProperties} 构造 OIDC 客户端注册（R01）。
 *
 * <p>为什么不用 {@code spring.security.oauth2.client.*} 的自动配置：
 * <ul>
 *   <li>项目的 OIDC 契约全部集中在 {@code vio.security.oidc.*}，导出成两套配置
 *       会让「哪一份才是生效身份」变得含糊；</li>
 *   <li>自动配置的 provider 若使用 {@code issuer-uri} 会在启动时做 OIDC Discovery
 *       网络请求，这与项目「不引入隐式外部调用」的约束冲突。这里只使用已校验的
 *       显式 URI（authorization/token/jwks），不做发现。</li>
 * </ul>
 *
 * <p>安全默认值：
 * <ul>
 *   <li>授权码模式 + PKCE（{@code requireProofKey}），由
 *       {@code OidcProperties.requirePkce}（默认 true、生产强制 true）驱动；</li>
 *   <li>算法固定 RS256，与 Resource Server 的 {@code JwtDecoder} 保持一致；</li>
 *   <li>scope 取配置值并要求包含 {@code openid}（已由 OidcProperties 校验）；</li>
 *   <li>client secret 只接受 Secret Store 引用，解析失败即抛错而不是用空密钥继续。</li>
 * </ul>
 */
public final class OidcClientRegistrationFactory {

    /** 回调路径与 application context-path 无关，由容器加上 /api 前缀。 */
    public static final String REDIRECT_URI_TEMPLATE = "{baseUrl}/login/oauth2/code/{registrationId}";

    /** 单注册 id；多 IdP 需要改造本类并同步 policy 映射。 */
    public static final String REGISTRATION_ID = "vio";

    private OidcClientRegistrationFactory() {
    }

    /**
     * 构造注册仓储。
     *
     * @param properties    已校验的 OIDC 配置
     * @param secretResolver client secret 解析器（Secret Store 引用）
     */
    public static ClientRegistrationRepository create(OidcProperties properties,
                                                      OidcClientSecretResolver secretResolver) {
        if (properties == null || !properties.enabled()) {
            throw new IllegalStateException("OIDC 未启用时不应构造客户端注册");
        }
        if (properties.redirectUris().isEmpty()) {
            throw new IllegalStateException("OIDC 缺少 redirectUris，无法确定回调地址");
        }

        ClientRegistration.Builder builder = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(properties.clientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI_TEMPLATE)
                .scope(properties.scopes())
                .authorizationUri(properties.authorizationUri())
                .tokenUri(properties.tokenUri())
                .jwkSetUri(properties.jwksUri())
                .issuerUri(properties.issuerUri())
                .clientName("VIO AI Agent")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                // PKCE 必须显式接到 ClientRegistration 上。
                // OidcProperties.requirePkce 只做配置校验（生产强制 true），
                // 若不再调用 requireProofKey，授权请求里根本不会出现 code_challenge，
                // 于是「生产必须启用 PKCE」就只是文档承诺而不是实际保护。
                .clientSettings(ClientRegistration.ClientSettings.builder()
                        .requireProofKey(properties.requirePkce())
                        .build())
                .providerConfigurationMetadata(providerMetadata(properties));

        if (!properties.clientSecretRef().isBlank()) {
            builder.clientSecret(secretResolver.resolve(properties.clientSecretRef()));
        }

        ClientRegistration registration = builder.build();
        Map<String, ClientRegistration> byId = new LinkedHashMap<>();
        byId.put(registration.getRegistrationId(), registration);
        return new InMemoryClientRegistrationRepository(byId);
    }

    /**
     * 显式 provider metadata：授权端点必须声明 PKCE 支持，否则
     * {@code DefaultOAuth2AuthorizationRequestResolver} 不会附加 code_challenge，
     * 于是「requireProofKey」只会停留在配置层面而不产生实际保护。
     */
    private static Map<String, Object> providerMetadata(OidcProperties properties) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", properties.issuerUri());
        metadata.put("authorization_endpoint", properties.authorizationUri());
        metadata.put("token_endpoint", properties.tokenUri());
        metadata.put("jwks_uri", properties.jwksUri());
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("subject_types_supported", List.of("public"));
        metadata.put("id_token_signing_alg_values_supported", List.of("RS256"));
        return metadata;
    }
}
