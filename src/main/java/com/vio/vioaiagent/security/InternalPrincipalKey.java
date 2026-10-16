package com.vio.vioaiagent.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 内部主体键派生（ADR-002 步骤 1）。
 *
 * <p>背景：资源归属此前直接使用认证主体的 {@code principalId}，而 OIDC 路径下它就是
 * ID token 的裸 {@code sub}（{@code ApiKeyAuthInterceptor} →
 * {@code RequestContext.setPrincipalId(policy.principalId())}）。
 * {@code sub} 只在单个 issuer 内唯一，且**租户完全没有参与归属判定**：
 * {@code ResourceController.owner()} 只读 {@code principalId}。
 * 同一 issuer 下同一 {@code sub} 属于两个租户时，两者的会话/队伍/收藏/Run 会落到同一 owner。
 *
 * <p>本类提供一个同时承载 **issuer + tenant + subject** 三个维度的内部键，
 * 让租户维度进入归属判定，并让 issuer 维度在键形状里预留出来。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>存储哈希而非明文</b>：owner 值会大量出现在索引、日志、事件 payload 与
 *       审计记录里；哈希避免把 issuer/sub 这类外部标识铺到各处，同时保持定长。</li>
 *   <li><b>前缀 {@code p-}</b>：与现有 API Key 派生键（纯数字字符串）和旧 OIDC 键
 *       （裸 {@code sub}）在形态上可区分，便于迁移期识别。</li>
 *   <li><b>不使用盐</b>：输入本身不是秘密，目标是稳定而非抗暴力枚举；
 *       加盐会让跨实例/跨重启的键无法复现。</li>
 *   <li><b>fail-closed</b>：任一维度为空即抛错，绝不"静默退回裸 sub"——
 *       那会让一个配置疏漏悄悄退化成越权风险。</li>
 * </ul>
 *
 * <p><b>当前状态</b>：本类只提供键派生与校验，尚未接管 owner 计算。
 * 现有键格式保持不变（见 {@link #legacyOwnerFor}），避免在不做数据迁移的情况下
 * 改变既有行的归属语义。切换流程见 ADR-002 §3。
 */
public final class InternalPrincipalKey {

    /** 新键前缀：与数字型 API Key 键、裸 sub 形旧键区分。 */
    public static final String PREFIX = "p-";

    /** 维度分隔符：使用单元分隔符，避免与任何合法 claim 值冲突。 */
    private static final char SEPARATOR = '\u001f';

    private InternalPrincipalKey() {
    }

    /**
     * 派生 OIDC 主体内部键。
     *
     * @param issuer  ID token 的 {@code iss}（已由 JwtDecoder 校验）
     * @param tenant  主体所属租户
     * @param subject ID token 的 {@code sub}
     * @return 形如 {@code p-<64 位小写十六进制>} 的稳定键
     * @throws IllegalArgumentException 任一维度为空或含控制字符
     */
    public static String fromOidc(String issuer, String tenant, String subject) {
        String normalizedIssuer = require(issuer, "issuer");
        String normalizedTenant = require(tenant, "tenant");
        String normalizedSubject = require(subject, "subject");
        return PREFIX + sha256Hex(String.join(String.valueOf(SEPARATOR),
                "oidc", normalizedIssuer, normalizedTenant, normalizedSubject));
    }

    /**
     * 现有（旧）owner 取值约定，供迁移期双读与对账使用。
     *
     * <p>与 {@code ApiKeyAuthInterceptor} 的历史行为一致：
     * OIDC 用裸 {@code sub}，API Key 用其派生数字字符串。这里显式集中表达，
     * 避免迁移时各处重新猜测旧键形状。
     */
    public static String legacyOwnerFor(String principalId) {
        return require(principalId, "principalId");
    }

    /**
     * 判断一个 owner 值是否已经是新形态的内部键。
     *
     * <p>必须校验**小写十六进制**而不只是长度：迁移期本方法用来区分新旧键，
     * 若把 {@code p-} + 64 位大写十六进制也认作新键，一个恰好长成这样的旧值
     * 会被错误分类，进而走错读取分支。大小写是这里唯一可靠的判别依据之一。
     */
    public static boolean isInternalKey(String owner) {
        if (owner == null || owner.length() != PREFIX.length() + 64 || !owner.startsWith(PREFIX)) {
            return false;
        }
        for (int i = PREFIX.length(); i < owner.length(); i++) {
            char c = owner.charAt(i);
            boolean lowerHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!lowerHex) return false;
        }
        return true;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            // 明确拒绝而不是回退：缺 tenant 或 issuer 时退回裸 sub 正是本 ADR 要消除的行为。
            throw new IllegalArgumentException(field + " 不能为空：内部主体键必须完整承载 issuer/tenant/subject");
        }
        String trimmed = value.trim();
        if (trimmed.length() > 512 || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " 含非法字符或超长");
        }
        return trimmed;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // JRE 必须提供 SHA-256；运行时破坏该保证时拒绝而不是降级到易碰撞的哈希。
            throw new IllegalStateException("JRE 不支持 SHA-256，无法派生内部主体键", ex);
        }
    }
}
