package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.pokemon.domain.TeamBuild;
import org.springframework.ai.tool.annotation.ToolParam;
import java.util.*;

/** Per-field reference coverage, never a simultaneous-learnability or competitive-legality certificate. */
public final class BuildCheckFacts {
    public static final String SCHEMA = "pokemon-build-check-v1";
    public static final String SCOPE = "SUPPLIED_FIELDS_AND_REFERENCE_MEMBERSHIP_NOT_JOINT_LEGALITY";
    private static final ObjectMapper JSON = new ObjectMapper();
    private BuildCheckFacts() { }
    public record Stats(@ToolParam(required=false) Integer hp, @ToolParam(required=false) Integer atk, @ToolParam(required=false) Integer def,
                        @ToolParam(required=false) Integer spa, @ToolParam(required=false) Integer spd, @ToolParam(required=false) Integer spe) {
        TeamBuild.Stats supplied() { return new TeamBuild.Stats(n(hp),n(atk),n(def),n(spa),n(spd),n(spe)); }
        private static int n(Integer value) { return value == null ? 0 : value; }
    }
    public record Build(@ToolParam(required=false) List<String> moves, @ToolParam(required=false) String ability,
                        @ToolParam(required=false) String item, @ToolParam(required=false) String nature, @ToolParam(required=false) Integer level,
                        @ToolParam(required=false) Stats evs, @ToolParam(required=false) Stats ivs) {
        public Build { if(moves!=null)moves=Collections.unmodifiableList(new ArrayList<>(moves)); }
    }
    public record Request(long pokemonId, @ToolParam(required=false) Build build, String dataReleaseId, String gameReleaseId) { }
    public record Source(long pokemonId, String slug, String nameZh, String sourceUrl, String referenceChecksum,
                         String pokemonChecksum, String speciesChecksum, List<String> abilities) { }
    public record Move(int slot, String supplied, String normalized, String status, PokemonLearnsetFacts learning) { }
    public record Report(String schemaVersion, Request request, String syntaxStatus, List<String> missingFields, String status,
                         Source source, String abilityStatus, List<Move> moves, String scope, String reportSha256) { }

