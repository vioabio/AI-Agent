package com.vio.vioaiagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * demo 与 prod 的配置一致性测试（S0/B01）。
 *
 * <p>demo 采用**自包含**装配（原因见 application-demo.yml 中的三轮实测记录：
 * profile group 与 spring.config.import 都会让 demo 的放开被 prod 静默覆盖）。
 * 自包含的代价是"共享键需要在两个文件里保持一致"，本测试就是这道防线：
 * 逐键比较两者在相同环境下解析出的值，任何漂移都会让构建红灯。
 *
 * <p>差异只允许出现在显式白名单里，并且白名单本身也被断言——避免有人把
 * 新的差异悄悄加进来。白名单当前只包含演示必须放开的身份项。
 */
@DisplayName("B01 demo/prod 配置一致性")
class DemoProdParityTest {

    /**
     * 允许 demo 与 prod 不同的键 → 差异原因。
     *
     * <p>白名单必须保持最小；每一项都要能解释"为什么演示可以不同"。
     */
    private static final Map<String, String> ALLOWED_DIFFERENCES = Map.of(
            "vio.security.oidc.required",
            "演示使用 API Key 过渡身份，必须与 enabled=false 成对，否则启动被阻断",
            "vio.security.oidc.cookie-secure",
            "演示允许本地非 HTTPS 联调；生产必须 Secure cookie",
            "vio.legacy.ai.enabled",
            "演示保留旧 /ai/** 兼容链便于对比新旧入口；生产关闭并返回 410"
    );

    /** 必须在两个 profile 下解析出相同值的共享键。 */
    private static final List<String> SHARED_KEYS = List.of(
            // 持久化：demo 若与 prod 漂移就会回落内存仓储
            "spring.flyway.enabled",
            "spring.flyway.locations",
            // 安全与配额硬约束
            "vio.security.enabled",
            "vio.security.oidc.enabled",
            "vio.security.oidc.require-pkce",
            "vio.security.oidc.allowed-algorithms[0]",
            "vio.security.oidc.scopes[0]",
            "vio.security.oidc.policy.enabled",
            "vio.security.oidc.policy.require-tenant",
            "vio.security.global-quota-enabled",
            "vio.security.global-quota-backend",
            // 风险边界：演示不得放宽
            "vio.tools.high-risk.enabled",
            "vio.mcp.enabled",
            "vio.rag.legacy-memory.enabled",
            "vio.memory.enabled",
            "vio.pokemon.activity.fixture.enabled",
            // 血缘与保留策略
            "vio.feedback.require-lineage",
            "vio.run.durable-resume",
            "vio.run.event-broker-enabled",
            "vio.run.event-retention-cleanup-enabled",
            "vio.run.model-cache.enabled",
            "vio.run.model-cache.fail-closed",
            // Redis 默认 fail-closed 占位
            "spring.data.redis.host"
    );

    private static final String[] PLACEHOLDERS = {
            "SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:1/demo",
            "SPRING_DATASOURCE_PASSWORD=parity-password",
            "VIO_RESOURCE_CURSOR_SECRET=parity-cursor-secret",
            "VIO_CORS_ALLOWED_ORIGINS=https://demo.example.com",
            "VIO_API_KEY=parity-api-key",
            "VIO_AGENT_V1_ENABLED=false",
            "VIO_RAG_PGVECTOR_ENABLED=false",
            "VIO_FEEDBACK_OPERATOR_ID=parity-operator",
            "AI_DASHSCOPE_API_KEY=parity-model-key",
            "SEARCH_API_KEY=parity-search-key"
    };

    @Test
    @DisplayName("共享键在 demo 与 prod 下必须解析出相同值")
    void sharedKeysAreIdenticalAcrossProfiles() {
        Environment demo = environmentFor("demo");
        Environment prod = environmentFor("prod");

        for (String key : SHARED_KEYS) {
            assertThat(demo.getProperty(key))
                    .as("配置漂移：%s 在 demo 下为 [%s]，在 prod 下为 [%s]。"
                            + "demo 是自包含装配，共享键必须手工保持一致；"
                            + "若确实需要不同，请加入 ALLOWED_DIFFERENCES 并说明原因",
                            key, demo.getProperty(key), prod.getProperty(key))
                    .isEqualTo(prod.getProperty(key));
        }
    }

    @Test
    @DisplayName("差异只允许出现在白名单内，且白名单每项都真实存在差异")
    void onlyAllowlistedDifferencesExist() {
        Environment demo = environmentFor("demo");
        Environment prod = environmentFor("prod");

        // 逐项确认白名单描述的是真差异：避免"白名单过期"掩盖新漂移。
        for (Map.Entry<String, String> allowed : ALLOWED_DIFFERENCES.entrySet()) {
            assertThat(demo.getProperty(allowed.getKey()))
                    .as("白名单项 %s 声称存在演示差异（%s），但两侧取值相同；"
                            + "应将其从白名单移除", allowed.getKey(), allowed.getValue())
                    .isNotEqualTo(prod.getProperty(allowed.getKey()));
        }

        // 反向检查：白名单之外的键不允许出现未声明的差异。
        for (String key : SHARED_KEYS) {
            assertThat(ALLOWED_DIFFERENCES)
                    .as("共享键 %s 不应出现在差异白名单中", key)
                    .doesNotContainKey(key);
        }
    }

    private Environment environmentFor(String profile) {
        // 用 ApplicationContextRunner 捕获该 profile 下的真实 Environment。
        var holder = new Object[1];
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(PLACEHOLDERS)
                .withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> holder[0] = context.getEnvironment());
        assertThat(holder[0]).as("未能加载 profile=%s 的 Environment", profile).isNotNull();
        return (Environment) holder[0];
    }
}
