package com.vio.vioaiagent.pokemon.gym;

import com.vio.vioaiagent.pokemon.domain.TeamBuild;
import com.vio.vioaiagent.pokemon.reference.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.tool.annotation.*;
import java.util.*;

/** Read-only strategy seeds become candidates only after exact SV move/learning observations. */
@Service @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class GymTeamCoreTool {
    private static final String GAME="pokemon-scarlet-violet";
    private static final List<Long> SEEDS=List.of(197L,943L,981L);
    private static final List<String> PREFERRED=List.of("crunch","foul-play","snarl","shadow-ball","psychic","hyper-voice","dark-pulse","bite","protect","helping-hand","taunt","reflect","moonlight","light-screen","wish","rest");
    private final LocalPokeApiRepository facts; private final LocalLearnsetReferences learnsets; private final LocalBuildOptions options; private final RunToolScope scope;
    public GymTeamCoreTool(LocalPokeApiRepository facts,LocalLearnsetReferences learnsets,LocalBuildOptions options,RunToolScope scope){this.facts=facts;this.learnsets=learnsets;this.options=options;this.scope=scope;}
    public record Candidate(long formId,String name,TeamBuild configuration,String abilityLabel,List<String> moveLabels,List<String> sources) { }
    public record Recommendation(String gymId,String phase,String status,List<Candidate> candidates,List<String> sources,List<String> missing) { }
    @Tool(name="recommendGymTeamCore",description="只读检索莱姆双打的3人理论核心。必须沿用当前Run资料版本，按朱紫已收录招式及学习记录核验。只支持ryme；部分结果不代表完整六人队伍、当前可捕获、用户已拥有或必胜，不保存队伍。")
    public Recommendation recommendGymTeamCore(@ToolParam(description="馆主id；当前仅支持ryme") String gymId,@ToolParam(description="initial或rematch") String phase,@ToolParam(description="沿用当前Run dataReleaseId") String dataReleaseId){
        scope.check(dataReleaseId,GAME);
        if(!LocalPokeApiRepository.RELEASE.equals(dataReleaseId)||!Set.of("initial","rematch").contains(Objects.toString(phase,"")))throw new IllegalArgumentException("推荐仅支持朱紫参考版本及明确挑战阶段");
        var missing=new ArrayList<String>();var result=new ArrayList<Candidate>();var sources=new LinkedHashSet<String>();
        missing.add("这是待验证的3人理论核心，不是完整六人队伍；Lv.50、勤奋、EV全0、IV全31、无持物均为计算假设，不代表玩家实际配置或适合所有进度。");
        missing.add("未核验捕获与进度、招式机材料、背包、服从条件、速度线或实际伤害；未证明可通关、未保存队伍。双打仍须检查首发配合、覆盖、太晶及友军波及。");
        if(!"ryme".equals(gymId)){missing.add("该馆主尚未建立候选检索策略，不套用莱姆核心。");return new Recommendation(gymId,phase,"PARTIAL",List.of(),List.of(),List.copyOf(missing));}
        long deadline=System.nanoTime()+20_000_000_000L;
        for(long seed:SEEDS){
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline){missing.add("候选检索达到时间预算，保留已核验部分。");break;}
            try{
                var detail=facts.detail(Long.toString(seed),dataReleaseId);
                if(detail.fact().formId()!=seed||!GAME.equals(detail.fact().gameReleaseId())||!dataReleaseId.equals(detail.fact().dataReleaseId())||detail.abilities().isEmpty())throw new IllegalArgumentException("候选身份/版本/特性不完整");
                var attacks=new ArrayList<String>();var support=new ArrayList<String>();var evidence=new LinkedHashSet<String>();evidence.add(detail.fact().sourceUrl());
                for(String move:PREFERRED){
                    if(!detail.moves().contains(move))continue;
                    if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)break;
                    var params=options.lookupMove(move);if(params.typeKey()==null||params.damageClass()==null)continue;
                    boolean status=params.damageClass().equals("status");var chosen=status?support:attacks;if(chosen.size()>=2)continue;
                    var learned=learnsets.read(seed,move,dataReleaseId,GAME);
                    if(learned.pokemonId()!=seed||!Objects.equals(learned.pokemonSlug(),detail.fact().slug())
                            ||!move.equals(learned.moveSlug())||!dataReleaseId.equals(learned.dataReleaseId())
                            ||!GAME.equals(learned.gameReleaseId())||!"scarlet-violet".equals(learned.versionGroup()))continue;
                    if(!learned.entries().stream().anyMatch(e->e.methodKey().equals("machine")||e.methodKey().equals("level-up")&&e.levelLearnedAt()<=50))continue;
                    chosen.add(move);evidence.add(learned.sourceUrl());if(attacks.size()==2&&support.size()==2)break;
                }
                if(attacks.size()<2||support.size()<2){missing.add("候选 "+detail.fact().speciesNameZh()+" 未取得两攻击、两辅助的完整SV学习记录，暂不输出配置。");continue;}
                var moves=new ArrayList<>(attacks);moves.addAll(support);String ability=detail.abilities().getFirst();
                result.add(new Candidate(seed,detail.fact().speciesNameZh(),new TeamBuild(moves,ability,"","hardy",50,null,null),options.label("ability",ability),moves.stream().map(m->options.label("move",m)).toList(),List.copyOf(evidence)));sources.addAll(evidence);
            }catch(Exception unavailable){missing.add("候选编号 "+seed+" 的事实或学习记录读取失败，未用模型记忆补全。");}
        }
        return new Recommendation(gymId,phase,"PARTIAL",List.copyOf(result),List.copyOf(sources),List.copyOf(missing));
    }
}
