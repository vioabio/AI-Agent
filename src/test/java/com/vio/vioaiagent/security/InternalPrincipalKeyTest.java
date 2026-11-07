package com.vio.vioaiagent.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部主体键派生回归（ADR-002 步骤 1）。
 *
 * <p>本测试要守住的核心性质只有一条：**tenant 与 issuer 必须真的改变归属键**。
 * 新增字段却没接线（键里不含 tenant）是这类改造最典型的假修复——
 * 代码看起来"引入了 tenant 维度"，归属判定却仍按裸 sub 走。
 * 因此这里的断言全部落在"不同输入必须得到不同键"上，而不是"方法存在"。
 */
@DisplayName("ADR-002 内部主体键派生")
class InternalPrincipalKeyTest {

    private static final String ISSUER = "https://id.example.com/realms/vio";
    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String SUBJECT = "user-alice";

    @Test
    @DisplayName("相同 issuer/tenant/subject 必须稳定派生同一键（跨实例/跨重启可复现）")
    void derivationIsDeterministic() {
        String first = InternalPrincipalKey.fromOidc(ISSUER, TENANT_A, SUBJECT);
        String second = InternalPrincipalKey.fromOidc(ISSUER, TENANT_A, SUBJECT);

        assertEquals(first, second, "同一输入必须得到同一键，否则重启后资源会找不到归属");
    }

    @Test
    @DisplayName("tenant 必须改变归属键：同 issuer 同 sub 在两个租户下是不同主体")
    void tenantChangesTheKey() {
        String inTenantA = InternalPrincipalKey.fromOidc(ISSUER, TENANT_A, SUBJECT);
        String inTenantB = InternalPrincipalKey.fromOidc(ISSUER, TENANT_B, SUBJECT);

        assertNotEquals(inTenantA, inTenantB,
                "同一 sub 在不同租户必须得到不同归属键；相等即说明 tenant 没有真正进入键，"
                        + "那样两个租户的资源会互相可见（ADR-002 风险 A）");
    }

    @Test
    @DisplayName("issuer 必须改变归属键：不同 IdP 的同名 sub 是不同主体")
    void issuerChangesTheKey() {
        String fromIssuerA = InternalPrincipalKey.fromOidc(ISSUER, TENANT_A, SUBJECT);
        String fromIssuerB = InternalPrincipalKey.fromOidc("https://other.example.com/realms/vio",
                TENANT_A, SUBJECT);

        assertNotEquals(fromIssuerA, fromIssuerB,
                "sub 只在 issuer 内唯一，issuer 必须参与键以消除跨 IdP 碰撞");
    }

    @Test
    @DisplayName("三维度各自独立生效：任意一维改变都得到不同键")
    void everyDimensionIsLoadBearing() {
        Set<String> keys = new HashSet<>();
        keys.add(InternalPrincipalKey.fromOidc(ISSUER, TENANT_A, SUBJECT));
        keys.add(InternalPrincipalKey.fromOidc(ISSUER, TENANT_A, "user-bob"));
        keys.add(InternalPrincipalKey.fromOidc(ISSUER, TENANT_B, SUBJECT));
        keys.add(InternalPrincipalKey.fromOidc("https://other.example.com", TENANT_A, SUBJECT));

        assertEquals(4, keys.size(),
                "四个不同组合必须产出四个不同键；出现重复说明某一维没有进入键");
    }

    @Test
    @DisplayName("键形态固定：p- 前缀 + 64 位小写十六进制，且不泄漏明文标识")
    void keyShapeIsStableAndNonRevealing() {
        String key = InternalPrincipalKey.fromOidc(ISSUER, TENANT_A, SUBJECT);

        assertTrue(InternalPrincipalKey.isInternalKey(key),
                "键必须可被 isInternalKey 识别，迁移期要靠它区分新旧形态: " + key);
        assertTrue(key.matches("^p-[0-9a-f]{64}$"), "键形态不符合约定: " + key);
        // owner 值会进入索引、日志、事件 payload 与审计记录，
        // 不能把 issuer/tenant/sub 原文铺到这些地方。
        assertFalse(key.contains(SUBJECT), "键不得包含 subject 明文");
        assertFalse(key.contains(TENANT_A), "键不得包含 tenant 明文");
        assertFalse(key.contains("example.com"), "键不得包含 issuer 明文");
    }

    @Test
    @DisplayName("缺任一维度必须 fail-closed，不得退回裸 sub")
    void missingDimensionFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> InternalPrincipalKey.fromOidc(null, TENANT_A, SUBJECT), "缺 issuer 必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> InternalPrincipalKey.fromOidc("  ", TENANT_A, SUBJECT), "空白 issuer 必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> InternalPrincipalKey.fromOidc(ISSUER, null, SUBJECT), "缺 tenant 必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> InternalPrincipalKey.fromOidc(ISSUER, "", SUBJECT), "空白 tenant 必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> InternalPrincipalKey.fromOidc(ISSUER, TENANT_A, null), "缺 subject 必须拒绝");
    }

    @Test
    @DisplayName("含控制字符或超长的维度必须拒绝（避免注入到键空间）")
    void illegalCharactersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> InternalPrincipalKey.fromOidc(ISSUER, "tenant\na", SUBJECT),
                "含换行的 tenant 必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> InternalPrincipalKey.fromOidc(ISSUER, TENANT_A, "x".repeat(600)),
                "超长 subject 必须拒绝");
    }

    @Test
    @DisplayName("维度值首尾空白被归一化：\" tenant-a \" 与 \"tenant-a\" 等价")
    void surroundingWhitespaceIsNormalized() {
        assertEquals(InternalPrincipalKey.fromOidc(ISSUER, TENANT_A, SUBJECT),
                InternalPrincipalKey.fromOidc(" " + ISSUER + " ", " " + TENANT_A + " ", " " + SUBJECT + " "),
                "外部 claim 可能带空白，归一化后必须得到同一键，否则同一用户会被拆成两个归属");
    }

    @Test
    @DisplayName("旧键取值保持历史语义（迁移期双读依赖它）")
    void legacyOwnerKeepsHistoricalValue() {
        // API Key 路径的历史 owner 是 stablePrincipalId 生成的数字字符串。
        assertEquals("1234567890", InternalPrincipalKey.legacyOwnerFor("1234567890"));
        // OIDC 路径的历史 owner 就是裸 sub。
        assertEquals("user-alice", InternalPrincipalKey.legacyOwnerFor("user-alice"));
        assertThrows(IllegalArgumentException.class,
                () -> InternalPrincipalKey.legacyOwnerFor("  "), "空主体必须拒绝而不是产出空 owner");
    }

    @Test
    @DisplayName("isInternalKey 不把旧键误判为新键")
    void legacyKeysAreNotMistakenForInternalKeys() {
        assertFalse(InternalPrincipalKey.isInternalKey("1234567890"), "数字型 API Key 键不是内部键");
        assertFalse(InternalPrincipalKey.isInternalKey("user-alice"), "裸 sub 不是内部键");
        assertFalse(InternalPrincipalKey.isInternalKey(null));
        assertFalse(InternalPrincipalKey.isInternalKey("p-" + "a".repeat(63)), "长度不足不得通过");
        assertFalse(InternalPrincipalKey.isInternalKey("p-" + "A".repeat(64)), "大写十六进制不得通过");
    }
}
