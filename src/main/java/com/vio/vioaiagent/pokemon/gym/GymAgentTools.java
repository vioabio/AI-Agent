package com.vio.vioaiagent.pokemon.gym;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;

/** Compact model-facing schema; the HTTP APIs retain the typed, validated domain requests. */
@Service @Profile("local & !prod & !demo") @ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class GymAgentTools {
    private final GymChallengeService gyms;private final GymBattleCalculator damage;private final ObjectMapper mapper;
    public GymAgentTools(GymChallengeService gyms,GymBattleCalculator damage,ObjectMapper mapper){this.gyms=gyms;this.damage=damage;this.mapper=mapper;}
    @Tool(name="planGymChallenge",description="只读朱紫道馆分析。request对象：gameEdition=scarlet/violet，gameReleaseId=pokemon-scarlet-violet，phase=initial/rematch，gymId=katy阿枫/brassius寇沙/iono奇树/kofu海岱/larry青木/ryme莱姆/tulip莉普/grusha古鲁夏；badges=0..8，allowLeveling=false/true，availableResources文本，members=[{formId:全国或形态编号,build:{level:实际等级,moves:[英文招式slug],ability:英文特性slug,nature:英文性格}}]。progress={completedGyms:[已击败馆主id],mainStoryComplete:false/true,inspectionAssigned:false/true,requestedResources:[tm048等],ownedResources:[]}。重赛需8徽章+主线通关+接到视察任务；不得编造玩家资料。只问获取TM也用本工具，不用学习途径替代。")
    public GymChallengeService.Report planGymChallenge(@ToolParam(description="上述request对象；例如members:[{formId:445,build:{level:24,moves:[earthquake,dragon-claw],ability:rough-skin,nature:hardy}}]，需使用合法JSON字符串值。只填用户已知条件。") Map<String,Object> request){
        return gyms.analyze(parse(request,GymChallengeService.Request.class));
    }
    @Tool(name="calculateGymDamage",description="朱紫道馆单次伤害。request={challenge:与planGymChallenge相同完整对象,memberSlot:己方序号从1起,opponentSlot:馆主成员序号从1起,direction:player或npc,move:攻击方已有英文招式,npcStats:{nature:hardy,evs:{hp:0,atk:0,def:0,spa:0,spd:0,spe:0},ivs:{hp:31,atk:31,def:31,spa:31,spd:31,spe:31}},playerState:{teraActive:false,hpPercent:100,status:'',boosts:{}},npcState:{teraActive:true或false,hpPercent:100,status:'',boosts:{}},field:{weather:null,terrain:null,isGravity:false},critical:false}。NPC数值是假设而非已知事实。仅已太晶化时teraActive=true；初战奇树梦妖魔序号4、重赛序号5。不需要额外查询NPC图鉴。")
    public JsonNode calculateGymDamage(@ToolParam(description="上述request对象，challenge必须包含实际队伍、游戏、馆主、phase和进度；无法补齐时先询问，不应反复调用其他工具。") Map<String,Object> request){return damage.calculate(parse(request,GymBattleCalculator.Request.class));}
    private <T> T parse(Map<String,Object> request,Class<T> type){
        if(request==null)throw new IllegalArgumentException("缺少request对象");
        JsonNode value=mapper.valueToTree(request);
        if(type==GymChallengeService.Request.class)normalize((ObjectNode)value);else if(value.path("challenge").isObject())normalize((ObjectNode)value.path("challenge"));
        try{return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(value);}
        catch(java.io.IOException e){throw new IllegalArgumentException("道馆参数不正确，请使用工具说明中的字段、英文配置标识和明确的数字等级",e);}
    }
    private void normalize(ObjectNode value){
        // Aliases change identity spelling only; no missing player or story conditions are invented.
        String[] names={"阿枫","寇沙","奇树","海岱","青木","莱姆","莉普","古鲁夏"};
        for(int i=0;i<names.length;i++)if(value.path("gymId").asText().equals(names[i]))value.put("gymId",GymResourcePolicy.GYMS.get(i));
        if(value.path("progress").path("completedGyms").isArray())for(int j=0;j<value.path("progress").path("completedGyms").size();j++)for(int i=0;i<names.length;i++)if(value.path("progress").path("completedGyms").get(j).asText().equals(names[i]))((com.fasterxml.jackson.databind.node.ArrayNode)value.path("progress").path("completedGyms")).set(j,mapper.getNodeFactory().textNode(GymResourcePolicy.GYMS.get(i)));
    }
}
