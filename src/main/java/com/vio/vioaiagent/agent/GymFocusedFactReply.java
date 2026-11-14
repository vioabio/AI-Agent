package com.vio.vioaiagent.agent;

import java.util.Optional;

/** A focused NPC ace lookup, with identity/stage from the existing conversation plan. */
public final class GymFocusedFactReply {
    private GymFocusedFactReply() { }
    public static Optional<String> reply(GymTurnPlan plan,String question){
        if(plan.intent()!=GymTurnPlan.Intent.CATALOG||plan.target().isEmpty()||question==null||question.length()>300
                ||question.contains("\n")||question.contains("```")
                ||!question.matches("(?s).*(太晶成员|太晶宝可梦|王牌).*" )
                ||question.matches("(?s).*(招式|特性|伤害|为什么|为何|机制|原因|对比|比较|以及|同时|还有|全部|完整|整队配置).*" )
                ||question.matches("(?s).*(不要|不看|无需|别).{0,8}(太晶|王牌).*"))return Optional.empty();
        var target=plan.target().orElseThrow();
        if(!"pokemon-scarlet-violet".equals(plan.gameReleaseId())||!java.util.Set.of("initial","rematch").contains(target.phase())||!GymConversationContext.IDS.contains(target.gymId()))return Optional.empty();
        try{
            var catalog=GymCatalogAnswer.load(target.phase());com.fasterxml.jackson.databind.JsonNode selected=null;
            for(var gym:catalog.path("gyms"))if(target.gymId().equals(gym.path("id").asText()))selected=gym;
            if(selected==null)throw new IllegalArgumentException();
            var aces=new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            for(var row:selected.path("opponents"))if(row.hasNonNull("teraType"))aces.add(row);
            if(aces.size()!=1)throw new IllegalArgumentException();
            var ace=aces.getFirst();var types=new java.util.ArrayList<String>();ace.path("types").forEach(t->types.add(t.asText()));
            String name=ace.path("nameZh").asText(ace.path("name").asText());
            return Optional.of("### "+target.name()+" · "+(target.phase().equals("initial")?"初战":"视察重赛")+"太晶成员\n\n"
                    +"是 **"+EvidenceText.inline(name)+"**，资料等级 **"+ace.path("level").asInt()+"**；原属性 **"+String.join("／",types)+"**，太晶属性 **"+ace.path("teraType").asText()+"**。\n\n"
                    +(target.defaultStage()?"未明确阶段，先按初战资料回答。\n\n":"")
                    +(target.phase().equals("rematch")?"查看资料不代表已解锁视察重赛。\n\n":"")
                    +"来源：[馆主资料](https://www.serebii.net/scarletviolet/"+(target.phase().equals("initial")?"gyms":"gymrematches")+".shtml)，随包核对日期 "+catalog.path("reviewedAt").asText()+"。");
        }catch(Exception unavailable){return Optional.of("本次未能从已校验的目标馆主资料唯一确认太晶成员，不使用模型记忆补写。");}
    }
}
