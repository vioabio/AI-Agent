package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.run.AgentRunContext;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;

/** Publishes observed candidate sets as partial theory, never as owned/acquirable or a six-member team. */
final class GymTeamRecommendationRoute {
    private static final ObjectMapper JSON=new ObjectMapper();
    private GymTeamRecommendationRoute() { }
    static Optional<String> reply(AgentRunContext context,ToolCallback[] tools,GymTurnPlan.Target target) {
        if(!target.gymId().equals("ryme"))return Optional.empty();
        var tool=Arrays.stream(tools).filter(t->t.getToolDefinition().name().equals("recommendGymTeamCore")).findFirst();
        if(tool.isEmpty())return Optional.empty();
        String unavailable="### 应对莱姆的队伍推荐\n莱姆使用双打。本次候选图鉴或学习记录未能完整读取，暂未生成配置；不会把馆主总览、海岱的队伍或使用率排名作为推荐。可先提供已保存队伍名称，我会按当前成员分析。";
        try {
            var args=JSON.createObjectNode().put("gymId",target.gymId()).put("phase",target.phase()).put("dataReleaseId",context.run().dataReleaseId());
            String output=tool.get().call(args.toString());if(output.startsWith("[tool."))return Optional.of(unavailable);
            var data=JSON.readTree(output);
            if(!data.path("gymId").asText().equals(target.gymId())||!data.path("phase").asText().equals(target.phase())
                    ||!data.path("status").asText().equals("PARTIAL")||!data.path("candidates").isArray()||data.path("candidates").size()>3)return Optional.of(unavailable);
            if(data.path("candidates").isEmpty())return Optional.of(unavailable);
            var sources=new HashSet<String>();
            if(!data.path("sources").isArray()||data.path("sources").isEmpty())return Optional.of(unavailable);
            for(var source:data.path("sources")){if(!validSource(source.asText()))return Optional.of(unavailable);sources.add(source.asText());}
            var text=new StringBuilder("### 应对莱姆").append(target.phase().equals("rematch")?"视察重赛":"初战").append("：参考核心队伍（部分建议）\n\n莱姆是双打。以下仅展示取得图鉴与朱／紫学习记录的候选，共 ").append(data.path("candidates").size()).append(" 位；不是完整六人阵容，也不代表当前地图进度可获得。\n\n| 成员 | 假设等级 / 性格 | 特性 / 持物 | 已核对招式 |\n| --- | --- | --- | --- |\n");
            for(var member:data.path("candidates")){
                if(!assumptionsMatch(member.path("configuration"))||!member.path("moveLabels").isArray()||member.path("moveLabels").size()!=4||!member.path("sources").isArray()||member.path("sources").isEmpty())return Optional.of(unavailable);
                for(var source:member.path("sources"))if(!sources.contains(source.asText()))return Optional.of(unavailable);
                for(var move:member.path("moveLabels"))if(!move.isTextual()||move.asText().isBlank())return Optional.of(unavailable);
                var moves=new ArrayList<String>();member.path("moveLabels").forEach(m->moves.add(safe(m.asText())));
                text.append("| ").append(safe(member.path("name").asText())).append(" | 50 / 勤奋 | ").append(safe(member.path("abilityLabel").asText())).append(" / 无 | ").append(String.join(" / ",moves)).append(" |\n");
            }
            text.append("\n**配置假设与使用方式：** EV 全0、IV 全31，未指定太晶，不额外假定背包持物。先从候选中选两位首发，按已列攻击招式建立输出，再按实际学到的辅助招式配合；不能把双方每回合出招、伤害或胜率当作已计算结果。\n\n**仍有缺口：**\n");
            for(var missing:data.path("missing"))text.append("- ").append(safe(missing.asText())).append('\n');
            text.append("\n来源：\n");
            for(var source:data.path("sources")){String url=source.asText();if(url.matches("https://pokeapi\\.co/api/v2/pokemon/[a-z0-9-]+/"))text.append("- [图鉴及朱／紫学习记录](").append(url).append(")\n");}
            text.append("- [莱姆馆主资料](https://www.serebii.net/scarletviolet/").append(target.phase().equals("rematch")?"gymrematches":"gyms").append(".shtml)\n\n本次只读检索，没有保存、替换或修改 test1。若要落地到你现有队伍，请说明徽章、当前成员及可用资源。");
            return Optional.of(text.toString());
        }catch(Exception invalid){return Optional.of(unavailable);}
    }
    private static boolean assumptionsMatch(com.fasterxml.jackson.databind.JsonNode build){
        if(!build.path("moves").isArray()||build.path("moves").size()!=4||build.path("level").asInt()!=50||!build.path("nature").asText().equals("hardy")||!build.path("item").asText().isEmpty()||build.hasNonNull("teraType"))return false;
        for(String stat:List.of("hp","atk","def","spa","spd","spe"))if(build.path("evs").path(stat).asInt(-1)!=0||build.path("ivs").path(stat).asInt(-1)!=31)return false;
        return true;
    }
    private static boolean validSource(String url){return url.matches("https://pokeapi\\.co/api/v2/pokemon/[a-z0-9-]+/");}
    private static String safe(String text){return text.replaceAll("[|\\r\\n<>`*\\[\\]()]"," ");}
}
