package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.reference.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Finite explicit query clauses and per-category settlement, not complete mechanism or moveset acceptance. */
public final class OverviewResourceCoverage {
    public static final String VERSION="explicit-overview-category-coverage-v1";
    public static final String ORIGIN="PROGRAM_OVERVIEW_RESOURCE";
    public static final int MAX_PROGRAM_READS=2;
    private static final Pattern QUERY=Pattern.compile("^(?:并且|同时|并|然后)?(?:请)?(?:帮我|先|再|只|仅|需要|实际)?(?:查询|查找|读取|核对)(.{1,120})$");
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Map<String,Set<String>> observed=new HashMap<>();
    private final Set<String> blocked=new HashSet<>();
    public record Request(String kind,String slug,String dataReleaseId) { }
    public static List<String> requiredKinds(String question) {
        if(question==null||question.length()>1500||question.contains("```"))return List.of();
        Set<String> found=new HashSet<>();
        for(String clause:question.strip().split("[。！？!?；;，,\\r\\n]+")) {
            var match=QUERY.matcher(clause.strip());if(!match.matches())continue;
            String text=match.group(1).split("不要|不必|无需|不用|别查",2)[0];
            if(text.contains("特性"))found.add("ability");if(text.contains("招式"))found.add("move");
            if(text.contains("性格"))found.add("nature");if(text.contains("道具"))found.add("item");
        }
        return List.of("ability","move","nature","item").stream().filter(found::contains).toList();
    }
    public void observe(String arguments) {
        try {
            var input=JSON.readTree(arguments);
            if(!input.isObject()||input.size()!=3||!"pokeapi-reference-v1".equals(input.path("dataReleaseId").asText())
                    ||!Set.of("ability","move","nature","item").contains(input.path("kind").asText())
                    ||!input.path("slug").isTextual()||!input.path("slug").asText().matches("[a-z0-9][a-z0-9-]{0,79}"))return;
            observed.computeIfAbsent(input.path("kind").asText(),k->new HashSet<>()).add(input.path("slug").asText());
        }catch(Exception invalid){/* unbound text cannot settle a requested category */}
    }
    public void unavailable(String kind){blocked.add(kind);}
    public List<Request> pending(String question,PokemonFact target,ResourceAnswerEvidence evidence) {
        List<Request> result=new ArrayList<>();
        for(String kind:requiredKinds(question))if(state(kind,target,evidence).equals("PENDING")) {
            String slug=evidence.candidates(target.formId(),kind).stream().filter(s->Known.KEYS.contains(kind+":"+s)).findFirst().orElseThrow();
            result.add(new Request(kind,slug,target.dataReleaseId()));
        }
        return List.copyOf(result);
    }
    public boolean settled(String question,PokemonFact target,ResourceAnswerEvidence evidence) {
        return requiredKinds(question).stream().noneMatch(k->Set.of("PENDING","MODEL_READ_REQUIRED","OUTSIDE_BUNDLED_REFERENCE").contains(state(k,target,evidence)));
    }
    private String state(String kind,PokemonFact target,ResourceAnswerEvidence evidence) {
        if(evidence.hasForMember(target.formId(),kind))return "VERIFIED";
        if(blocked.contains(kind))return "UNAVAILABLE";
        var candidates=evidence.candidates(target.formId(),kind);
        if(observed.getOrDefault(kind,Set.of()).stream().anyMatch(s->candidates.contains(s)||kind.equals("nature")||kind.equals("item")))return "ATTEMPTED_MISSING";
        if(!Set.of("ability","move").contains(kind))return "MODEL_READ_REQUIRED";
        if(candidates.isEmpty())return "NO_CANDIDATES";
        if(candidates.stream().noneMatch(s->Known.KEYS.contains(kind+":"+s)))return "OUTSIDE_BUNDLED_REFERENCE";
        return "PENDING";
    }
    public String summary(String question,PokemonFact target,ResourceAnswerEvidence evidence) {
        if(requiredKinds(question).isEmpty())return "";
        StringBuilder text=new StringBuilder("### 本次资料覆盖\n");
        for(String kind:requiredKinds(question)) {
            String label=switch(kind){case "ability"->"特性";case "move"->"招式";case "nature"->"性格";default->"道具";};
            String description=switch(state(kind,target,evidence)) {
                case "VERIFIED"->"已取得可核验参考；候选与已选配置分开。";
                case "ATTEMPTED_MISSING"->"未取得可校验内容，保留缺失，不由模型补造。";
                case "UNAVAILABLE"->"当前工具或可信运行范围不可用，未取得对应资料。";
                case "NO_CANDIDATES"->"本次成员资料未提供可核对候选，不能据此断言不可学习。";
                case "OUTSIDE_BUNDLED_REFERENCE"->"当前内置参考未覆盖本次候选，需要进一步来源。";
                case "MODEL_READ_REQUIRED"->"尚未取得对应参考，本次不自动代选性格或道具。";
                default->"尚未取得对应参考。";
            };
            text.append(label).append("：").append(description).append("\n\n");
        }
        return text.append("每类仅核对参考示例；类别覆盖不代表全部特性、完整四招、触发细节或赛事合法性，也不表示已选定配置。\n\n").toString();
    }
    private static final class Known {
        private static final Set<String> KEYS=load();
        private static Set<String> load() {
            try(var data=OverviewResourceCoverage.class.getResourceAsStream("/pokemon-mechanics/references-v1.json");
                var metadata=OverviewResourceCoverage.class.getResourceAsStream("/pokemon-mechanics/references-v1.meta.json")) {
                if(data==null||metadata==null)return Set.of();
                byte[] bytes=data.readNBytes(400001);if(bytes.length>400000)return Set.of();
                String text=new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n");var meta=JSON.readTree(metadata.readNBytes(16000));var root=JSON.readTree(text);
                if(!PokeApiClient.digest(text).equals(meta.path("catalogSha256").asText())||!"pokemon-mechanic-catalog-v1".equals(root.path("schemaVersion").asText())
                        ||!root.path("records").isArray()||root.path("records").size()!=meta.path("recordCount").asInt()||root.path("records").size()>200)return Set.of();
                Set<String> keys=new HashSet<>();for(var row:root.path("records")){var valid=ResourceReferenceFacts.validate(row);keys.add(valid.path("kind").asText()+":"+valid.path("slug").asText());}
                return Set.copyOf(keys);
            }catch(Exception invalid){return Set.of();}
        }
    }
}
