package com.vio.vioaiagent.pokemon.gym;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.domain.TeamBuild;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.pokemon.calculator.TypeEffectivenessCalculator;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;

@Service @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class GymChallengeService {
    private final LocalBuildOptions options;
    private final JsonNode catalog;
    private final JsonNode rematches;
    private static final Map<String,String> TYPES=Map.ofEntries(Map.entry("normal","一般"),Map.entry("fire","火"),Map.entry("water","水"),Map.entry("electric","电"),Map.entry("grass","草"),Map.entry("ice","冰"),Map.entry("fighting","格斗"),Map.entry("poison","毒"),Map.entry("ground","地面"),Map.entry("flying","飞行"),Map.entry("psychic","超能力"),Map.entry("bug","虫"),Map.entry("rock","岩石"),Map.entry("ghost","幽灵"),Map.entry("dragon","龙"),Map.entry("dark","恶"),Map.entry("steel","钢"),Map.entry("fairy","妖精"));
    public GymChallengeService(LocalBuildOptions options,ObjectMapper mapper){
        this.options=options;
        catalog=load(mapper,"initial");rematches=load(mapper,"rematch");
    }
    private JsonNode load(ObjectMapper mapper,String phase){
        try(var input=getClass().getResourceAsStream("/pokemon-gym/sv-"+phase+"-v2.json");var hash=getClass().getResourceAsStream("/pokemon-gym/sv-"+phase+"-v2.sha256")){
            String text=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n");
            if(!PokeApiClient.digest(text).equals("sha256:"+new String(hash.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip()))throw new IllegalStateException("馆主资料校验失败");
            return mapper.readTree(text);
        }catch(Exception error){throw new IllegalStateException("馆主资料未能加载",error);}
    }
    public JsonNode catalog(){return catalog.deepCopy();}
    public JsonNode catalog(String phase){if(!Set.of("initial","rematch").contains(phase))throw new IllegalArgumentException("未知挑战阶段");return (phase.equals("rematch")?rematches:catalog).deepCopy();}
    public JsonNode gym(String phase,String id){for(var row:catalog(phase).path("gyms"))if(row.path("id").asText().equals(id))return row;throw new IllegalArgumentException("未识别馆主");}
    public record Member(long formId,TeamBuild build){}
    public record Progress(List<String> completedGyms,boolean mainStoryComplete,boolean inspectionAssigned,List<String> requestedResources,List<String> ownedResources){
        public Progress {completedGyms=bounded(completedGyms);requestedResources=bounded(requestedResources);ownedResources=bounded(ownedResources);}
        private static List<String> bounded(List<String> values){if(values==null)return List.of();if(values.size()>50||values.stream().anyMatch(v->v==null||v.length()>80))throw new IllegalArgumentException("进度或资源条件过大");return List.copyOf(values);}
    }
    public record Request(String gameEdition,String gameReleaseId,String phase,String gymId,int badges,boolean allowLeveling,String availableResources,List<Member> members,Progress progress){
        public Request(String edition,String release,String phase,String gym,int badges,boolean leveling,String resources,List<Member> members){this(edition,release,phase,gym,badges,leveling,resources,members,null);}
    }
    public record Matchup(int slot,String pokemon,String move,String opponent,double beforeTera,double afterTera,int levelGap){}
    public record Report(JsonNode gym,List<Matchup> matchups,List<String> plan,List<String> missing,String sourceVersion,Integer badges,String gameEdition,List<GymResourcePolicy.Check> resourceChecks){}
    @org.springframework.ai.tool.annotation.Tool(name="planGymChallenge",description="只读分析朱/紫初战或主线后视察重赛。必须先向玩家确认朱或紫、馆主、初次挑战、已有徽章、是否允许练级，以及当前拥有的成员实际等级与招式，不得编造玩家配置。返回已核对的馆主初战阵容、当前招式属性覆盖、等级差和资料缺口；不查OU热门榜、不自动保存或保证获胜。重赛需progress明确通关并接到视察任务。")
    public Report analyze(@org.springframework.ai.tool.annotation.ToolParam(description="玩家明确提供的游戏、初次挑战阶段、馆主及当前队伍条件") Request request){
        if(request==null||!Set.of("scarlet","violet").contains(Objects.toString(request.gameEdition(),""))||!"pokemon-scarlet-violet".equals(request.gameReleaseId())||!Set.of("initial","rematch").contains(Objects.toString(request.phase(),""))||request.badges()<0||request.badges()>8||request.members()==null||request.members().isEmpty()||request.members().size()>6||Objects.toString(request.availableResources(),"").length()>1000)throw new IllegalArgumentException("请选择朱或紫、初战或重赛、0–8枚徽章及1–6位成员");
        var progress=request.progress()==null?new Progress(null,false,false,null,null):request.progress();
        GymResourcePolicy.validateProgress(progress,request.badges());
        if(request.phase().equals("rematch")&&(!progress.mainStoryComplete()||!progress.inspectionAssigned()||request.badges()!=8))throw new IllegalArgumentException("重赛需已通关主线、8枚道馆徽章并接到也慈的道馆视察任务；仅有8枚徽章不代表已解锁");
        JsonNode gym=gym(request.phase(),request.gymId());
        return evaluate(gym,request.members(),request.allowLeveling(),request.phase(),request.badges(),request.gameEdition(),GymResourcePolicy.check(progress));
    }
    /** A theoretical, read-only matchup does not assert that the player has unlocked the battle. */
    public Report assessSavedRoster(String gymId,String phase,List<Member> members){
        if(members==null||members.isEmpty()||members.size()>6)throw new IllegalArgumentException("请选择1–6位已保存成员");
        return evaluate(gym(phase,gymId),members,false,phase,null,null,List.of());
    }
    private Report evaluate(JsonNode gym,List<Member> members,boolean allowLeveling,String phase,Integer badges,String edition,List<GymResourcePolicy.Check> resourceChecks){
        var results=new ArrayList<Matchup>();var missing=new ArrayList<String>();var plan=new ArrayList<String>();var ids=new HashSet<Long>();
        int aceLevel=gym.path("opponents").get(gym.path("opponents").size()-1).path("level").asInt();
        for(int i=0;i<members.size();i++){
            Member member=members.get(i);
            if(member==null||member.formId()<1||member.formId()>20000||member.build()==null||!ids.add(member.formId()))throw new IllegalArgumentException("成员必须有明确等级/招式配置，且形态编号不可重复");
            String name=PokemonNameIndex.all().stream().filter(row->row.nationalNo()==member.formId()).map(PokemonNameIndex.Entry::nameZh).findFirst().orElse("形态编号"+member.formId());
            if(member.build().moves().isEmpty())missing.add(name+"尚未填写当前招式，不能仅按自身属性判定进攻覆盖");
            if(member.build().level()<aceLevel)plan.add(name+" Lv."+member.build().level()+"低于王牌 Lv."+aceLevel+"；"+(allowLeveling?"可先考虑练级再挑战":"本次不假定可以练级，需保留换人和失败备选"));
            for(String move:member.build().moves()){
                var known=options.lookupMove(move.toLowerCase(Locale.ROOT).replace(' ','-'));
                if(known.typeKey()==null||!TYPES.containsKey(known.typeKey())||known.damageClass()==null){missing.add("招式 "+move+" 参数未取得");continue;}
                if("status".equals(known.damageClass()))continue;
                for(var foe:gym.path("opponents")){
                    List<String> defending=new ArrayList<>();foe.path("types").forEach(t->defending.add(t.asText()));
                    double before=effectiveness(TYPES.get(known.typeKey()),defending,foe.path("ability").asText());
                    double after=foe.hasNonNull("teraType")?effectiveness(TYPES.get(known.typeKey()),List.of(foe.path("teraType").asText()),foe.path("ability").asText()):before;
                    results.add(new Matchup(i+1,name,known.nameZh(),foe.path("name").asText(),before,after,member.build().level()-foe.path("level").asInt()));
                }
            }
        }
        for(var foe:gym.path("opponents")){
            var best=results.stream().filter(r->r.opponent().equals(foe.path("name").asText())&&r.afterTera()>0).max(Comparator.comparingDouble(Matchup::afterTera).thenComparingInt(Matchup::levelGap));
            if(best.isPresent()){var b=best.get();plan.add("对 "+b.opponent()+" 可优先考虑第"+b.slot()+"位 "+b.pokemon()+" 的 "+b.move()+"（"+b.afterTera()+"倍属性效果），仅为当前招式中的覆盖候选，不是伤害或先手保证");}
            else missing.add("对 "+foe.path("name").asText()+" 尚无可评估的已填攻击招式");
        }
        if(gym.path("id").asText().equals("iono"))plan.add("关键威胁：梦妖魔电太晶后仍有飘浮，地面攻击无效；不要按电属性弱地面直接安排收尾");
        if(gym.path("id").asText().equals("ryme"))plan.add("这是双打；需安排两位首发、注意群体招式与友军波及，以及现场观众带来的能力变化");
        missing.add("上述倍数只是属性覆盖；请使用下方单次伤害计算核对特性、太晶、能力变化及场地。不是多回合模拟或胜率");
        missing.add("馆主等级、招式、特性、持物已收录；性格、EV、IV及实时HP未核实，伤害计算必须明确假设，不代表NPC真实数值");
        missing.add("当前捕获/获得等级未提供，不能仅按现等级断言服从。资源只检查已收录途径；解锁途径不等于背包已有、可学习或有足够制作材料");
        return new Report(gym.deepCopy(),List.copyOf(results),List.copyOf(plan),List.copyOf(missing),"sv-"+phase+"-v2 / 核对2026-11-08",badges,edition,resourceChecks);
    }
    static double effectiveness(String attack,List<String> defending,String ability){if(attack.equals("地面")&&ability.equalsIgnoreCase("levitate"))return 0;return new TypeEffectivenessCalculator().calculate(attack,defending.toArray(String[]::new));}
}
