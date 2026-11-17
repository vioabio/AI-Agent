package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.BuildCheckFacts;
import com.vio.vioaiagent.pokemon.reference.PokemonLearnsetFacts;
import java.util.*;

/** Compares recorded learning thresholds with declared constraints, never proof of acquisition. */
public final class TrainingLearningCheck {
    private static final ObjectMapper JSON = new ObjectMapper();
    private TrainingLearningCheck() { }

    public static List<String> describe(BuildCheckFacts.Move move, Integer savedLevel, TrainingIntentState intent) {
        if (!"RECORDED".equals(move.status()) || move.learning() == null) return List.of();
        var learning = PokemonLearnsetFacts.validate(JSON.valueToTree(move.learning()));
        if (!learning.moveSlug().equals(move.normalized())) throw new IllegalArgumentException("学习记录与招式不匹配");
        if (intent.conflicted()) return List.of("声明条件有冲突，暂不判断学习等级与训练条件是否相容。");
        if (savedLevel == null || savedLevel < 1 || savedLevel > 100) return List.of("没有有效保存等级，暂不核对升级学习条件。");
        var levels = learning.entries().stream().filter(e -> "level-up".equals(e.methodKey()) && e.levelLearnedAt() > 0)
                .map(PokemonLearnsetFacts.Entry::levelLearnedAt).distinct().sorted().toList();
        if (levels.isEmpty()) return List.of();
        var constraints = TrainingLevelConstraints.from(intent);
        boolean prohibited = constraints.permission() == TrainingLevelConstraints.Permission.PROHIBITED;
        Integer ceiling = constraints.cap(), targetLevel = constraints.targetLevel();
        var notes = new ArrayList<String>();
        for (int threshold : levels.stream().limit(3).toList()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            var note = new StringBuilder("学习条件核对：升级记录 ").append(threshold).append(" 级；");
            if (threshold <= savedLevel) note.append("不高于保存等级，但不能证明已学会或可直接回忆。");
            else {
                note.append("高于保存等级 ").append(savedLevel).append("。");
                if (prohibited) note.append("已声明不允许练级，此升级途径不符合该限制，不安排升级。");
                if (ceiling != null && threshold > ceiling) note.append("记录等级超过声明练级上限 ").append(ceiling).append("，不按此途径安排升级。");
                if (targetLevel != null && threshold > targetLevel) note.append("记录等级高于声明目标等级 ").append(targetLevel).append("，达到该目标仍不足以满足此记录的等级门槛。");
                if (!prohibited && (ceiling == null || threshold <= ceiling) && (targetLevel == null || threshold <= targetLevel))
                    note.append("等级记录未证明当前可以执行训练；实际等级、练级许可及取得条件仍待确认。");
            }
            notes.add(note.toString());
        }
        if (levels.size() > 3) notes.add("另有 " + (levels.size() - 3) + " 个升级等级记录未展开，请指定招式继续核对。");
        var alternatives = learning.entries().stream().map(PokemonLearnsetFacts.Entry::methodKey)
                .filter(k -> !"level-up".equals(k)).distinct().sorted().map(k -> switch(k) {
                    case "machine" -> "学习器"; case "egg" -> "蛋招式"; case "tutor" -> "教授招式"; default -> "其他方式";
                }).distinct().toList();
        notes.add(alternatives.isEmpty() ? "本次未记录其他学习方式，不等于不存在其他途径或无法学习。"
                : "另有" + String.join("、", alternatives) + "记录；其解锁、资源及实际操作仍待核对，不能自动视作可用替代途径。");
        return List.copyOf(notes);
    }
}