    public static void requireRequest(Request r) {
        if (r == null || r.pokemonId() < 1 || r.pokemonId() > 100_000 || !"pokeapi-reference-v1".equals(r.dataReleaseId()) || !"pokemon-scarlet-violet".equals(r.gameReleaseId()))
            throw new IllegalArgumentException("配招检查仅支持有效形态编号与当前朱紫参考范围");
        if (r.build() != null) {
            var b = r.build(); if (b.moves() != null && b.moves().size() > 8) throw new IllegalArgumentException("检查请求招式列表过长");
            for (String s : List.of(b.ability() == null ? "" : b.ability(), b.item() == null ? "" : b.item(), b.nature() == null ? "" : b.nature())) if (s.length() > 80) throw new IllegalArgumentException("配置名称过长");
            if (b.moves() != null && b.moves().stream().anyMatch(s -> s != null && s.length() > 80)) throw new IllegalArgumentException("配置名称过长");
        }
    }
    public static String normalize(String s) {
        if (s == null) return "";
        String slug = s.trim().toLowerCase(Locale.ROOT).replaceAll(" +", "-");
        return slug.matches("[a-z0-9][a-z0-9-]{0,79}") ? slug : "";
    }
    public static String syntax(Build b) {
        if (b == null) return "VALID_SUPPLIED_FIELDS";
        try { new TeamBuild(b.moves(),b.ability(),b.item(),b.nature(),b.level(),b.evs()==null?null:b.evs().supplied(),b.ivs()==null?null:b.ivs().supplied());
            if(b.moves()!=null){var keys=b.moves().stream().map(BuildCheckFacts::normalize).filter(k->!k.isEmpty()).toList();if(keys.stream().distinct().count()!=keys.size())return "INVALID_INPUT";} return "VALID_SUPPLIED_FIELDS"; }
        catch (IllegalArgumentException invalid) { return "INVALID_INPUT"; }
    }
    public static List<String> missing(Build b) {
        List<String> fields = new ArrayList<>();
        if (b == null) return List.of("build");
        if (b.moves() == null || b.moves().size() != 4) fields.add("moves.fourSlots");
        if (b.ability() == null || b.ability().isBlank()) fields.add("ability");
        if (b.item() == null) fields.add("item");
        if (b.nature() == null || b.nature().isBlank()) fields.add("nature");
        if (b.level() == null) fields.add("level");
        for (var entry : Map.of("evs", b.evs() == null ? new Stats(null,null,null,null,null,null) : b.evs(), "ivs", b.ivs() == null ? new Stats(null,null,null,null,null,null) : b.ivs()).entrySet()) {
            JsonNode values = JSON.valueToTree(entry.getValue()); for (String stat : List.of("hp","atk","def","spa","spd","spe")) if (values.path(stat).isNull()) fields.add(entry.getKey()+"."+stat);
        }
        return fields.stream().sorted().toList();
    }
    public static Report seal(Report r) {
        ObjectNode node = JSON.valueToTree(r); node.put("reportSha256", digest(node)); return validate(node);
    }
    public static Report validate(JsonNode node) {
        try {
            if (!node.isObject() || node.size() != 10 || !SCHEMA.equals(node.path("schemaVersion").asText()) || !SCOPE.equals(node.path("scope").asText())
                    || !digest((ObjectNode) node).equals(node.path("reportSha256").asText()) || !node.path("moves").isArray() || node.path("moves").size() > 4) throw new IllegalArgumentException();
            parseRequest(node.path("request"));Report r = JSON.treeToValue(node, Report.class); requireRequest(r.request());
            if (!Objects.equals(syntax(r.request().build()), r.syntaxStatus()) || !missing(r.request().build()).equals(r.missingFields())) throw new IllegalArgumentException();
            if (!Set.of("REFERENCE_MATCHED","INCOMPLETE","REFERENCE_UNAVAILABLE","REFERENCE_MISMATCH","INVALID_INPUT").contains(r.status())) throw new IllegalArgumentException();
            if (r.source() != null) {
                var s = r.source(); if (s.pokemonId() != r.request().pokemonId() || !PokemonLearnsetFacts.slug(s.slug()) || s.nameZh() == null || s.nameZh().isBlank() || s.nameZh().length() > 100
                        || !Set.of("https://pokeapi.co/api/v2/pokemon/"+s.slug()+"/", "https://pokeapi.co/api/v2/pokemon/"+s.pokemonId()+"/").contains(s.sourceUrl())
                        || !s.pokemonChecksum().matches("sha256:[a-f0-9]{64}") || !s.speciesChecksum().matches("sha256:[a-f0-9]{64}")
                        || !PokeApiClient.digest(s.pokemonChecksum()+s.speciesChecksum()).equals(s.referenceChecksum()) || s.abilities() == null || s.abilities().size() > 8 || s.abilities().stream().anyMatch(a -> !PokemonLearnsetFacts.slug(a))) throw new IllegalArgumentException();
            }
            if (!abilityStatus(r.request().build(),r.source()).equals(r.abilityStatus())) throw new IllegalArgumentException();
            List<String> supplied = r.request().build()==null||r.request().build().moves()==null?List.of():r.request().build().moves();
            if (!"INVALID_INPUT".equals(r.syntaxStatus()) && r.moves().size() != supplied.size()) throw new IllegalArgumentException();
            for (int i=0;i<r.moves().size();i++) {
                var m=r.moves().get(i); if (m.slot()!=i+1 || !Objects.equals(m.supplied(),supplied.get(i)) || !normalize(m.supplied()).equals(m.normalized())) throw new IllegalArgumentException();
                if (m.learning()!=null) {
                    var l=PokemonLearnsetFacts.validate(JSON.valueToTree(m.learning()));
                    if (r.source()==null || l.pokemonId()!=r.request().pokemonId() || !l.pokemonSlug().equals(r.source().slug()) || !l.moveSlug().equals(m.normalized()) || !l.sourceChecksum().equals(r.source().pokemonChecksum())) throw new IllegalArgumentException();
                }
                if (!moveStatus(m.normalized(),r.source(),m.learning()).equals(m.status())) throw new IllegalArgumentException();
            }
            if (!outcome(r.syntaxStatus(),r.missingFields(),r.source(),r.abilityStatus(),r.moves()).equals(r.status())) throw new IllegalArgumentException();
            return r;
        } catch (Exception invalid) { throw new IllegalArgumentException("配招核对结果不完整或未通过来源核验", invalid); }
    }
    public static String abilityStatus(Build b, Source s) {
        if (b==null || b.ability()==null || b.ability().isBlank()) return "MISSING";
        String key=normalize(b.ability()); if (key.isEmpty()) return "UNRESOLVED_NAME";
        return s==null?"REFERENCE_UNAVAILABLE":s.abilities().contains(key)?"MATCHED":"NOT_IN_SNAPSHOT";
    }
    public static Request parseRequest(JsonNode node) {
        try {
            if(!node.isObject()||node.size()>4||!node.path("pokemonId").isIntegralNumber()||!node.path("pokemonId").canConvertToLong()
                    ||!node.path("dataReleaseId").isTextual()||!node.path("gameReleaseId").isTextual())throw new IllegalArgumentException();
            var b=node.path("build");if(!b.isMissingNode()&&!b.isNull()) {
                if(!b.isObject()||b.size()>7)throw new IllegalArgumentException();
                for(String f:List.of("ability","item","nature"))if(b.hasNonNull(f)&&!b.path(f).isTextual())throw new IllegalArgumentException();
                if(b.hasNonNull("level")&&(!b.path("level").isIntegralNumber()||!b.path("level").canConvertToInt()))throw new IllegalArgumentException();
                if(b.hasNonNull("moves")){if(!b.path("moves").isArray())throw new IllegalArgumentException();for(var m:b.path("moves"))if(!m.isNull()&&!m.isTextual())throw new IllegalArgumentException();}
                for(String f:List.of("evs","ivs"))if(b.hasNonNull(f)){if(!b.path(f).isObject()||b.path(f).size()>6)throw new IllegalArgumentException();for(var value:b.path(f))if(!value.isNull()&&(!value.isIntegralNumber()||!value.canConvertToInt()))throw new IllegalArgumentException();}
            }
            Request r=JSON.treeToValue(node,Request.class);requireRequest(r);return r;
        }catch(Exception invalid){throw new IllegalArgumentException("配招输入字段或类型无效",invalid);}
    }
    public static String moveStatus(String key, Source s, PokemonLearnsetFacts l) {
        return key.isEmpty()?"UNRESOLVED_NAME":s==null||l==null?"REFERENCE_UNAVAILABLE":l.entries().isEmpty()?"NOT_RECORDED":"RECORDED";
    }
    public static String outcome(String syntax, List<String> missing, Source s, String ability, List<Move> moves) {
        if (syntax.equals("INVALID_INPUT")) return "INVALID_INPUT";
        if (ability.equals("NOT_IN_SNAPSHOT") || moves.stream().anyMatch(m->m.status().equals("NOT_RECORDED"))) return "REFERENCE_MISMATCH";
        if (s==null || ability.equals("REFERENCE_UNAVAILABLE") || moves.stream().anyMatch(m->m.status().equals("REFERENCE_UNAVAILABLE"))) return "REFERENCE_UNAVAILABLE";
        if (!missing.isEmpty() || ability.equals("UNRESOLVED_NAME") || moves.stream().anyMatch(m->m.status().equals("UNRESOLVED_NAME"))) return "INCOMPLETE";
        return "REFERENCE_MATCHED";
    }
    private static JsonNode canonical(JsonNode n) {
        if (n.isObject()) { ObjectNode out=JSON.createObjectNode(); var fields=new TreeMap<String,JsonNode>(); n.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue()));fields.forEach((k,v)->out.set(k,canonical(v)));return out; }
        if (n.isArray()) { var out=JSON.createArrayNode();n.forEach(v->out.add(canonical(v)));return out; } return n;
    }
    private static String digest(ObjectNode node) { var copy=node.deepCopy();copy.remove("reportSha256");return PokeApiClient.digest(canonical(copy).toString()); }
}
