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

    public static Optional<String> arguments(String question) {
        if (question == null || question.length() > 8000) return Optional.empty();
        int marker = question.indexOf("输入：");
        if (marker < 0 || marker > 250) return Optional.empty();
        String head = question.substring(0,marker).strip();
        if (!head.matches("^请调用\\s*calculateBattleDamage\\s*计算.*伤害.*")
                || Pattern.compile("保存|修改|删除|预览|读取|引用|以下资料|```|[\\r\\n]").matcher(head).find())
            return Optional.empty();
        try {
            JsonNode input = JSON.readTree(question.substring(marker + 3));
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
