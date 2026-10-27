package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.reference.*;
import java.util.*;

/** Only actual, argument-bound resource projections become readable facts. Never promotes candidates to saved builds. */
public final class ResourceAnswerEvidence {
    public static final String VERSION="bound-resource-facts-and-paraphrase-v1";
    private final ObjectMapper mapper=new ObjectMapper();
    private final Map<String,JsonNode> facts=new LinkedHashMap<>();
    private final Map<Long,LocalPokeApiRepository.Detail> members=new LinkedHashMap<>();
    public void member(LocalPokeApiRepository.Detail detail) {
        members.remove(detail.fact().formId());
        if(detail.abilities()==null||detail.moves()==null||detail.abilities().size()>8||detail.moves().size()>500
                ||java.util.stream.Stream.concat(detail.abilities().stream(),detail.moves().stream()).anyMatch(s->s==null||!s.matches("[a-z0-9][a-z0-9-]{0,79}")))return;
        members.put(detail.fact().formId(),detail);
    }
    public void retainMembers(Set<Long> ids){members.keySet().retainAll(ids);}
    public boolean observe(JsonNode output,String arguments) {
        try {
            JsonNode input=mapper.readTree(arguments);
            if(!input.isObject()||input.size()!=3)throw new IllegalArgumentException();
            for(String field:List.of("kind","slug","dataReleaseId"))if(!input.path(field).isTextual()||!input.path(field).equals(output.path(field)))throw new IllegalArgumentException();
            var verified=ResourceReferenceFacts.validate(output);String key=verified.path("kind").asText()+":"+verified.path("slug").asText();
            if(!facts.containsKey(key)&&facts.size()>=8)throw new IllegalArgumentException();facts.put(key,verified);return true;
        }catch(Exception invalid){failed(arguments);return false;}
    }
    public void failed(String arguments) {
        try {
            var input=mapper.readTree(arguments);
            if(!input.isObject()||input.size()!=3||!input.path("kind").isTextual()||!input.path("slug").isTextual()
                    ||!Set.of("ability","move","nature","item").contains(input.path("kind").asText())||!input.path("slug").asText().matches("[a-z0-9][a-z0-9-]{0,79}")
                    ||!"pokeapi-reference-v1".equals(input.path("dataReleaseId").asText()))throw new IllegalArgumentException();
            facts.remove(input.path("kind").asText()+":"+input.path("slug").asText());
        }catch(Exception invalid){facts.clear();}
    }
    public boolean hasFacts(){return !facts.isEmpty();}
    public List<String> candidates(long memberId,String kind) {
        var member=members.get(memberId);if(member==null)return List.of();
        return switch(kind){case "ability"->List.copyOf(member.abilities());case "move"->List.copyOf(member.moves());default->List.of();};
    }
    public boolean hasForMember(long memberId,String kind) {
        return facts.values().stream().anyMatch(f->kind.equals(f.path("kind").asText())
                &&(kind.equals("nature")||candidates(memberId,kind).contains(f.path("slug").asText())));
    }
    public String summary() {
        if(facts.isEmpty())return "";
        StringBuilder text=new StringBuilder("### 机制资料（本次读取）\n以下展示实际取得的参考字段；中文释义由项目编写，仍待独立复核。\n\n");
        for(var f:facts.values()) {
            String kind=f.path("kind").asText(),slug=f.path("slug").asText();
            text.append("**").append(EvidenceText.inline(f.path("nameZh").asText())).append("（").append(switch(kind){case "move"->"招式";case "ability"->"特性";default->"性格";}).append("）**\n\n");
            if(kind.equals("nature"))text.append(f.path("neutralNature").asBoolean()?"能力倾向：无提升或降低字段。\n\n":"能力倾向：提升"+stat(f.path("increasedStat").asText())+"，降低"+stat(f.path("decreasedStat").asText())+"。\n\n");
            else {
                var eligible=members.values().stream().filter(d->(kind.equals("ability")?d.abilities():d.moves()).contains(slug)).map(d->d.fact().speciesNameZh()).distinct().toList();
                text.append(eligible.isEmpty()?"关联范围：资源本身；尚未核对到当前成员的对应候选。\n\n":"资料候选："+EvidenceText.inline(String.join("、",eligible))+"；不代表已经选择或符合赛事规则。\n\n");
            }
            if(kind.equals("move"))text.append("属性：").append(type(f.path("typeKey").asText())).append(" · 分类：").append(switch(f.path("damageClass").asText()){case "physical"->"物理";case "special"->"特殊";default->"变化";})
                    .append(" · 威力：").append(value(f,"power","")).append(" · 命中：").append(value(f,"accuracy","%"))
                    .append(" · 基础PP：").append(f.path("pp").asInt()).append(" · 优先度：").append(f.path("priority").asInt()).append("\n\n");
            if(f.has("explanationZh"))text.append("项目中文释义（待复核）：").append(EvidenceText.inline(f.path("explanationZh").asText())).append("\n\n");
            else if(f.has("descriptionExcerpt"))text.append("朱紫原文节选：\n").append(EvidenceText.quote(f.path("descriptionExcerpt").asText())).append(f.path("descriptionTruncated").asBoolean()?"\n（此处为节选，未展示完整说明。）":"").append("\n\n");
            else if(!kind.equals("nature"))text.append("本次来源未提供朱紫机制短文，暂不补入其他世代说明。\n\n");
            text.append("来源：[PokéAPI](").append(f.path("sourceUrl").asText()).append(") · 抓取于 ").append(f.path("retrievedAt").asText(),0,10)
                    .append(f.has("descriptionExcerpt")?" · 朱紫短文参考":"").append("\n\n");
        }
        return text.append("字段未提供不等于0或必中；基础数值是上游参考字段，并非特定补丁冻结。短文与释义不包含完整概率、回合数、例外判定、学习途径或赛事合法性。\n\n").toString();
    }
    private String value(JsonNode f,String key,String suffix){return f.path(key).isNull()?"未提供":f.path(key).asInt()+suffix;}
    private String stat(String key){return switch(key){case "attack"->"攻击";case "defense"->"防御";case "special-attack"->"特攻";case "special-defense"->"特防";case "speed"->"速度";default->"未提供";};}
    private String type(String key){String[] keys={"normal","fire","water","electric","grass","ice","fighting","poison","ground","flying","psychic","bug","rock","ghost","dragon","dark","steel","fairy"};String[] zh={"一般","火","水","电","草","冰","格斗","毒","地面","飞行","超能力","虫","岩石","幽灵","龙","恶","钢","妖精"};return zh[Arrays.asList(keys).indexOf(key)];}
}
