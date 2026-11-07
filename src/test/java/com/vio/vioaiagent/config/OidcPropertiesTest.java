package com.vio.vioaiagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OIDC/PKCE 配置契约")
class OidcPropertiesTest {

    @Test
    @DisplayName("默认关闭且非 required 时不阻断本地启动")
    void disabledOptionalIsAllowed() {
        OidcProperties properties = new OidcProperties();
        assertTrue(properties.validationErrors("local").isEmpty());
    }

    @Test
    @DisplayName("启用的 staging OIDC 配置通过非网络校验")
    void validStagingConfigurationPasses() {
        OidcProperties properties = valid();
        assertTrue(properties.validationErrors("staging").isEmpty(),
                () -> properties.validationErrors("staging").toString());
        assertEquals("vio-web", properties.publicClientSettings().clientId());
    }

    @Test
    @DisplayName("生产拒绝 HTTP/localhost 和不安全 cookie")
    void productionRejectsUnsafeSettings() {
        OidcProperties properties = valid();
        properties.setIssuerUri("http://localhost:9000");
        properties.setCookieSecure(false);
        properties.setRedirectUris(List.of("http://localhost:3000/callback"));
        List<String> errors = properties.validationErrors("prod");
        assertTrue(errors.stream().anyMatch(value -> value.contains("issuerUri")));
        assertTrue(errors.stream().anyMatch(value -> value.contains("Secure")));
        assertTrue(errors.stream().anyMatch(value -> value.contains("redirectUri")));
    }

    @Test
    @DisplayName("多个 active profile 含 prod 时仍执行生产安全校验")
    void commaSeparatedProfilesStillUseProductionRules() {
        OidcProperties properties = valid();
        properties.setIssuerUri("http://localhost:9000");
        properties.setCookieSecure(false);

        List<String> errors = properties.validationErrors("prod,region-cn");

        assertTrue(errors.stream().anyMatch(value -> value.contains("issuerUri")));
        assertTrue(errors.stream().anyMatch(value -> value.contains("Secure")));
    }

    @Test
    @DisplayName("required=true 但 disabled 必须 fail-closed")
    void requiredDisabledFails() {
        OidcProperties properties = new OidcProperties();
        properties.setRequired(true);
        assertTrue(properties.validationErrors("prod").stream().anyMatch(value -> value.contains("required")));
    }

    @Test
    @DisplayName("拒绝 secret value 和关闭 PKCE")
    void rejectsSecretAndPkce() {
        OidcProperties properties = valid();
        properties.setClientSecretRef("sk-real-value");
        properties.setRequirePkce(false);
        List<String> errors = properties.validationErrors("staging");
        assertTrue(errors.stream().anyMatch(value -> value.contains("Secret Store")));
        assertTrue(errors.stream().anyMatch(value -> value.contains("PKCE")));
    }

    @Test
    @DisplayName("JWT 算法只允许与 Resource Server 一致的 RS256")
    void onlyRs256IsAllowed() {
        OidcProperties properties = valid();
        assertTrue(properties.validationErrors("staging").isEmpty());

        for (List<String> algorithms : List.<List<String>>of(
                List.of("ES256"),
                List.of("none"),
                List.<String>of(),
                List.of(""),
                List.of("RS256", "ES256"),
                List.of("rs256"))) {
            properties.setAllowedAlgorithms(algorithms);
            List<String> errors = properties.validationErrors("staging");
            assertTrue(errors.stream().anyMatch(value ->
                            value.contains("仅支持精确配置为 [RS256]")),
                    () -> "未拒绝算法配置: " + algorithms + ", errors=" + errors);
        }
    }

    private OidcProperties valid() {
        OidcProperties properties = new OidcProperties();
        properties.setEnabled(true);
        properties.setIssuerUri("https://id.example.com/realms/vio");
        properties.setJwksUri("https://id.example.com/realms/vio/protocol/openid-connect/certs");
        properties.setAuthorizationUri("https://id.example.com/realms/vio/protocol/openid-connect/auth");
        properties.setTokenUri("https://id.example.com/realms/vio/protocol/openid-connect/token");
        properties.setAudience("vio-api");
        properties.setClientId("vio-web");
        properties.setClientSecretRef("secret://oidc/client-secret");
        properties.setRedirectUris(List.of("https://agent.example.com/auth/callback"));
        properties.setScopes(List.of("openid", "profile"));
        properties.setAllowedAlgorithms(List.of("RS256"));
        properties.setCookieSecure(true);
        return properties;
    }
}
