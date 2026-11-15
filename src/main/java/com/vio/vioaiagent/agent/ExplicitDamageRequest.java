package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.engine.LocalDamageEngine;
import java.util.*;
import java.util.regex.Pattern;

/** Only an explicit current-user calculation input may replace model transcription. */
public final class ExplicitDamageRequest {
    public static final String ORIGIN = "PROGRAM_BOUND_EXPLICIT_DAMAGE";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ExplicitDamageRequest() { }
    public static final String INVALID_INPUT="计算输入缺失、无效或版本不匹配。本次未计算；请补齐明确招式及双方完整配置，并核对资料版本。不会自动补选招式、改写版本或沿用历史数值。";
    private static int inputStart(String question) {
        var marker=Pattern.compile("(?:输入数据|输入)[：:]").matcher(question);
        if(marker.find())return marker.end();
        var newline=Pattern.compile("\\r?\\n(?=\\s*\\{)").matcher(question);
        if(newline.find() && question.substring(0,newline.start()).matches(
                "(?:请)?(?:使用|按|根据)以下完整输入计算[^\\r\\n{}]{0,200}伤害[^\\r\\n{}]{0,100}"))return newline.end();
        return -1;
    }
    public static boolean hasInput(String question) {
        if(question==null||question.length()>8000)return false;
        int start=inputStart(question);
        if(start<0||start>1000)return false;
        String head=question.substring(0,start);
        return head.matches("(?s).*(calculateBattleDamage|伤害|重新计算|特攻努力值).*" )&&!head.matches("(?s).*(以下为资料|以下是资料|引用以下|解释以下资料|示例文本).*" );
    }

    public static Optional<String> arguments(String question) {
        if (question == null || question.length() > 8000) return Optional.empty();
        if(!hasInput(question))return Optional.empty();
        int start=inputStart(question);
        String intent=question.substring(0,start).replaceAll("(?:不要|不)(?:修改或保存|修改|保存)(?:队伍|配置)?","");
        if(intent.matches("(?s).*(保存|修改|删除|预览|读取|引用|以下资料|```).*"))return Optional.empty();
        try {
            JsonNode input = JSON.readTree(question.substring(start));
            if(input.isObject()&&input.has("request")) {
                if(input.size()!=1||!input.path("request").isObject())return Optional.empty();
                input=input.path("request");
            }
            var request = JSON.treeToValue(input, LocalDamageEngine.Request.class);
            // Requiring a lossless typed round trip rejects missing/defaulted fields,
            // unknown keys, numeric coercions and normalized conditions.
            if (!input.equals(JSON.valueToTree(request)) || request.generation() != 9
                    || !"pokeapi-reference-v1".equals(request.dataReleaseId()) || request.field() == null
                    || request.move() == null || request.move().isBlank()) return Optional.empty();
            for (var member : List.of(request.attacker(), request.defender())) {
                if (member.species() == null || member.species().isBlank() || member.build() == null
                        || member.build().ability().isBlank() || member.build().moves().isEmpty()) return Optional.empty();
            }
            return Optional.of(JSON.writeValueAsString(Map.of("request",input)));
        } catch (Exception invalid) { return Optional.empty(); }
    }
}
