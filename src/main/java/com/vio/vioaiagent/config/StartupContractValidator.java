package com.vio.vioaiagent.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 启动契约校验（升级方案 F01）。
 *
 * <p>解决的问题：{@code application-prod.yml} / {@code application-demo.yml} 里大量使用
 * {@code ${VAR}} 占位符，缺少变量时 Spring 抛出的 {@code PlaceholderResolutionException}
 * 只指向某一个属性，运维看到的是“某某字段解析失败”，很难判断到底缺了哪些环境变量、
 * 应当去哪里补。更糟的是，缺失项可能要到 Bean 初始化中段才暴露。
 *
 * <p>本校验在上下文启动早期一次性收集**全部**缺失项，输出字段级、可操作的错误信息：
 * 需要哪些环境变量、分别服务于哪个配置项、以及该在哪个 profile 下设置。
 * 这样“缺生产必需项”从随机失败变成一份明确的补齐清单。
 *
 * <p>设计边界：
 * <ul>
 *   <li>只校验**必填且无安全默认值**的项。带合理默认值的可选项不在此列，
 *       避免把契约变成噪音从而被整体忽略。</li>
 *   <li>不打印任何变量值，只报告变量名与用途；错误信息可以安全进入日志和工单。</li>
 *   <li>不做“自动补默认值”。缺失就失败，这是生产安全默认值的一部分。</li>
 * </ul>
 *
 * <p>校验逻辑与 Spring 解耦为静态方法，便于不启动上下文即可单测。
 */
@Slf4j
@Component
public class StartupContractValidator {

    /**
     * 必填环境变量 → 用途说明。
     *
     * <p>使用 {@link LinkedHashMap} 保证报错顺序稳定，便于比对回归输出。
     */
    static final Map<String, String> PROD_REQUIRED = requiredProd();

    private final Environment environment;

    public StartupContractValidator(Environment environment) {
        this.environment = environment;
    }

    /**
     * 在配置绑定完成后、业务 Bean 使用这些值之前执行。
     *
     * <p>失败即阻止启动：宁可起不来，也不要带着不完整的安全/配额配置对外服务。
     */
    @PostConstruct
    public void validate() {
        String profiles = String.join(",", environment.getActiveProfiles());
        List<String> missing = missingRequired(environment, profiles);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "启动契约校验失败（profile=" + profiles + "）。以下必需环境变量缺失或为空：\n  - "
                            + String.join("\n  - ", missing)
                            + "\n请在部署环境/Secret Store 中补齐后重新启动。"
                            + "缺项必须显式失败，不得通过关闭校验绕过。");
        }
        log.info("启动契约校验通过: profile={}", profiles.isBlank() ? "(default)" : profiles);
    }

    /**
     * 返回缺失的必填项描述（空列表表示通过）。
     *
     * @param resolver 任意属性解析器（生产为 {@link Environment}，测试可传 Map）
     * @param profiles 逗号分隔的 active profiles
     */
    static List<String> missingRequired(PropertyResolver resolver, String profiles) {
        List<String> missing = new ArrayList<>();
        if (!isProduction(profiles) && !isDemo(profiles)) {
            // 本地/测试/CI 不启用生产契约，避免离线仅跑单测也被要求注入密钥。
            return missing;
        }
        for (Map.Entry<String, String> entry : PROD_REQUIRED.entrySet()) {
            String value = resolver.getProperty(entry.getKey());
            if (value == null || value.isBlank()) {
                missing.add(entry.getKey() + " — " + entry.getValue());
            }
        }
        // 演示 profile 额外要求游标签名密钥：prod 与 demo 都已移除默认值，
        // 这里把原因写清楚，避免运维以为“演示就不用密钥”。
        if (isDemo(profiles) && missing.isEmpty()) {
            log.info("演示 profile 已提供游标签名密钥，跨实例游标可用");
        }
        return missing;
    }

    /** 生产语义判定：与 OidcProperties 保持一致的逗号/空白分隔处理。 */
    private static boolean isProduction(String profiles) {
        return containsProfile(profiles, "prod") || containsProfile(profiles, "production");
    }

    private static boolean isDemo(String profiles) {
        return containsProfile(profiles, "demo");
    }

    private static boolean containsProfile(String profiles, String expected) {
        if (profiles == null || profiles.isBlank()) return false;
        for (String value : profiles.split("[,\\s]+")) {
            if (expected.equalsIgnoreCase(value.trim())) return true;
        }
        return false;
    }

    private static Map<String, String> requiredProd() {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("DB_PASSWORD",
                "PostgreSQL 连接密码（SPRING_DATASOURCE_PASSWORD），无安全默认值");
        required.put("VIO_API_KEY",
                "过渡期 API Key 身份（vio.security.api-keys）。多用户公网部署应改用 OIDC");
        required.put("VIO_CORS_ALLOWED_ORIGINS",
                "浏览器允许来源白名单（vio.http.cors.allowed-origins），禁止通配符");
        required.put("VIO_FEEDBACK_OPERATOR_ID",
                "反馈运营主体标识（vio.feedback.operator-roles），不得信任请求头");
        required.put("VIO_AGENT_V1_ENABLED",
                "候选主链显式开关，避免把未验收主链误开到未知环境");
        required.put("VIO_RAG_PGVECTOR_ENABLED",
                "PGVector 检索显式开关，未完成真实验收时保持关闭");
        required.put("AI_DASHSCOPE_API_KEY",
                "模型供应商凭据（spring.ai.dashscope.api-key）");
        required.put("SEARCH_API_KEY",
                "搜索服务凭据（search-api.api-key）");
        required.put("VIO_RESOURCE_CURSOR_SECRET",
                "资源游标签名密钥（vio.resource.cursor.secret），跨实例复用必须稳定，无默认值");
        return required;
    }

    /** 供诊断/测试读取的必填项名称集合（稳定排序）。 */
    public static TreeSet<String> requiredNames() {
        return new TreeSet<>(PROD_REQUIRED.keySet());
    }
}
