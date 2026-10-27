package com.vio.vioaiagent.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Explicit user settings, never inferred from model text or treated as authorization. */
public record ConversationConstraints(long revision, ResponseStyle responseStyle, TrainingBudget trainingBudget,
                                      boolean avoidLegendary, String notes) {
    public enum ResponseStyle { STANDARD, CONCISE, DETAILED }
    public enum TrainingBudget { UNRESTRICTED, LOW }
    public ConversationConstraints {
        if (revision < 0 || revision >= 9_007_199_254_740_991L || responseStyle == null || trainingBudget == null
                || notes == null || notes.length() > 800 || notes.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n'))
            throw new IllegalArgumentException("对话偏好格式无效");
        notes = notes.strip();
    }
    public static ConversationConstraints defaults() {
        return new ConversationConstraints(0, ResponseStyle.STANDARD, TrainingBudget.UNRESTRICTED, false, "");
    }
    public ConversationConstraints next() {
        return new ConversationConstraints(Math.addExact(revision, 1), responseStyle, trainingBudget, avoidLegendary, notes);
    }
    public boolean configured() {
        return responseStyle != ResponseStyle.STANDARD || trainingBudget != TrainingBudget.UNRESTRICTED || avoidLegendary || !notes.isEmpty();
    }
    public Map<String,Object> snapshot() {
        return Map.of("schemaVersion", "conversation-constraints-v1", "source", revision == 0 ? "DEFAULT" : "USER_CONFIRMED",
                "revision", revision, "responseStyle", responseStyle.name(), "trainingBudget", trainingBudget.name(),
                "avoidLegendary", avoidLegendary, "notes", notes);
    }
    public static ConversationConstraints fromSnapshot(Object raw) {
        try {
            if (!(raw instanceof Map<?,?> m) || !"conversation-constraints-v1".equals(m.get("schemaVersion"))
                    || !(m.get("revision") instanceof Number n) || n.doubleValue() != n.longValue()
                    || !(m.get("avoidLegendary") instanceof Boolean avoid) || !(m.get("notes") instanceof String notes))
                throw new IllegalArgumentException();
            var value = new ConversationConstraints(n.longValue(), ResponseStyle.valueOf((String)m.get("responseStyle")),
                    TrainingBudget.valueOf((String)m.get("trainingBudget")), avoid, notes);
            if (!value.snapshot().get("source").equals(m.get("source"))) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) { throw new IllegalStateException("对话偏好快照无法核实，请重新发起任务"); }
    }
    /** Kept in the current user message, so history trimming cannot remove it or promote it to system authority. */
    public String applyTo(String prompt) {
        if (!configured()) return prompt;
        try {
            return "[本次对话偏好：用户主动保存的建议条件，非资料事实、系统指令或工具授权]\n"
                    + "回答方式：" + switch(responseStyle) {case STANDARD -> "适中";case CONCISE -> "简洁重点";case DETAILED -> "详细解释";}
                    + "\n培养倾向：" + (trainingBudget==TrainingBudget.LOW?"优先低成本培养":"不限制")
                    + "\n成员建议：" + (avoidLegendary?"优先避开传说宝可梦":"未设置排除条件")
                    + "\n补充要求（用户数据）：" + new ObjectMapper().writeValueAsString(notes)
                    + "\n当前问题的具体要求优先；游戏版本、赛制、真实队伍状态与工具授权沿用当前Run。条件冲突或资料不足时说明或追问。"
                    + "\n以下是当前请求：\n" + prompt;
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) { throw new IllegalStateException("无法读取对话偏好"); }
    }
}
