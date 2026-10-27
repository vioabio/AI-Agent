package com.vio.vioaiagent.pokemon.reference;

import java.util.*;

/** Presentation of validated records, never a joint learning or legality verdict. */
public final class BuildLearningNotes {
    public static final String VERSION = "recorded-learning-level-notes-v1";
    public static final int MAX_NOTES = 6;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private BuildLearningNotes() { }

    public static List<String> describe(BuildCheckFacts.Move move, Integer level) {
        if (!"RECORDED".equals(move.status()) || move.learning() == null) return List.of();
        var learning = PokemonLearnsetFacts.validate(JSON.valueToTree(move.learning()));
        if (!learning.moveSlug().equals(move.normalized())) throw new IllegalArgumentException("招式学习记录不匹配");
        var notes = new LinkedHashSet<String>();
        learning.entries().stream().sorted(Comparator.comparing(PokemonLearnsetFacts.Entry::methodKey)
                .thenComparingInt(PokemonLearnsetFacts.Entry::levelLearnedAt)).forEach(row -> notes.add(describe(row, level)));
        var result = new ArrayList<>(notes.stream().limit(MAX_NOTES).toList());
        if (notes.size() > MAX_NOTES) result.add("另有 " + (notes.size() - MAX_NOTES) + " 条记录，详见来源；不据此推断其他路径。");
        return List.copyOf(result);
    }

    private static String describe(PokemonLearnsetFacts.Entry row, Integer level) {
        return switch (row.methodKey()) {
            case "level-up" -> row.levelLearnedAt() == 0
                    ? "升级记录：等级字段为 0，不能据此解释为可在 0 级学习。"
                    : "升级学习：记录等级 " + row.levelLearnedAt() + "；" + (level == null || level < 1 || level > 100
                        ? "未提供有效配置等级，暂不比较。"
                        : row.levelLearnedAt() > level ? "高于配置等级 " + level + "，需另核对取得方式。"
                        : "不高于配置等级 " + level + "，实际取得仍待核对。");
            case "machine" -> "招式学习器：快照记录此途径；未核对编号、获取与使用条件。";
            case "egg" -> "蛋招式：快照记录此类别；未核对遗传、传授或具体操作条件。";
            case "tutor" -> "教授招式：快照记录此类别；未核对NPC、地点或操作条件。";
            default -> "其他学习方式（" + row.methodKey() + "）：仅保留快照类别，具体操作待核对。";
        };
    }
}
