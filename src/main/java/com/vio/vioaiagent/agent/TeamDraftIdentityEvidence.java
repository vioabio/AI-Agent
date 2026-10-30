package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.TeamBuildCheckFacts;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;
import java.util.regex.Pattern;

/** Per-turn identity binding for the bounded, explicitly unfilled natural-language draft. */
final class TeamDraftIdentityEvidence {
    static final String VERSION = "resolved-draft-member-identity-v1";
    private final ObjectMapper json = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<Integer, String> labels = new LinkedHashMap<>();
    private final Map<String, Identity> resolved = new HashMap<>();
    private final boolean active;
    private record Identity(long id, String data, String game) {}

    TeamDraftIdentityEvidence(String question) {
        active = TeamBuildAnswerEvidence.isUnfilledDraft(question);
        if (!active) return;
        // Finite grammar only. Never derive an ID from model memory or a test species table.
        int start = question.indexOf('第');
        String clause = question.substring(start).split("[。；\\n]", 2)[0]
                .replaceFirst("[，,]请按位置1到6返回$", "");
        var entry = Pattern.compile("第([1-6](?:、[1-6])*)位(?:是)?([\\p{IsHan}A-Za-z0-9-]{1,80})");
        for (String part : clause.split("[，,]", -1)) {
            var match = entry.matcher(part);
            if (!match.matches()) { labels.clear(); return; }
            for (String slot : match.group(1).split("、")) {
                if (labels.putIfAbsent(Integer.parseInt(slot), match.group(2)) != null) {
                    labels.clear(); return;
                }
            }
        }
    }

    boolean ready() { return !labels.isEmpty() && resolved.keySet().containsAll(labels.values()); }

    ToolCallback[] planningTools(ToolCallback[] tools) {
        if (!active || ready()) return tools;
        return Arrays.stream(tools).filter(t -> !"checkPokemonTeamBuild".equals(t.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }

    String instruction() {
        if (!active) return "";
        if (labels.isEmpty()) return "\n无法可靠解析草稿成员位置与名称，请澄清成员列表，不要猜测编号或宣称整队核对完成。";
        if (ready()) return "\n本次原名称资料查询已完成，成员位置与formId绑定："
                + String.join("；", labels.entrySet().stream().map(e -> "第" + e.getKey() + "位"
                + e.getValue() + "=" + resolved.get(e.getValue()).id()).toList())
                + "。可用checkPokemonTeamBuild核对，不必重复查询；保留用户原顺序与build:null。";
        return "\n整队核对前，先逐个以用户原名称调用lookupPokemonReference，不能猜全国编号。待核对名称："
                + String.join("、", new LinkedHashSet<>(labels.values()))
                + "。只使用本次真实资料返回的formId及当前Run版本。成员未全部解析时整队工具暂不可用。";
    }

    void observe(String tool, String output, String arguments) {
        if (!active || !"lookupPokemonReference".equals(tool)) return;
        try {
            if (output == null || output.length() > 120_000 || arguments == null || arguments.length() > 1000) return;
            var input = json.readTree(arguments);
            String query = input.path("query").asText();
            if (!labels.containsValue(query)) return;
            // A failed/new lookup invalidates the earlier binding for this same literal name.
            resolved.remove(query);
            var fact = json.readTree(output).path("fact");
            if (!fact.path("formId").isIntegralNumber() || !fact.path("formId").canConvertToLong()
                    || fact.path("formId").asLong() <= 0 || fact.path("formId").asLong() > 100_000) return;
            if (!query.equals(Long.toString(fact.path("formId").asLong()))
                    && !query.equalsIgnoreCase(fact.path("slug").asText())
                    && !query.equals(fact.path("speciesNameZh").asText())
                    && !query.equals(fact.path("formNameZh").asText())) return;
            String data = fact.path("dataReleaseId").asText(), game = fact.path("gameReleaseId").asText();
            if (!"pokeapi-reference-v1".equals(data) || !data.equals(input.path("dataReleaseId").asText())
                    || !"pokemon-scarlet-violet".equals(game)) return;
            resolved.put(query, new Identity(fact.path("formId").asLong(), data, game));
        } catch (Exception invalid) { /* Malformed tool output cannot authorize an aggregate call. */ }
    }

    void verify(String tool, String arguments) {
        if (!active || !"checkPokemonTeamBuild".equals(tool)) return;
        try {
            if (!ready()) throw new IllegalArgumentException();
            var root = json.readTree(arguments);
            if (!root.isObject() || root.size() != 1) throw new IllegalArgumentException();
            var request = TeamBuildCheckFacts.parseRequest(root.path("request"));
            if (request.members().size() != labels.size()) throw new IllegalArgumentException();
            for (var member : request.members()) {
                var identity = resolved.get(labels.get(member.slot()));
                if (identity == null || identity.id() != member.pokemonId()
                        || !identity.data().equals(request.dataReleaseId()) || !identity.game().equals(request.gameReleaseId()))
                    throw new IllegalArgumentException();
            }
            for (var member : root.path("request").path("members"))
                if (!member.has("build") || !member.path("build").isNull()) throw new IllegalArgumentException();
        } catch (Exception invalid) {
            throw new IllegalArgumentException("成员身份尚未与本次原名称资料查询绑定，请先查询原名称，保留原位置、编号、版本和未填写配置");
        }
    }
}
