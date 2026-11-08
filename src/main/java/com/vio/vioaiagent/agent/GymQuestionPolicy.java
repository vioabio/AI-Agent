package com.vio.vioaiagent.agent;

import java.util.*;
import org.springframework.ai.tool.ToolCallback;

/** Domain routing and clarification, before generic learnset / guide routes can capture a gym request. */
final class GymQuestionPolicy {
    private GymQuestionPolicy() { }
    static boolean matches(String question){return question!=null&&question.matches("(?is).*(道馆|馆主|gym|阿枫|寇沙|奇树|海岱|青木|莱姆|莉普|古鲁夏).*");}
    static Optional<String> clarification(String question){
        if(!matches(question))return Optional.empty();
        if(question.matches("(?s).*(?:剑盾|剑／盾|剑/盾|绿宝石|火红|叶绿|究极之日|究极之月).*")&&!question.contains("不要混用"))return Optional.of("当前道馆模块仅支持朱／紫的初战和主线后视察重赛，不能把其他游戏的馆主资料混用。请确认具体游戏和目标馆主；其他版本暂未提供已核实的道馆分析。");
        if(question.contains("重赛")&&(question.matches("(?s).*(?:还没|没有|未|没)(?:完成|通关).{0,12}(?:主线|归乡之路).*")||question.matches("(?s).*(?:还没|没有|未)(?:接到|接).{0,12}视察.*")))
            return Optional.of("### 道馆重赛尚未解锁\n8枚道馆徽章不等于主线通关。需完成含归乡之路的主线，并在学院接到也慈的道馆视察任务后，才能挑战这轮重赛；不是每日无限重赛，也不是DLC特邀教员对战。\n\n来源：[馆主重赛规则](https://www.serebii.net/scarletviolet/gymrematches.shtml)。");
        if(!question.matches("(?is).*(?:Lv\\.?\\s*\\d+|\\d+\\s*级|\"level\"\\s*:).*")||question.matches("(?s).*第[一1个]*道馆.*")&&!question.matches("(?is).*(?:阿枫|寇沙|奇树|海岱|青木|莱姆|莉普|古鲁夏|katy|brassius|iono|kofu|larry|ryme|tulip|grusha).*"))
            return Optional.of("### 先确认你的冒险进度\n朱／紫可自由选择道馆，不能把‘第一个道馆’当作固定阵容。请补充：\n\n- 朱或紫、目标馆主，以及初战还是主线后视察重赛。\n- 已有徽章、已击败馆主；重赛还需确认主线通关与视察任务。\n- 当前拥有的成员、实际等级、招式、特性与道具。\n- 是否允许练级，以及背包和可用资源限制。\n\n收到实际配置后再读取对应资料；现在不编造馆主、你的队伍、可获得资源或伤害。");
        return Optional.empty();
    }
    static boolean wantsDamage(String question){String intent=question.split("以下全部是数据",2)[0];return intent.matches("(?is).*(?:(?:请|帮我|调用).{0,20}(?:计算|伤害工具)|算.{0,12}伤害|calculateGymDamage).*");}
    static ToolCallback[] tools(ToolCallback[] all){return Arrays.stream(all).filter(t->Set.of("planGymChallenge","calculateGymDamage","lookupPokemonReference","doTerminate").contains(t.getToolDefinition().name())).toArray(ToolCallback[]::new);}
    static String instruction(){return "\n当前是朱紫道馆任务，只调用道馆工具，不查学习途径或OU榜。先planGymChallenge取事实；只有用户明确要数值才calculateGymDamage，不额外查NPC图鉴。参数使用英文标识：粗糙皮肤rough-skin、勤奋hardy、地震earthquake、龙爪dragon-claw、火焰放射flamethrower。馆主id见工具说明，不发明地点/人名。工具结果由程序展示，不需再重复调用。";}
    static String bindDamageTarget(String question,String arguments){
        try{
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var root=mapper.readTree(arguments);var request=root.path("request");var challenge=request.path("challenge");
            String phase=challenge.path("phase").asText();if(!Set.of("initial","rematch").contains(phase))throw new IllegalArgumentException("请明确初战或视察重赛");
            String file="/pokemon-gym/sv-"+phase+"-v2";
            try(var stream=GymQuestionPolicy.class.getResourceAsStream(file+".json");var hash=GymQuestionPolicy.class.getResourceAsStream(file+".sha256")){
                String text=new String(stream.readNBytes(200001),java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n");
                if(!com.vio.vioaiagent.pokemon.reference.PokeApiClient.digest(text).equals("sha256:"+new String(hash.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip()))throw new IllegalStateException("道馆身份资料校验失败");
                var catalog=mapper.readTree(text);com.fasterxml.jackson.databind.JsonNode gym=null;String id=challenge.path("gymId").asText();
                for(var row:catalog.path("gyms"))if(row.path("id").asText().equals(id)||row.path("name").asText().equals(id))gym=row;
                if(gym==null)throw new IllegalArgumentException("请明确有效馆主");
                String intent=question.split("以下全部是数据",2)[0].toLowerCase(java.util.Locale.ROOT);var candidates=new ArrayList<Integer>();
                for(int i=0;i<gym.path("opponents").size();i++){
                    var foe=gym.path("opponents").get(i);boolean named=false;
                    for(String key:List.of("nameZh","name","species")){String name=foe.path(key).asText().toLowerCase(java.util.Locale.ROOT);if(!name.isBlank()&&affirmativeMention(intent,name))named=true;}
                    if(named)candidates.add(i+1);
                }
                if(candidates.isEmpty()&&intent.contains("王牌"))for(int i=0;i<gym.path("opponents").size();i++)if(gym.path("opponents").get(i).hasNonNull("teraType"))candidates.add(i+1);
                if(candidates.size()!=1)throw new IllegalArgumentException("请明确本次伤害计算的一位馆主宝可梦；多个目标需要分开计算");
                ((com.fasterxml.jackson.databind.node.ObjectNode)request).put("opponentSlot",candidates.getFirst());return mapper.writeValueAsString(root);
            }
        }catch(IllegalArgumentException explicit){throw explicit;}catch(Exception invalid){throw new IllegalArgumentException("无法核对伤害计算对象，请在道馆面板明确选择目标后重试",invalid);}
    }
    private static boolean affirmativeMention(String text,String name){
        for(int index=text.indexOf(name);index>=0;index=text.indexOf(name,index+name.length())){
            String prefix=text.substring(0,index);int boundary=-1;for(char separator:new char[]{'，','。','！','？',';','；','\n',','})boundary=Math.max(boundary,prefix.lastIndexOf(separator));
            String clause=prefix.substring(boundary+1);
            if(!clause.matches("(?s).*(?:不是|并非|排除|不要选择|而非)[^，。！？;；\\n,]*"))return true;
        }
        return false;
    }
}
