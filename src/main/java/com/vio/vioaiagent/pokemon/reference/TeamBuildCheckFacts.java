package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Aggregates supplied fields only; duplicate forms are observations, never a species clause. */
public final class TeamBuildCheckFacts {
    public static final String SCHEMA="pokemon-team-build-check-v1";
    public static final String SCOPE="SUPPLIED_TEAM_MEMBER_CHECKS_NOT_JOINT_OR_TEAM_LEGALITY";
    private static final ObjectMapper JSON=new ObjectMapper();
    public static final List<String> STATUSES=List.of("REFERENCE_MATCHED","INCOMPLETE","REFERENCE_UNAVAILABLE","REFERENCE_MISMATCH","INVALID_INPUT");
    public record Member(int slot,long pokemonId,@org.springframework.ai.tool.annotation.ToolParam(required=false) BuildCheckFacts.Build build) { }
    public record Request(String dataReleaseId,String gameReleaseId,List<Member> members) {
        public Request { if(members!=null)members=List.copyOf(members); }
    }
    public record CheckedMember(int slot,BuildCheckFacts.Report report) { }
    public record Report(String schemaVersion,Request request,List<CheckedMember> members,Map<String,Integer> counts,
                         List<List<Integer>> duplicateFormSlots,String jointStatus,String scope,String reportSha256) {
        public Report { members=List.copyOf(members);counts=Collections.unmodifiableMap(new LinkedHashMap<>(counts));duplicateFormSlots=duplicateFormSlots.stream().map(List::copyOf).toList(); }
    }
    public static BuildCheckFacts.Request memberRequest(Request r,Member m){return new BuildCheckFacts.Request(m.pokemonId(),m.build(),r.dataReleaseId(),r.gameReleaseId());}
    public static void requireRequest(Request r){
        if(r==null||r.members()==null||r.members().isEmpty()||r.members().size()>6)throw new IllegalArgumentException("整队检查需要1至6位成员");
        var slots=new HashSet<Integer>();for(var m:r.members()){
            if(m==null||m.slot()<1||m.slot()>6||!slots.add(m.slot()))throw new IllegalArgumentException("队伍位置应唯一且位于1至6");
            BuildCheckFacts.requireRequest(memberRequest(r,m));
        }
    }
    public static Request parseRequest(JsonNode node){try{
        if(node==null||!node.isObject()||node.size()!=3||!node.path("dataReleaseId").isTextual()||!node.path("gameReleaseId").isTextual()||!node.path("members").isArray()||node.path("members").size()<1||node.path("members").size()>6)throw new IllegalArgumentException();
        var members=new ArrayList<Member>();for(var m:node.path("members")){
            if(!m.isObject()||m.size()<2||m.size()>3||!m.path("slot").isIntegralNumber()||!m.path("slot").canConvertToInt())throw new IllegalArgumentException();
            var one=JSON.createObjectNode();one.set("pokemonId",m.path("pokemonId"));one.set("dataReleaseId",node.path("dataReleaseId"));one.set("gameReleaseId",node.path("gameReleaseId"));one.set("build",m.has("build")?m.path("build"):JSON.nullNode());
            var parsed=BuildCheckFacts.parseRequest(one);if(m.size()!=(m.has("build")?3:2))throw new IllegalArgumentException();
            members.add(new Member(m.path("slot").asInt(),parsed.pokemonId(),parsed.build()));
        }
        var r=new Request(node.path("dataReleaseId").asText(),node.path("gameReleaseId").asText(),members);requireRequest(r);return r;
    }catch(Exception invalid){throw new IllegalArgumentException("整队核对字段、位置或参考版本无效",invalid);}}
    public static Map<String,Integer> counts(List<CheckedMember> members){var counts=new LinkedHashMap<String,Integer>();STATUSES.forEach(s->counts.put(s,0));members.forEach(m->counts.compute(m.report().status(),(k,v)->v+1));return counts;}
    public static List<List<Integer>> duplicates(Request r){var groups=new LinkedHashMap<Long,List<Integer>>();for(var m:r.members())groups.computeIfAbsent(m.pokemonId(),k->new ArrayList<>()).add(m.slot());return groups.values().stream().filter(s->s.size()>1).map(List::copyOf).toList();}
    public static Report seal(Request request,List<CheckedMember> members){
        var report=new Report(SCHEMA,request,members,counts(members),duplicates(request),"NOT_VERIFIED",SCOPE,"");
        ObjectNode node=JSON.valueToTree(report);node.put("reportSha256",hash(node));return validate(node);
    }
    public static Report validate(JsonNode node){try{
        if(node==null||!node.isObject()||node.size()!=8||!SCHEMA.equals(node.path("schemaVersion").asText())||!SCOPE.equals(node.path("scope").asText())||!"NOT_VERIFIED".equals(node.path("jointStatus").asText())||!hash((ObjectNode)node).equals(node.path("reportSha256").asText()))throw new IllegalArgumentException();
        var request=parseRequest(node.path("request"));var r=JSON.treeToValue(node,Report.class);
        if(r.members().size()!=request.members().size())throw new IllegalArgumentException();
        for(int i=0;i<r.members().size();i++){
            var member=r.members().get(i);var expected=request.members().get(i);
            if(!node.path("members").get(i).isObject()||node.path("members").get(i).size()!=2||!node.path("members").get(i).path("slot").isIntegralNumber()||member.slot()!=expected.slot())throw new IllegalArgumentException();
            var report=BuildCheckFacts.validate(node.path("members").get(i).path("report"));if(!memberRequest(request,expected).equals(report.request()))throw new IllegalArgumentException();
        }
        if(!counts(r.members()).equals(r.counts())||!duplicates(request).equals(r.duplicateFormSlots())||!canonical(JSON.valueToTree(r)).toString().equals(canonical(node).toString()))throw new IllegalArgumentException();return r;
    }catch(Exception invalid){throw new IllegalArgumentException("整队报告与成员原配置或逐项结果不一致",invalid);}}
    private static String hash(ObjectNode n){var copy=n.deepCopy();copy.remove("reportSha256");return PokeApiClient.digest(canonical(copy).toString());}
    private static JsonNode canonical(JsonNode n){if(n.isObject()){var out=JSON.createObjectNode();var fields=new TreeMap<String,JsonNode>();n.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue()));fields.forEach((k,v)->out.set(k,canonical(v)));return out;}if(n.isArray()){var out=JSON.createArrayNode();n.forEach(v->out.add(canonical(v)));return out;}return n;}
}
