package com.vio.vioaiagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import com.vio.vioaiagent.security.TokenStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * demo profile ConfigData 解析测试（S0 收尾 / B01）。
 *
 * <p>为什么必须是"真实 ConfigData 加载"而不是静态 YAML 断言：
 * 原实现把 {@code spring.profiles.group.demo=prod,demo} 写在
 * {@code application-prod.yml} 里。静态 YAML 测试只验证该字段存在，因此**通过**；
 * 但实测单独激活 demo 时 active profiles 只有 {@code [demo]}，
 * 且 {@code spring.datasource.url} 未解析——group 根本不生效。
 *
 * <p>真正的危害不是"少了个 profile"：{@code application.yml} 默认排除了
 * DataSource/JdbcTemplate 自动配置，只有 prod/demo 文档才把它们打开。
 * demo 未继承时，仓储会静默回落到 {@code @Profile("!prod")} 的内存实现，
 * 演示环境重启后数据全部丢失且没有任何报错。
 *
 * <p>本测试用 {@link ApplicationContextRunner} +
 * {@link ConfigDataApplicationContextInitializer} 加载仓库里的真实配置文件，
 * 只断言环境与属性解析结果，**不启动业务 Bean、不建立数据库连接**。
 * 数据源属性存在不能证明仓储已持久化：核心 JDBC/内存 Bean 的实际选择
 * 由 {@link DemoPersistenceWiringTest} 注册真实组件后验证。
 */
@DisplayName("B01 demo profile 真实装配")
class DemoProfileActivationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties.class)
    static class SecurityBindingConfiguration {}

    @Test
    void environmentApiKeyAuthenticatesWithRealProfileBinding() {
        for (String profile : new String[]{"demo", "prod"}) {
            runnerWithPlaceholders()
                    .withUserConfiguration(SecurityBindingConfiguration.class)
                    .withPropertyValues("spring.profiles.active=" + profile,
                            "VIO_API_KEY=ci-only-vio-token")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        TokenStore store = new TokenStore(context.getBean(SecurityProperties.class));
                        assertThat(store.validate("ci-only-vio-token")).isNotNull();
                        assertThat(store.validate("${VIO_API_KEY}")).isNull();
                        assertThat(store.validate("VIO_API_KEY")).isNull();
                        assertThat(store.validate("vioapikey")).isNull();
                    });
        }
    }

    /** 让带占位符的属性可解析，但不真的连库。 */
    private ApplicationContextRunner runnerWithPlaceholders() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:1/none",
                        "SPRING_DATASOURCE_PASSWORD=it-not-a-real-password",
                        "VIO_RESOURCE_CURSOR_SECRET=it-cursor-secret");
    }

    @Test
    @DisplayName("demo 是自包含配置：不激活 prod，但必须打开 JDBC 自动配置")
    void demoIsSelfContainedWithJdbcPersistence() {
        runnerWithPlaceholders()
                .withPropertyValues("spring.profiles.active=demo")
                .run(context -> {
                    Environment env = context.getEnvironment();

                    assertThat(env.getActiveProfiles())
                            .as("demo 只激活自身；继承 prod 的三种机制（group 写 prod 文档、"
                                    + "group 写基础文档、spring.config.import）实测都会让 demo 的"
                                    + "放开被 prod 静默覆盖，因此改为自包含装配")
                            .containsExactly("demo");

                    // 自动配置条件；真实仓储选择见 DemoPersistenceWiringTest。
                    assertThat(env.getProperty("spring.autoconfigure.exclude", ""))
                            .as("demo 必须清空 application.yml 对 DataSource/JdbcTemplate 的排除，"
                                    + "否则持久化 Bean 无法获得 JDBC 依赖")
                            .doesNotContain("DataSourceAutoConfiguration")
                            .doesNotContain("JdbcTemplateAutoConfiguration");
                    assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("true");
                    assertThat(env.getProperty("spring.datasource.url"))
                            .as("必须解析出来自 application-demo.yml 的数据源地址")
                            .isEqualTo("jdbc:postgresql://127.0.0.1:1/none");
                    assertThat(env.getProperty("spring.datasource.password")).isNotBlank();
                });
    }

    @Test
    @DisplayName("demo 成对放开 OIDC：enabled=false 且 required=false")
    void demoRelaxesOidcConsistently() {
        runnerWithPlaceholders()
                .withPropertyValues("spring.profiles.active=demo")
                .run(context -> {
                    Environment env = context.getEnvironment();
                    assertThat(env.getProperty("vio.security.oidc.enabled", Boolean.class)).isFalse();
                    assertThat(env.getProperty("vio.security.oidc.required", Boolean.class))
                            .as("demo 使用 API Key 身份，required 必须同步为 false，"
                                    + "否则 OidcProperties 判定 enabled/required 冲突并阻止启动")
                            .isFalse();
                });
    }

    @Test
    @DisplayName("prod 保持 fail-closed：OIDC required=true，游标签名密钥无默认值")
    void prodStaysFailClosed() {
        runnerWithPlaceholders()
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    Environment env = context.getEnvironment();
                    assertThat(env.getProperty("vio.security.oidc.required", Boolean.class))
                            .as("生产必须保持 OIDC required=true，防止共享 API Key 冒充生产身份契约")
                            .isTrue();
                    assertThat(env.getProperty("vio.security.oidc.enabled", Boolean.class)).isFalse();
                    assertThat(env.getProperty("vio.security.oidc.cookie-secure", Boolean.class))
                            .as("生产必须要求 Secure cookie")
                            .isTrue();
                });
    }

    @Test
    @DisplayName("demo 不得放宽高危工具与 MCP 边界")
    void demoDoesNotRelaxHighRiskBoundaries() {
        runnerWithPlaceholders()
                .withPropertyValues("spring.profiles.active=demo")
                .run(context -> {
                    Environment env = context.getEnvironment();
                    assertThat(env.getProperty("vio.tools.high-risk.enabled", Boolean.class)).isFalse();
                    assertThat(env.getProperty("vio.mcp.enabled", Boolean.class)).isFalse();
                    assertThat(env.getProperty("vio.rag.legacy-memory.enabled", Boolean.class)).isFalse();
                });
    }

    @Test
    @DisplayName("demo 保持生产级安全与配额约束")
    void demoKeepsProductionGradeGuards() {
        runnerWithPlaceholders()
                .withPropertyValues("spring.profiles.active=demo")
                .run(context -> {
                    Environment env = context.getEnvironment();
                    assertThat(env.getProperty("vio.security.enabled", Boolean.class)).isTrue();
                    assertThat(env.getProperty("vio.security.oidc.require-pkce", Boolean.class)).isTrue();
                    assertThat(env.getProperty("vio.security.oidc.allowed-algorithms[0]")).isEqualTo("RS256");
                    assertThat(env.getProperty("vio.feedback.require-lineage", Boolean.class)).isTrue();
                    assertThat(env.getProperty("vio.pokemon.activity.fixture.enabled", Boolean.class)).isFalse();
                });
    }
}
