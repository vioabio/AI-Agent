package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.pokemon.reference.*;
import java.util.*;
import java.util.regex.Pattern;

/** Detailed community notes must match both actual arguments and the current pinned source. */
public final class MechanicAnswerEvidence {
    public static final String VERSION = "bound-pinned-mechanic-notes-v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ENTITY = Pattern.compile("激流|雨盘|雪隐|诅咒之躯|冰冻光束|水之波动|torrent|rain-dish|snow-cloak|cursed-body|ice-beam|water-pulse", Pattern.CASE_INSENSITIVE);
    private final Map<String, ObjectNode> notes = new LinkedHashMap<>();
    private boolean attempted, failed;
    public static boolean requested(String question) {
        if (question == null || question.length() > 1500 || question.contains("```")) return false;
        String text = question.replaceAll("(?:不要|不需要|不用|无需|别)(?:查询|解释|介绍|说明|展示)?(?:详细机制|触发门槛|触发概率)", "");
        return text.contains("详细机制") || text.contains("触发门槛") || text.contains("触发概率")
                || ENTITY.matcher(text).find() && Pattern.compile("机制|概率|几率|触发|门槛|倍率|多少(?:血|HP)", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }
    public static String instruction(String question) {
        return requested(question) ? "\n用户询问详细机制或触发条件。工具开放时使用lookupPokemonMechanics核对固定第二来源；保留当前Run版本。不用PokéAPI短文代替未提供的门槛、倍率或概率，未收录/失败就说明缺失。社区参考和项目中文整理不是官方规则或人工验收。" : "";
    }
    public boolean attempted() { return attempted; }
    public void observe(String output, String arguments) {
        attempted = true;
        try {
            var input = JSON.readTree(arguments); validateInput(input);
            if (output == null || output.length() > 20_000) throw new IllegalArgumentException();
            ObjectNode note = IndependentMechanicCatalog.verifyCurrent(JSON.readTree(output));
            for (String field : List.of("kind", "slug", "dataReleaseId", "gameReleaseId")) if (!input.path(field).equals(note.path(field))) throw new IllegalArgumentException();
            notes.put(input.path("kind").asText() + ":" + input.path("slug").asText(), note);
        } catch (Exception invalid) {
            failed = true;
            try { var input = JSON.readTree(arguments); validateInput(input); notes.remove(input.path("kind").asText() + ":" + input.path("slug").asText()); }
            catch (Exception unbound) { notes.clear(); }
        }
    }
    public static void validateInput(JsonNode input) {
        if (input == null || !input.isObject() || input.size() != 4) throw new IllegalArgumentException();
        for (String field : List.of("kind", "slug", "dataReleaseId", "gameReleaseId")) if (!input.path(field).isTextual()) throw new IllegalArgumentException();
        IndependentMechanicFacts.requireRequest(input.path("kind").asText(), input.path("slug").asText(), input.path("dataReleaseId").asText(), input.path("gameReleaseId").asText());
    }
    public String summary(String question) {
        if (notes.isEmpty()) return attempted || requested(question) ? "### 详细机制资料\n本次未取得可核验的第二来源机制说明；不能从通用短文或模型记忆补造门槛、倍率和概率。\n\n" : "";
        StringBuilder text = new StringBuilder("### 机制补充（第二来源）\n以下为固定Pokémon Showdown第九世代文本的项目中文整理，待人工复核。\n\n");
        for (var note : notes.values()) text.append("**").append(EvidenceText.inline(note.path("nameZh").asText())).append("（")
                .append(note.path("kind").asText().equals("ability") ? "特性" : "招式").append("）**\n\n")
                .append(EvidenceText.inline(note.path("explanationZh").asText())).append("\n\n来源：[Pokémon Showdown](")
                .append(note.path("sourceUrl").asText()).append(")\n\n");
        if (failed) text.append("部分详细机制调用未通过核验，失败结果未展示。\n\n");
        return text.append("该社区快照不替代官方游戏规则、完整例外或比赛合法性审核；也不表示当前成员已选择这些特性/招式。\n\n").toString();
    }
}
