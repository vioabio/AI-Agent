package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.reference.PokemonLearnsetFacts;
import java.util.*;

/** Publishes only argument-bound learn rows, without inferring missing generations or operations. */
public final class LearnsetAnswerEvidence {
    public static final String VERSION = "bound-learnset-reference-v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, PokemonLearnsetFacts> facts = new LinkedHashMap<>();
    private boolean attempted;
    private boolean failed;
    public static boolean requested(String question) {
        return question != null && question.matches("(?s).*(?:学习途径|学习方式|怎么学|如何学|几级学|多少级学).*");
    }
    public boolean attempted() { return attempted; }
    public void observe(String output, String arguments) {
        attempted = true;
        try {
            var input = JSON.readTree(arguments);
            if (!validInput(input) || output == null || output.length() > 20_000) throw new IllegalArgumentException();
            var fact = PokemonLearnsetFacts.validate(JSON.readTree(output));
            if (fact.pokemonId() != input.path("pokemonId").asLong() || !fact.moveSlug().equals(input.path("moveSlug").asText())
                    || !fact.dataReleaseId().equals(input.path("dataReleaseId").asText()) || !fact.gameReleaseId().equals(input.path("gameReleaseId").asText())) throw new IllegalArgumentException();
            String key = fact.pokemonId() + ":" + fact.moveSlug();
            if (!facts.containsKey(key) && facts.size() >= 8) throw new IllegalArgumentException();
            facts.put(key, fact);
        } catch (Exception invalid) {
            failed = true;
            try {
                var input = JSON.readTree(arguments);
                if (validInput(input)) facts.remove(input.path("pokemonId").asLong() + ":" + input.path("moveSlug").asText());
                else facts.clear();
            } catch (Exception unbound) { facts.clear(); }
        }
    }
    private static boolean validInput(JsonNode input) {
        if (input == null || !input.isObject() || input.size() != 4 || !input.path("pokemonId").isIntegralNumber() || !input.path("pokemonId").canConvertToLong()
                || !input.path("moveSlug").isTextual() || !input.path("dataReleaseId").isTextual() || !input.path("gameReleaseId").isTextual()) return false;
        try { PokemonLearnsetFacts.requireRequest(input.path("pokemonId").asLong(), input.path("moveSlug").asText(), input.path("dataReleaseId").asText(), input.path("gameReleaseId").asText()); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    public String summary(String question, Collection<PokemonFact> members) {
        if (facts.isEmpty()) return attempted || requested(question)
                ? "### 招式学习途径\n本次未取得可核验的学习途径资料；不能凭招式候选列表或模型记忆确认学习方式。\n\n" : "";
        StringBuilder text = new StringBuilder("### 招式学习途径（本次读取）\n仅展示朱／紫版本组在该公开快照中的记录。\n\n");
        for (var fact : facts.values()) {
            String name = members.stream().filter(m -> m.formId() == fact.pokemonId() && m.slug().equals(fact.pokemonSlug()))
                    .map(PokemonFact::speciesNameZh).findFirst().orElse(fact.pokemonSlug());
            text.append("**").append(EvidenceText.inline(name)).append(" · ").append(EvidenceText.inline(fact.moveSlug())).append("**\n\n");
            if (fact.entries().isEmpty()) text.append("该快照未记录此成员与招式的朱紫学习途径；不能据此断言无法学习。\n\n");
            for (var row : fact.entries()) {
                text.append("- ").append(method(row.methodKey()));
                if (row.methodKey().equals("level-up")) text.append(row.levelLearnedAt() == 0 ? "；等级字段为0，未提供具体学习操作" : "；记录等级：" + row.levelLearnedAt());
                else if (row.levelLearnedAt() > 0) text.append("；上游等级字段：").append(row.levelLearnedAt());
                text.append("。\n");
            }
            text.append("\n来源：[PokéAPI](").append(fact.sourceUrl()).append(") · 抓取于 ").append(fact.retrievedAt(), 0, 10).append("\n\n");
        }
        if (failed) text.append("部分学习途径调用未通过核验，失败结果未展示。\n\n");
        return text.append("学习方式是来源字段的中文标签，未补造TM编号、获取地点、遗传亲本、回忆或进化操作；不证明特定补丁、DLC条件、跨世代传入或赛事合法性，也未修改队伍。\n\n").toString();
    }
    private static String method(String key) {
        return switch (key) {
            case "level-up" -> "等级提升（level-up）";
            case "machine" -> "招式学习器（machine）";
            case "egg" -> "蛋招式（egg）";
            case "tutor" -> "教学（tutor）";
            default -> "上游方式：" + EvidenceText.inline(key);
        };
    }
}
