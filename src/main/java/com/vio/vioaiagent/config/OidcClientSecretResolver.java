package com.vio.vioaiagent.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * OIDC client secret 解析器（R01）。
 *
 * <p>设计约束来自 {@code OidcProperties}：{@code clientSecretRef} 只接受
 * Secret Store 引用（{@code secret://} / {@code vault://} / {@code kms://}），
 * 绝不接受明文 secret value。本类负责把引用解析成运行期值。
 *
 * <p>当前实现覆盖 {@code secret://<name>} → 环境变量
 * {@code VIO_OIDC_SECRET_<NAME>}。这样做的理由：
 * <ul>
 *   <li>不引入新的外部依赖（Vault/KMS 客户端），先让登录闭环可用；</li>
 *   <li>解析失败必须抛错，绝不返回空串或占位符——
 *       用一个空 client secret 去换 token 只会得到难以诊断的
 *       {@code invalid_client}，而且会掩盖「Secret 没配」这一事实；</li>
 *   <li>{@code vault://} 与 {@code kms://} 明确不支持并给出可操作提示，
 *       避免像已接通 KMS 那样继续运行。</li>
 * </ul>
 *
 * <p>本类只读取环境变量名，不记录、不返回任何日志中的 secret 值。
 */
@Component
public class OidcClientSecretResolver {

    /** 环境变量前缀：secret://oidc/client-secret → VIO_OIDC_SECRET_OIDC_CLIENT_SECRET。 */
    static final String ENV_PREFIX = "VIO_OIDC_SECRET_";

    private final Environment environment;

    public OidcClientSecretResolver(Environment environment) {
        this.environment = environment;
    }

    /**
     * 解析 Secret Store 引用。
     *
     * @param reference 形如 {@code secret://oidc/client-secret} 的引用
     * @return 非空 secret 值
     * @throws IllegalStateException 引用格式非法或对应 Secret 缺失
     */
    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalStateException("OIDC client secret 引用不能为空");
        }
        String ref = reference.trim();
        if (ref.startsWith("secret://")) {
            return fromEnvironment(ref.substring("secret://".length()));
        }
        if (ref.startsWith("vault://") || ref.startsWith("kms://")) {
            // 明确拒绝而不是静默降级：把「没接 KMS」暴露成启动失败，
            // 好过在运行期用错误凭据反复换 token。
            throw new IllegalStateException(
                    "暂不支持 " + ref.substring(0, ref.indexOf("://") + 3)
                            + " 引用；当前仅实现 secret:// → 环境变量。"
                            + "请改用 secret:// 形式，或实现对应 Secret Store 适配器。");
        }
        if (ref.contains("://")) {
            throw new IllegalStateException("无法识别的 Secret Store 协议，仅支持 secret://");
        }
        // 未带协议的字面量视为配置错误：OidcProperties 已拒绝明文 secret value。
        throw new IllegalStateException(
                "client secret 必须是 Secret Store 引用（secret://...），不接受明文值");
    }

    private String fromEnvironment(String name) {
        String key = name.trim();
        if (key.isEmpty()) {
            throw new IllegalStateException("client secret 引用缺少名称");
        }
        String envName = ENV_PREFIX + key.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_");
        String value = environment.getProperty(envName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "未找到 OIDC client secret：引用 " + key + " 需要环境变量 " + envName
                            + "。请由 Secret Store 注入后再启动；缺失时拒绝启动，不使用空密钥。");
        }
        return value;
    }

    /** 供诊断/测试读取环境变量名，不泄漏值。 */
    static String environmentNameFor(String reference) {
        String ref = reference == null ? "" : reference.trim();
        String key = ref.startsWith("secret://") ? ref.substring("secret://".length()) : ref;
        return ENV_PREFIX + key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }
}
