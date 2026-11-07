package com.vio.vioaiagent.run;

import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * N4/N5 结构化事件的机器契约注册表。
 *
 * <p>事件类型先在这里登记，再由 Run 生命周期服务校验后写入事件仓储。
 * 这样前端可以按稳定的 wire name/release 字段渲染卡片，不需要从自由文本
 * 猜测事实或引用。旧的 v1.0 事件仍走兼容路径；只有新增结构化事件启用
 * 本注册表的严格门禁。</p>
 */
public final class AgentEventContractRegistry {
    private static final String SCHEMA_VERSION = "v1.1";

    private static final Map<AgentEventType, EventContract> CONTRACTS;

    static {
        EnumMap<AgentEventType, EventContract> values = new EnumMap<>(AgentEventType.class);
        values.put(AgentEventType.CONTEXT_CARD, contract(
                Set.of("gameReleaseId", "battleFormatId", "rulesetVersionId", "language"), false));
        values.put(AgentEventType.FACT_CARD, contract(
                Set.of("factId", "name", "types", "baseStats", "gameReleaseId", "dataReleaseId",
                        "sourceUrl", "evidenceText"), true));
        values.put(AgentEventType.CALCULATION_CARD, contract(
                Set.of("calculationId", "calculationType", "result", "gameReleaseId", "battleFormatId",
                        "rulesetVersionId", "dataReleaseId"), true));
        values.put(AgentEventType.TEAM_MATRIX, contract(
                Set.of("teamId", "members", "gameReleaseId", "battleFormatId", "rulesetVersionId",
                        "dataReleaseId"), true));
        values.put(AgentEventType.ACTIVITY_CARD, contract(
                Set.of("noticeId", "title", "sourceUrl", "retrievedAt", "effectiveFrom", "freshness",
                        "gameReleaseId"), false));
        values.put(AgentEventType.RETRIEVAL_CITATION_CARD, contract(
                Set.of("sourceUrl", "evidenceText", "documentVersion", "chunkId", "dataReleaseId",
                        "knowledgeReleaseId", "indexReleaseId"), true));
        values.put(AgentEventType.ANSWER_COMPLETED, contract(
                Set.of("answerId", "lineageStatus"), false));
        values.put(AgentEventType.FEEDBACK_CARD, contract(
                Set.of("answerId", "feedbackId", "status"), false));
        values.put(AgentEventType.ERROR_CARD, contract(
                Set.of("code", "message"), false));
        CONTRACTS = Map.copyOf(values);
    }

    private AgentEventContractRegistry() {
    }

    public static String schemaVersion() {
        return SCHEMA_VERSION;
    }

    public static boolean isStructured(AgentEventType type) {
        return type != null && CONTRACTS.containsKey(type);
    }

    public static EventContract contract(AgentEventType type) {
        return type == null ? null : CONTRACTS.get(type);
    }

    /**
     * 校验新增卡片事件。只检查契约边界，不对业务数值做模型式推断；数值
     * 正确性由 Fact/Calculator/评测层负责。
     */
    public static void validate(AgentEventType type, Map<String, Object> payload) {
        EventContract specification = contract(type);
        if (specification == null) return;
        Map<String, Object> safe = payload == null ? Map.of() : payload;
        for (String key : specification.requiredFields()) {
            if (!safe.containsKey(key) || blank(safe.get(key))) {
                // context.card 允许空值表示明确的缺失上下文；键本身仍必须存在，
                // 以便前端区分“字段缺失”和“需要追问”。
                if (type == AgentEventType.CONTEXT_CARD && safe.containsKey(key)) continue;
                throw new IllegalArgumentException(type.wireName() + " 缺少字段: " + key);
            }
        }
        if (specification.releaseBound()) {
            for (String key : List.of("dataReleaseId", "knowledgeReleaseId", "indexReleaseId")) {
                if (safe.containsKey(key) && placeholder(safe.get(key))) {
                    throw new IllegalArgumentException(type.wireName() + " 的 " + key + " 不能是占位符");
                }
            }
        }
        Object sourceUrl = safe.get("sourceUrl");
        if (sourceUrl != null && !blank(sourceUrl) && !httpUrl(String.valueOf(sourceUrl))) {
            throw new IllegalArgumentException(type.wireName() + " sourceUrl 仅允许 HTTP(S)");
        }
        if (type == AgentEventType.ANSWER_COMPLETED
                && "COMPLETE".equalsIgnoreCase(String.valueOf(safe.get("lineageStatus")))) {
            for (String key : List.of("gameReleaseId", "battleFormatId", "rulesetVersionId",
                    "dataReleaseId", "knowledgeReleaseId", "indexReleaseId")) {
                if (placeholder(safe.get(key))) {
                    throw new IllegalArgumentException("完整 answer.completed 缺少 " + key);
                }
            }
        }
    }

    private static EventContract contract(Set<String> requiredFields, boolean releaseBound) {
        return new EventContract(SCHEMA_VERSION, requiredFields, releaseBound);
    }

    private static boolean blank(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private static boolean placeholder(Object value) {
        if (blank(value)) return true;
        String text = String.valueOf(value).trim();
        return "unknown".equalsIgnoreCase(text) || "undefined".equalsIgnoreCase(text)
                || text.toLowerCase(java.util.Locale.ROOT).contains("replace-with");
    }

    private static boolean httpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public record EventContract(String schemaVersion, Set<String> requiredFields, boolean releaseBound) {
        public EventContract {
            requiredFields = requiredFields == null ? Set.of() : Set.copyOf(requiredFields);
        }
    }
}
