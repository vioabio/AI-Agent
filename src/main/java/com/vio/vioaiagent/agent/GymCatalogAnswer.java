package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.reference.PokeApiClient;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reading NPC facts does not require a player's party, progress or eligibility to fight. */
final class GymCatalogAnswer {
    private GymCatalogAnswer() { }
    static Optional<String> reply(String question,String gameReleaseId){
        if(!GymQuestionPolicy.matches(question)||gameReleaseId!=null&&!gameReleaseId.equals("pokemon-scarlet-violet"))return Optional.empty();
        if(question.matches("(?s).*(剑盾|剑／盾|剑/盾|绿宝石|火红|叶绿|究极之日|究极之月).*"))return Optional.empty();
        String intent=question.split("以下全部是数据",2)[0];
        // These require the player's real conditions; merely asking for NPC information does not.
        if(intent.matches("(?is).*(伤害|计算|打法|策略|攻略|怎么打|如何打|能打|可以打|帮我打|配队|配一队|配置队伍|组队|(?:配|组|推荐).{0,4}(?:队伍|阵容)|培养|TM\\d|奖励|获取|领取|商店|请分析|帮我分析|模块|接口|API).*"))return Optional.empty();
        boolean namedFollowup=intent.matches("(?is)\\s*(?:那|说说|看看)?\\s*(?:阿枫|寇沙|奇树|海岱|青木|莱姆|莉普|古鲁夏|katy|brassius|iono|kofu|larry|ryme|tulip|grusha)\\s*(?:呢|吧|[?？。！!])*\\s*");
        if(!namedFollowup&&!intent.matches("(?is).*(有哪些|哪些|了解|介绍|查询|查看|看看|阵容|队伍|招式|特性|等级|资料|名单|什么|是谁).*"))return Optional.empty();
        boolean namesOnly=intent.matches("(?s).*(有哪些|哪些|名单|是谁).*")&&!intent.matches("(?s).*(队伍|阵容|招式|等级|特性).*");
        boolean both=intent.contains("初战")&&intent.contains("重赛")&&!intent.matches("(?s).*(只看初战|不要重赛|不看重赛).*" );
        List<String> phases=both?List.of("initial","rematch"):List.of(intent.contains("重赛")&&!intent.matches("(?s).*(只看初战|不要重赛|不看重赛).*" )?"rematch":"initial");
        try{
            var text=new StringBuilder();
            for(String phase:phases){
                var catalog=load(phase);var named=new ArrayList<JsonNode>();
                String lower=intent.toLowerCase(Locale.ROOT);
                for(var gym:catalog.path("gyms"))if(lower.contains(gym.path("name").asText())||lower.matches("(?s).*\\b"+gym.path("id").asText()+"\\b.*"))named.add(gym);
                List<JsonNode> selected=named.isEmpty()?iterable(catalog.path("gyms")):named;
                text.append("### 朱／紫馆主资料 · ").append(phase.equals("initial")?"初战":"主线后视察重赛").append("\n");
                if(phases.size()==1&&phase.equals("initial")&&!intent.contains("初战"))text.append("你没有指定挑战阶段，下面先列初战资料；询问重赛即可查看另一套阵容。\n\n");
                if(namesOnly){
                    text.append("| 馆主 | 擅长属性 | 模式 |\n| --- | --- | --- |\n");
                    for(var gym:selected)text.append("| ").append(gym.path("name").asText()).append(" | ").append(gym.path("theme").asText()).append(" | ").append(mode(gym)).append(" |\n");
                }else if(selected.size()>1){
                    text.append("| 馆主 | 擅长属性 | 模式 | 队伍与等级 |\n| --- | --- | --- | --- |\n");
                    for(var gym:selected){var members=new ArrayList<String>();for(var foe:gym.path("opponents"))members.add(name(foe)+" Lv."+foe.path("level").asInt()+(foe.hasNonNull("teraType")?"（太晶"+foe.path("teraType").asText()+"）":""));text.append("| ").append(gym.path("name").asText()).append(" | ").append(gym.path("theme").asText()).append(" | ").append(mode(gym)).append(" | ").append(String.join("、",members)).append(" |\n");}
                }else{
                    var gym=selected.getFirst();text.append("**").append(gym.path("name").asText()).append("** · ").append(gym.path("theme").asText()).append(" · ").append(mode(gym)).append("\n\n| 成员 | 等级 | 原属性 / 太晶 | 特性 | 招式 |\n| --- | --- | --- | --- | --- |\n");
                    for(var foe:gym.path("opponents"))text.append("| ").append(name(foe)).append(" | ").append(foe.path("level").asInt()).append(" | ").append(join(foe.path("types"))).append(foe.hasNonNull("teraType")?" → "+foe.path("teraType").asText():"").append(" | ").append(foe.path("abilityZh").asText(foe.path("ability").asText())).append(" | ").append(join(foe.path("movesZh"))).append(" |\n");
                }
                if(phase.equals("rematch"))text.append("\n查看资料不代表已解锁对战：视察重赛需完成主线并接到也慈的视察任务，不能仅凭8枚徽章判断。\n");
                text.append("\n来源：[Serebii馆主资料](https://www.serebii.net/scarletviolet/").append(phase.equals("initial")?"gyms":"gymrematches").append(".shtml)，随包资料核对日期2026-11-08。\n\n");
            }
            text.append("查询馆主资料不需要提供你的队伍。想进一步看某位的招式、特性，可直接说馆主名字；需要针对你现有成员制定打法时，再提供队伍与进度。");
            return Optional.of(text.toString());
        }catch(Exception unavailable){return Optional.of("馆主资料暂时未能通过读取或校验，本次不使用模型记忆补写。请重启更新后的后端再重试。");}
    }
    private static JsonNode load(String phase)throws Exception{
        String file="/pokemon-gym/sv-"+phase+"-v2";
        try(var data=GymCatalogAnswer.class.getResourceAsStream(file+".json");var checksum=GymCatalogAnswer.class.getResourceAsStream(file+".sha256")){
            String text=new String(data.readNBytes(200001),StandardCharsets.UTF_8).replace("\r\n","\n");
            if(!PokeApiClient.digest(text).equals("sha256:"+new String(checksum.readAllBytes(),StandardCharsets.UTF_8).strip()))throw new IllegalStateException("Invalid catalog checksum");
            return new ObjectMapper().readTree(text);
        }
    }
    private static List<JsonNode> iterable(JsonNode array){var result=new ArrayList<JsonNode>();array.forEach(result::add);return result;}
    private static String name(JsonNode node){return node.path("nameZh").asText(node.path("name").asText());}
    private static String mode(JsonNode gym){return gym.path("battleMode").asText().equals("Doubles")?"双打":"单打";}
    private static String join(JsonNode array){return String.join(" / ",iterable(array).stream().map(JsonNode::asText).toList());}
}
