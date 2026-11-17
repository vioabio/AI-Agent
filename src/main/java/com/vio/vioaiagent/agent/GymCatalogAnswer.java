package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.reference.PokeApiClient;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reading NPC facts does not require a player's party, progress or eligibility to fight. */
final class GymCatalogAnswer {
    private GymCatalogAnswer() { }
    static boolean requestsOverview(String question) {
        return question != null && question.length() <= 800
                && question.matches("(?s).*((?:八|8)位(?:道馆)?馆主|全部(?:道馆)?馆主|所有(?:道馆)?馆主).*")
                && question.matches("(?s).*(介绍|列出|查看|查询).*(队伍|阵容).*")
                && question.matches("(?s).*(逐位|分别|每位).*(核对|确认|条件|注意).*")
                && !question.matches("(?s).*(我的|我当前|已保存|保存修改|应用修改|生成预览|推荐|计算|不要介绍|无需介绍).*" );
    }

    static Optional<String> overview(String question) {
        if (!requestsOverview(question)) return Optional.empty();
        if (question.contains("重赛") && question.contains("初战")
                && !question.matches("(?s).*(只看初战|不要重赛|不看重赛|只看重赛|不要初战|不看初战).*"))
            return Optional.of(overview("介绍八位馆主的初战队伍，逐位说明需要核对的条件。").orElseThrow()
                    + "\n\n" + overview("介绍八位馆主的视察重赛队伍，逐位说明需要核对的条件。").orElseThrow());
        String phase = question.contains("重赛") && !question.matches("(?s).*(只看初战|不要重赛|不看重赛).*") ? "rematch" : "initial";
        var roster = reply("查询八位馆主的" + (phase.equals("initial") ? "初战" : "重赛") + "队伍", "pokemon-scarlet-violet");
        if (roster.isEmpty()) return Optional.empty();
        try {
            var catalog = load(phase);
            var answer = new StringBuilder(roster.get());
            answer.append("\n\n### 逐位核对对战条件\n\n| 馆主 | 需要核对的条件 |\n| --- | --- |\n");
            for (var gym : catalog.path("gyms")) {
                var checks = new ArrayList<String>();
                checks.add("我方对" + gym.path("theme").asText() + "属性及对手其他招式的攻防覆盖");
                for (var opponent : gym.path("opponents")) {
                    if (opponent.hasNonNull("teraType"))
                        checks.add(name(opponent) + "太晶为" + opponent.path("teraType").asText() + "后的招式相性");
                    if (opponent.path("ability").asText().equals("Levitate"))
                        checks.add(name(opponent) + "的飘浮特性对地面招式的影响");
                }
                if (gym.path("battleMode").asText().equals("Doubles"))
                    checks.add("双打两位首发、队友招式影响及行动顺序");
                answer.append("| ").append(gym.path("name").asText()).append(" | ")
                        .append(String.join("，", checks)).append(" |\n");
            }
            answer.append("\n制定具体打法还需我方成员、实际等级和招式、特性、道具、徽章与获得等级、可用资源。精确伤害需补齐培养配置和场面条件，再调用计算工具。\n");
            return Optional.of(answer.toString());
        } catch (Exception unavailable) {
            return Optional.of("八位馆主的对战资料暂时无法完整核对，请稍后重试。");
        }
    }
    static Optional<String> reply(String question,String gameReleaseId){
        return reply(question,gameReleaseId,List.of());
    }
    static Optional<String> reply(String question,String gameReleaseId,List<String> displayedOrder){
        if(!GymQuestionPolicy.matches(question)||gameReleaseId!=null&&!gameReleaseId.equals("pokemon-scarlet-violet"))return Optional.empty();
        if(question.matches("(?s).*(剑盾|剑／盾|剑/盾|绿宝石|火红|叶绿|究极之日|究极之月).*"))return Optional.empty();
        String intent=GymConversationContext.requestedActions(GymConversationContext.affirmative(question.split("以下全部是数据",2)[0]));
        if(GymTurnPlan.classify(intent)!=GymTurnPlan.Intent.CATALOG)return Optional.empty();
        if(GymConversationContext.savedTeamRequest(intent))return Optional.empty();
        var ordinal=GymOrdinalReference.requestedOrdinal(intent);
        var ordinalTarget=GymOrdinalReference.resolve(intent,displayedOrder);
        if(ordinal.isPresent()&&ordinalTarget.isEmpty())return Optional.of(GymOrdinalReference.clarifyOrdinal());
        // These require the player's real conditions; merely asking for NPC information does not.
        if(intent.matches("(?is).*(伤害|计算|打法|策略|攻略|怎么打|如何打|能打|可以打|帮我打|配队|配一队|配置队伍|组队|(?:配|组|推荐).{0,4}(?:队伍|阵容)|培养|TM\\d|奖励|获取|领取|商店|请分析|帮我分析|模块|接口|API).*"))return Optional.empty();
        boolean namedFollowup=intent.matches("(?is)\\s*(?:那|说说|看看|改看)?\\s*(?:阿枫|寇沙|奇树|海岱|青木|莱姆|莉普|古鲁夏|katy|brassius|iono|kofu|larry|ryme|tulip|grusha)\\s*(?:呢|吧|[，,?？。！!])*\\s*");
        if(!namedFollowup&&!GymOrdinalReference.countQuestion(intent)&&!intent.matches("(?is).*(有哪些|哪些|了解|介绍|查询|查看|看看|列出|改问|改看|阵容|队伍|配置|太晶|成员|招式|特性|等级|资料|名单|什么|是谁|对战模式|单打|双打).*"))return Optional.empty();
        boolean namesOnly=(GymOrdinalReference.countQuestion(intent)||intent.matches("(?s).*(有哪些|哪些|名单|是谁).*"))&&!intent.matches("(?s).*(队伍|阵容|招式|等级|特性).*");
        boolean both=intent.contains("初战")&&intent.contains("重赛")&&!intent.matches("(?s).*(只看初战|不要重赛|不看重赛).*" );
        List<String> phases=both?List.of("initial","rematch"):List.of(intent.contains("重赛")&&!intent.matches("(?s).*(只看初战|不要重赛|不看重赛).*" )?"rematch":"initial");
        try{
            var text=new StringBuilder();
            for(String phase:phases){
                var catalog=load(phase);var named=new ArrayList<JsonNode>();
                String lower=intent.toLowerCase(Locale.ROOT);
                for(var gym:catalog.path("gyms"))if(ordinalTarget.isPresent()?ordinalTarget.get().equals(gym.path("id").asText()):lower.contains(gym.path("name").asText())||lower.matches("(?s).*\\b"+gym.path("id").asText()+"\\b.*"))named.add(gym);
                List<JsonNode> selected=named.isEmpty()?iterable(catalog.path("gyms")):named;
                text.append("### 朱／紫馆主资料 · ").append(phase.equals("initial")?"初战":"主线后视察重赛").append("\n");
                if(ordinalTarget.isPresent())text.append("按前文展示名单的第").append(displayedOrder.indexOf(ordinalTarget.get())+1).append("位查询；编号不是游戏固定挑战顺序。\n\n");
                if(phases.size()==1&&phase.equals("initial")&&!intent.contains("初战"))text.append("你没有指定挑战阶段，下面先列初战资料；询问重赛即可查看另一套阵容。\n\n");
                if(namesOnly&&named.isEmpty()&&ordinalTarget.isEmpty()){
                    text.append("朱／紫共有 **8 位道馆馆主**。下列编号仅供本次名单引用，不是固定挑战顺序。\n\n| 编号 | 馆主 | 擅长属性 | 模式 |\n| --- | --- | --- | --- |\n");
                    int number=0;for(var gym:selected)text.append("| ").append(++number).append(" | ").append(gym.path("name").asText()).append(" | ").append(gym.path("theme").asText()).append(" | ").append(mode(gym)).append(" |\n");
                }else if(selected.size()>1){
                    text.append("以下按参考等级列出；朱／紫可自由选择道馆，这不是固定挑战顺序，也不是强制挑战顺序。\n\n");
                    text.append("| 编号 | 馆主 | 擅长属性 | 模式 | 队伍与等级 |\n| --- | --- | --- | --- | --- |\n");
                    int number=0;
                    for(var gym:selected){var members=new ArrayList<String>();for(var foe:gym.path("opponents"))members.add(name(foe)+" Lv."+foe.path("level").asInt()+(foe.hasNonNull("teraType")?"（太晶"+foe.path("teraType").asText()+"）":""));text.append("| ").append(++number).append(" | ").append(gym.path("name").asText()).append(" | ").append(gym.path("theme").asText()).append(" | ").append(mode(gym)).append(" | ").append(String.join("、",members)).append(" |\n");}
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
    static JsonNode load(String phase)throws Exception{
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
