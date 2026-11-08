package com.vio.vioaiagent.pokemon.gym;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.pokemon.domain.TeamBuild;
import com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.ai.tool.annotation.Tool;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** NPC identities and moves come from the phase-specific snapshot; hidden stats remain explicit assumptions. */
@Service @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class GymBattleCalculator {
    private final GymChallengeService gyms;
    private final LocalPokeApiRepository reference;
    private final ObjectMapper mapper;
    private final String node;
    private final Path script;
    public GymBattleCalculator(GymChallengeService gyms,LocalPokeApiRepository reference,ObjectMapper mapper,
            @Value("${vio.damage-engine.node:node}") String node,
            @Value("${vio.gym.damage-script:${user.dir}/tools/pokemon-engine/gym-battle.cjs}") String script){
        this.gyms=gyms;this.reference=reference;this.mapper=mapper;this.node=node;this.script=Path.of(script).toAbsolutePath().normalize();
    }
    public record NpcStats(String nature,TeamBuild.Stats evs,TeamBuild.Stats ivs) { }
    public record Request(GymChallengeService.Request challenge,int memberSlot,int opponentSlot,String direction,String move,
                          NpcStats npcStats,JsonNode playerState,JsonNode npcState,JsonNode field,boolean critical,Integer hits) { }
    @Tool(name="calculateGymDamage",description="计算朱紫道馆单次伤害（初战/视察重赛）。先取得玩家实际队伍/进度，challenge同planGymChallenge；memberSlot/opponentSlot从1开始；direction为player或npc。move必须是该攻击方已有招式。npcStats需明确假设性格/EV/IV，不能冒充NPC真实数值；缺省中性/0EV/31IV。playerState/npcState支持显式teraActive、hpPercent、status、boosts、abilityOn、alliesFainted；field支持weather/terrain/isGravity及attackerSide/defenderSide的墙、帮助、友情防守、顺风、守住。未建模条件会拒绝，不是多回合模拟或胜率。")
    public JsonNode calculate(Request request){
        if(request==null||request.challenge()==null)throw new IllegalArgumentException("缺少道馆和玩家条件");
        var report=gyms.analyze(request.challenge());
        if(request.memberSlot()<1||request.memberSlot()>request.challenge().members().size()||request.opponentSlot()<1||request.opponentSlot()>report.gym().path("opponents").size()
                ||!Set.of("player","npc").contains(Objects.toString(request.direction(),"")))throw new IllegalArgumentException("请选择有效成员、对手与攻击方向");
        var member=request.challenge().members().get(request.memberSlot()-1);var foe=report.gym().path("opponents").get(request.opponentSlot()-1);
        var detail=reference.detail(Long.toString(member.formId()),LocalPokeApiRepository.RELEASE);
        if(member.build().ability().isBlank()||detail.abilities().stream().noneMatch(a->id(a).equals(id(member.build().ability()))))throw new IllegalArgumentException("请先填写该宝可梦实际拥有的特性");
        var stats=request.npcStats()==null?new NpcStats("hardy",null,null):request.npcStats();
        String tera=foe.hasNonNull("teraType")?teraEnglish(foe.path("teraType").asText()):null;
        var npcBuild=new TeamBuild(mapper.convertValue(foe.path("moves"),mapper.getTypeFactory().constructCollectionType(List.class,String.class)),foe.path("ability").asText(),foe.path("item").asText(),stats.nature(),foe.path("level").asInt(),stats.evs(),stats.ivs(),tera);
        boolean player=request.direction().equals("player");
        if(request.move()==null||(player?member.build().moves():npcBuild.moves()).stream().noneMatch(m->id(m).equals(id(request.move()))))throw new IllegalArgumentException("只能计算该攻击方已配置/已核实的招式");
        ObjectNode own=mapper.createObjectNode().put("species",detail.fact().slug());own.set("build",mapper.valueToTree(member.build()));own.set("state",state(request.playerState()));
        ObjectNode npc=mapper.createObjectNode().put("species",foe.path("species").asText());npc.set("build",mapper.valueToTree(npcBuild));npc.set("state",state(request.npcState()));
        if(request.field()!=null&&!request.field().isObject())throw new IllegalArgumentException("场地条件必须是对象");
        ObjectNode field=request.field()==null?mapper.createObjectNode():request.field().deepCopy();
        String mode=report.gym().path("battleMode").asText();
        if(field.has("gameType")&&!mode.equals(field.path("gameType").asText()))throw new IllegalArgumentException("单双打条件与所选馆主不一致");
        field.put("gameType",mode);
        var payload=mapper.createObjectNode();payload.set("attacker",player?own:npc);payload.set("defender",player?npc:own);payload.put("move",request.move());payload.set("field",field);payload.put("critical",request.critical());if(request.hits()!=null)payload.put("hits",request.hits());
        Process process=null;
        try{
            byte[] input=mapper.writeValueAsBytes(payload);if(input.length>32768)throw new IllegalArgumentException("计算请求过大");
            process=new ProcessBuilder(node,script.toString()).directory(script.getParent().toFile()).start();
            try(var stream=process.getOutputStream()){stream.write(input);}
            if(!process.waitFor(8,TimeUnit.SECONDS))throw new IllegalStateException("道馆伤害计算超时");
            byte[] output=process.getInputStream().readNBytes(32769);
            if(process.exitValue()!=0||output.length>32768)throw new IllegalArgumentException("引擎拒绝未知或未支持的战斗条件；变化招式不计算直接伤害");
            var result=(ObjectNode)mapper.readTree(output);
            String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
            if(!"@smogon/calc".equals(result.path("engine").asText())||!"0.12.0".equals(result.path("engineVersion").asText())||!digest.equals(result.path("requestSha256").asText()))throw new IllegalStateException("计算版本或输入摘要不匹配");
            var base=result.path(player?"attackerBaseStats":"defenderBaseStats");var fact=detail.fact();
            int[] expected={fact.hp(),fact.attack(),fact.defense(),fact.spAttack(),fact.spDefense(),fact.speed()};String[] keys={"hp","atk","def","spa","spd","spe"};
            for(int i=0;i<keys.length;i++)if(!base.path(keys[i]).isIntegralNumber()||base.path(keys[i]).asInt()!=expected[i])throw new IllegalStateException("玩家参考种族值与引擎不一致，拒绝混用形态");
            result.put("npcStatsStatus","USER_ASSUMPTIONS_NOT_VERIFIED_NPC_STATS");result.put("sourceVersion",report.sourceVersion());result.put("sourceUrl",report.gym().path("sourceUrl").asText());
            result.put("dataReleaseId",LocalPokeApiRepository.RELEASE);result.put("calculationScope","GYM_SINGLE_ACTION_V1");
            for(String side:List.of("attacker","defender")){var combatant=(ObjectNode)result.path("assumptions").path(side);var build=combatant.path("build");for(String key:List.of("level","nature","ability","item","evs","ivs"))combatant.set(key,build.path(key));}
            result.put("assumptionsNotice","NPC性格/EV/IV为假设；未开启的太晶不会激活。按当前HP、能力变化、状态和场地计算单次伤害，不含AI行动顺序、命中率、回合末结算和胜率。");
            return result;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("计算已取消",interrupted);}
        catch(java.io.IOException unavailable){throw new IllegalStateException("无法运行道馆伤害引擎，请安装Node.js并在tools/pokemon-engine执行npm ci",unavailable);}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        finally{if(process!=null&&process.isAlive())process.destroyForcibly();}
    }
    private JsonNode state(JsonNode value){if(value==null)return mapper.createObjectNode();if(!value.isObject())throw new IllegalArgumentException("战斗状态必须是对象");return value;}
    private static String id(String value){return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");}
    private static String teraEnglish(String type){String[] zh={"一般","火","水","电","草","冰","格斗","毒","地面","飞行","超能力","虫","岩石","幽灵","龙","恶","钢","妖精"};String[] en={"normal","fire","water","electric","grass","ice","fighting","poison","ground","flying","psychic","bug","rock","ghost","dragon","dark","steel","fairy"};for(int i=0;i<zh.length;i++)if(zh[i].equals(type))return en[i];throw new IllegalStateException("未知馆主太晶属性");}
}
