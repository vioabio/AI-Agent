package com.vio.vioaiagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Profile 配置契约测试（升级方案 F01）。
 *
 * <p>锁定的是**配置文件层面的部署契约**，不启动 Spring 上下文：
 * <ol>
 *   <li>{@code application-prod.yml} 必须保持 fail-closed 默认值
 *       （OIDC enabled=false 但 required=true），并保留无默认值的游标签名密钥；</li>
 *   <li>{@code application-demo.yml} 必须成对地把 enabled/required 都设为 false，
 *       否则 {@code OidcProperties.validationErrors()} 会判定冲突并阻止启动，
 *       这正是 F01 报告的真实启动阻断；</li>
 *   <li>prod 中声明的 profile group 必须让 demo 自动叠加 prod，
 *       避免出现“只激活 demo、丢掉生产硬约束”的半配置环境。</li>
 * </ol>
 *
 * <p>为什么用静态 YAML 断言而不是启动上下文：启动成本低、失败信息直接指向配置行，
 * 且能在 CI 中离线运行。运行期的 fail-closed 行为由 {@code OidcPropertiesTest}
 * 与 {@code StartupContractValidatorTest} 覆盖。
 */
@DisplayName("F01 profile 配置契约")
class DeploymentProfileContractTest {

    private static final String PROD = "application-prod.yml";
    private static final String DEMO = "application-demo.yml";

    @Test
    @DisplayName("prod 保持 fail-closed：OIDC 默认关闭但标记为必需")
    void prodStaysFailClosed() {
        Map<String, Object> oidc = oidcSection(load(PROD));

        assertEquals("${VIO_OIDC_ENABLED:false}", oidc.get("enabled"),
                "prod 默认不得启用 OIDC，避免在未接入 IdP 时静默失败");
        assertEquals("${VIO_OIDC_REQUIRED:true}", oidc.get("required"),
                "prod 必须保持 required=true，防止共享 API Key 被当成生产身份契约上线");
    }

    @Test
    @DisplayName("prod 的游标签名密钥不提供默认值")
    void prodCursorSecretHasNoDefault() {
        Map<String, Object> cursor = cursorSection(load(PROD));

        assertEquals("${VIO_RESOURCE_CURSOR_SECRET}", cursor.get("secret"),
                "游标签名密钥必须无默认值：跨实例复用需要稳定密钥，兜底默认值会造成静默降级");
    }

    @Test
    @DisplayName("demo 成对放开 OIDC：enabled=false 与 required=false 必须一致")
    void demoRelaxesOidcConsistently() {
        Map<String, Object> oidc = oidcSection(load(DEMO));

        assertEquals("${VIO_OIDC_ENABLED:false}", oidc.get("enabled"));
        assertEquals("${VIO_OIDC_REQUIRED:false}", oidc.get("required"),
                "demo 使用 API Key 身份时必须同步把 required 设为 false，"
                        + "否则 OidcProperties 判定 enabled/required 冲突，应用无法启动");
    }

    @Test
    @DisplayName("demo 同样不提供游标签名密钥默认值")
    void demoCursorSecretHasNoDefault() {
        Map<String, Object> cursor = cursorSection(load(DEMO));

        assertEquals("${VIO_RESOURCE_CURSOR_SECRET:}", cursor.get("secret"),
                "demo 也不得内置密钥值，缺失时应由启动契约给出字段级错误");
    }

    @Test
    @DisplayName("prod 声明 demo 为 prod+demo 的 profile group")
    void demoProfileGroupIncludesProd() {
        Map<String, Object> spring = asMap(load(PROD).get("spring"));
        Map<String, Object> profiles = asMapNullable(spring.get("profiles"));
        assertNotNull(profiles, "prod 必须声明 spring.profiles.group 以保证 demo 叠加 prod");

        Object group = asMap(profiles).get("group");
        assertNotNull(group, "prod 必须声明 spring.profiles.group.demo");
        assertEquals("prod,demo", asMap(group).get("demo"),
                "demo 必须自动叠加 prod，否则演示环境会丢掉生产硬约束");
    }

    @Test
    @DisplayName("demo 不得把 legacy /ai/** 之外的开关放得比 prod 更松")
    void demoOnlyRelaxesReviewedSwitches() {
        Map<String, Object> demoVio = asMap(load(DEMO).get("vio"));

        // 演示允许放开旧兼容链（便于对比新旧入口），这是经过评审的一项。
        Map<String, Object> legacy = asMapNullable(demoVio.get("legacy"));
        assertNotNull(legacy, "demo 若要放开 /ai/** 必须显式声明，不能靠 prod 默认值猜测");
        assertTrue(asMap(legacy.get("ai")).get("enabled").toString().contains("true"));

        // 高风险工具与 MCP 不得因为“演示”而被放开：这两项涉及进程/网络边界。
        Map<String, Object> prodVio = asMap(load(PROD).get("vio"));
        assertFalse(demoVio.containsKey("mcp"),
                "demo 不得覆盖 vio.mcp，避免意外放宽远端工具边界");
        assertFalse(demoVio.containsKey("tools"),
                "demo 不得覆盖 vio.tools，高危工具必须继续默认关闭");
    }

    // ==================== YAML 读取辅助 ====================

    private static Map<String, Object> load(String resource) {
        try (InputStream in = DeploymentProfileContractTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, () -> "找不到配置文件 " + resource);
            Object parsed = new Yaml().load(in);
            assertNotNull(parsed, () -> resource + " 内容为空");
            return asMap(parsed);
        } catch (IOException ex) {
            throw new IllegalStateException("读取 " + resource + " 失败", ex);
        }
    }

    private static Map<String, Object> oidcSection(Map<String, Object> root) {
        Map<String, Object> vio = asMap(root.get("vio"));
        Map<String, Object> security = asMap(vio.get("security"));
        Map<String, Object> oidc = asMapNullable(security.get("oidc"));
        assertNotNull(oidc, "配置必须声明 vio.security.oidc");
        return oidc;
    }

    private static Map<String, Object> cursorSection(Map<String, Object> root) {
        Map<String, Object> vio = asMap(root.get("vio"));
        Map<String, Object> resource = asMapNullable(vio.get("resource"));
        assertNotNull(resource, "配置必须声明 vio.resource");
        Map<String, Object> cursor = asMapNullable(resource.get("cursor"));
        assertNotNull(cursor, "配置必须声明 vio.resource.cursor");
        return cursor;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertNotNull(value, "期望映射节点，实际为 null");
        assertTrue(value instanceof Map, () -> "期望映射节点，实际为 " + value.getClass().getSimpleName());
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMapNullable(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}
