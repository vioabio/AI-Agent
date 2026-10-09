package com.vio.vioaiagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动契约校验单元测试（升级方案 F01）。
 *
 * <p>覆盖三类行为：
 * <ol>
 *   <li>非生产 profile 不触发生产契约，保证离线单测/CI 不被要求注入密钥；</li>
 *   <li>生产/demo 缺项时必须**一次性列出全部**缺失项，而不是只报第一个；</li>
 *   <li>错误信息只含变量名与用途，绝不回显变量值（避免密钥进入日志）。</li>
 * </ol>
 */
@DisplayName("F01 启动契约校验")
class StartupContractValidatorTest {

    @Test
    @DisplayName("本地/测试 profile 不启用生产契约")
    void nonProductionProfilesAreNotEnforced() {
        MockEnvironment environment = new MockEnvironment();
        for (String profile : List.of("", "local", "test", "dev", "f02-identity", "test,integration")) {
            assertTrue(StartupContractValidator.missingRequired(environment, profile).isEmpty(),
                    () -> "非生产 profile 不应要求生产密钥: '" + profile + "'");
        }
    }

    @Test
    @DisplayName("生产 profile 缺少全部必需项时一次性列出，并给出字段级用途")
    void productionListsEveryMissingVariableAtOnce() {
        MockEnvironment environment = new MockEnvironment();

        List<String> missing = StartupContractValidator.missingRequired(environment, "prod");

        assertFalse(missing.isEmpty(), "prod 且无任何环境变量时必须判定缺失");
        // 每个必需项都要出现，否则运维需要反复重启才能凑齐配置。
        for (String name : StartupContractValidator.requiredNames()) {
            assertTrue(missing.stream().anyMatch(entry -> entry.startsWith(name + " ")),
                    () -> "缺失清单未列出 " + name + ": " + missing);
        }
        // 字段级可操作性：每条都要说明它服务于哪个配置项。
        assertTrue(missing.stream().anyMatch(entry -> entry.contains("vio.resource.cursor.secret")),
                () -> "游标签名密钥缺失项应说明对应配置项: " + missing);
    }

    @Test
    @DisplayName("补齐全部必需项后生产契约通过")
    void productionPassesWhenAllRequiredPresent() {
        MockEnvironment environment = filled();

        assertTrue(StartupContractValidator.missingRequired(environment, "prod").isEmpty(),
                () -> StartupContractValidator.missingRequired(environment, "prod").toString());
    }

    @Test
    @DisplayName("空白值等同于缺失（不能靠空字符串绕过）")
    void blankValuesCountAsMissing() {
        MockEnvironment environment = filled();
        environment.setProperty("VIO_RESOURCE_CURSOR_SECRET", "   ");

        List<String> missing = StartupContractValidator.missingRequired(environment, "prod");

        assertEquals(1, missing.size(), () -> "只应报告空白的那一项: " + missing);
        assertTrue(missing.get(0).startsWith("VIO_RESOURCE_CURSOR_SECRET "));
    }

    @Test
    @DisplayName("demo 同样要求游标签名密钥与演示凭据")
    void demoEnforcesSameRequiredSet() {
        MockEnvironment environment = new MockEnvironment();

        List<String> missing = StartupContractValidator.missingRequired(environment, "demo,test");

        assertTrue(missing.stream().anyMatch(entry -> entry.startsWith("VIO_RESOURCE_CURSOR_SECRET ")),
                () -> "demo 也必须显式注入游标签名密钥: " + missing);
        assertTrue(missing.stream().anyMatch(entry -> entry.startsWith("VIO_API_KEY ")),
                () -> "demo 使用 API Key 身份，必须显式注入: " + missing);
    }

    @Test
    @DisplayName("错误信息只含变量名，不回显任何值")
    void missingReportNeverEchoesValues() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("DB_PASSWORD", "super-secret-value");
        environment.setProperty("VIO_API_KEY", "sk-another-secret");

        List<String> missing = StartupContractValidator.missingRequired(environment, "prod,region-cn");
        String report = String.join("\n", missing);

        assertFalse(report.contains("super-secret-value"), "报告不得回显密钥值");
        assertFalse(report.contains("sk-another-secret"), "报告不得回显密钥值");
        // 已提供的项不应出现在缺失清单中。
        assertTrue(missing.stream().noneMatch(entry -> entry.startsWith("DB_PASSWORD ")));
    }

    @Test
    @DisplayName("多 profile 逗号/空格分隔都能识别生产语义")
    void productionDetectionHandlesMultipleProfiles() {
        MockEnvironment environment = new MockEnvironment();
        assertFalse(StartupContractValidator.missingRequired(environment, "prod,region-cn").isEmpty());
        assertFalse(StartupContractValidator.missingRequired(environment, "cn prod").isEmpty());
        assertFalse(StartupContractValidator.missingRequired(environment, "production").isEmpty());
    }

    private MockEnvironment filled() {
        MockEnvironment environment = new MockEnvironment();
        for (String name : StartupContractValidator.requiredNames()) {
            environment.setProperty(name, "test-value-for-" + name);
        }
        return environment;
    }
}
