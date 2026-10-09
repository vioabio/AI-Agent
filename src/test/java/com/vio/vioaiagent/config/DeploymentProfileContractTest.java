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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * demo 是**自包含**装配，不使用 profile group。
     *
     * <p>本测试原先断言 {@code spring.profiles.group.demo=prod,demo} 存在——那是个
     * 已被证伪的设计：实测该 group 在单独激活 demo 时不生效（active profiles 只有
     * {@code [demo]}），而 group 挪到基础文档或改用 {@code spring.config.import}
     * 又会让 prod 的文档排在 demo 之上、把 demo 的放开全部覆盖。
     * 详见 {@code DemoProfileActivationTest} 与 {@code application-demo.yml} 的实测记录。
     *
     * <p>因此这里改为断言自包含装配的两个硬要求：不得残留 group（否则会重新引入
     * 不可控的文档排序），且必须显式打开 JDBC 持久化（否则静默回落内存仓储）。
     */
    @Test
    @DisplayName("demo 使用自包含装配：无 profile group，且显式打开 JDBC 持久化")
    void demoIsSelfContainedWithoutProfileGroup() {
        Map<String, Object> demoSpring = asMap(load(DEMO).get("spring"));

        assertNull(asMapNullable(demoSpring.get("profiles")),
                "demo 不得再用 spring.profiles.group：三种继承机制实测都会让 demo 的放开被 prod 静默覆盖");

        // 自包含必须自己打开 JDBC：application.yml 默认排除了 DataSource/JdbcTemplate。
        Map<String, Object> autoconfigure = asMapNullable(demoSpring.get("autoconfigure"));
        assertNotNull(autoconfigure, "demo 必须显式声明 spring.autoconfigure.exclude");
        String excludes = String.valueOf(asMap(autoconfigure).get("exclude"));
        assertFalse(excludes.contains("DataSourceAutoConfiguration"),
                "demo 不得排除 DataSourceAutoConfiguration，否则仓储会回落到内存实现、重启丢数据");
        assertFalse(excludes.contains("JdbcTemplateAutoConfiguration"),
                "demo 不得排除 JdbcTemplateAutoConfiguration");
        assertNotNull(demoSpring.get("datasource"), "demo 必须显式声明数据源");
        assertNotNull(demoSpring.get("flyway"), "demo 必须显式声明 Flyway");
    }

    @Test
    @DisplayName("demo 不得把 legacy /ai/** 之外的开关放得比 prod 更松")
    void demoOnlyRelaxesReviewedSwitches() {
        Map<String, Object> demoVio = asMap(load(DEMO).get("vio"));

        // 演示允许放开旧兼容链（便于对比新旧入口），这是经过评审的一项。
        Map<String, Object> legacy = asMapNullable(demoVio.get("legacy"));
        assertNotNull(legacy, "demo 若要放开 /ai/** 必须显式声明，不能靠 prod 默认值猜测");
        assertTrue(asMap(legacy.get("ai")).get("enabled").toString().contains("true"));

        // 风险边界不得因为“演示”而放开。自包含装配会显式写出这些键，
        // 因此断言的是**取值**而不是“键不存在”。
        assertEquals("${VIO_HIGH_RISK_TOOLS_ENABLED:false}",
                asMap(asMapNullable(demoVio.get("tools")).get("high-risk")).get("enabled"),
                "demo 不得放开高危工具：涉及进程/网络边界");
        assertEquals("${VIO_MCP_ENABLED:false}", asMap(demoVio.get("mcp")).get("enabled"),
                "demo 不得放开 MCP：涉及远端工具信任边界");
        assertEquals("${VIO_RAG_LEGACY_MEMORY_ENABLED:false}",
                asMap(asMapNullable(demoVio.get("rag")).get("legacy-memory")).get("enabled"),
                "demo 不得允许旧内存库静默降级");
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
