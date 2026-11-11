package com.vio.vioaiagent.pokemon.gym;

import com.vio.vioaiagent.pokemon.domain.*;
import com.vio.vioaiagent.pokemon.repository.*;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.tool.annotation.*;
import java.util.*;
import java.time.Instant;

/** Owned saved builds are authoritative inputs, not reconstructions from assistant history. */
@Service @Profile("local & !prod & !demo") @ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class SavedTeamGymTool {
    private final SavedTeamRepository teams;private final PokemonFactRepository facts;private final RunRepository runs;private final GymChallengeService gyms;private final LocalBuildOptions options;
    public SavedTeamGymTool(SavedTeamRepository teams,PokemonFactRepository facts,RunRepository runs,GymChallengeService gyms,LocalBuildOptions options){this.teams=teams;this.facts=facts;this.runs=runs;this.gyms=gyms;this.options=options;}
    public record Member(int slot,String name,TeamBuild build,List<String> moveLabels,String abilityLabel,String itemLabel) { }
    public record Analysis(long teamId,String teamName,long revision,String savedDataReleaseId,String savedRulesetId,List<Member> members,GymChallengeService.Report report) { }
    @Tool(name="analyzeSavedTeamForGym",description="只读评估当前登录账号已保存队伍对朱紫馆主的现有招式覆盖。先listCurrentTeams取得用户指定队伍ID；gymId为katy/brassius/iono/kofu/larry/ryme/tulip/grusha，phase为initial/rematch。读取真实保存快照，不从聊天重建配置、不保存修改；这只是匹配评估，不声明剧情已解锁、招式合法或必胜。")
    public Analysis analyzeSavedTeamForGym(@ToolParam(description="当前账号选择的队伍ID") long teamId,@ToolParam(description="已明确的馆主id") String gymId,@ToolParam(description="initial或rematch") String phase){
        var context=ToolExecutionContextHolder.peek();if(context==null||context.userId().equals("anonymous"))throw new SecurityException("请先登录当前账号");
        var run=runs.findByIdForUser(context.runId(),context.userId()).orElseThrow(()->new SecurityException("当前任务不存在"));
        if(run.status()!=AgentRunStatus.RUNNING||!Instant.now().isBefore(run.deadlineAt())||Thread.currentThread().isInterrupted())throw new IllegalStateException("任务已停止");
        if(!"pokemon-scarlet-violet".equals(run.gameReleaseId()))throw new IllegalArgumentException("道馆分析仅支持朱／紫");
        var snapshot=teams.snapshotForOwner(teamId,run.userId());var team=snapshot.team();
        var release=facts.findDataRelease(team.dataReleaseId()).orElseThrow(()->new IllegalArgumentException("队伍保存的数据版本暂不可读取，请在队伍页核对版本"));
        if(!facts.isQueryableRelease(release)||!run.gameReleaseId().equals(release.gameReleaseId()))throw new IllegalArgumentException("队伍保存资料不属于当前游戏的可查询版本，不能混用");
        // Read-only PvE assessment can use the team's own release while preserving the Run and saved rules.
        var ordered=team.members().stream().sorted(Comparator.comparingInt(TeamMember::slot)).toList();
        var input=ordered.stream().map(m->new GymChallengeService.Member(m.formId(),m.build())).toList();
        var report=gyms.assessSavedRoster(gymId,phase,input);
        var plans=report.plan().stream().map(line->{for(int i=ordered.size()-1;i>=0;i--)line=line.replace("第"+(i+1)+"位 ","槽位"+ordered.get(i).slot()+" ");return line;}).toList();
        var matches=report.matchups().stream().map(m->new GymChallengeService.Matchup(ordered.get(m.slot()-1).slot(),m.pokemon(),m.move(),m.opponent(),m.beforeTera(),m.afterTera(),m.levelGap())).toList();
        report=new GymChallengeService.Report(report.gym(),matches,plans,report.missing(),report.sourceVersion(),null,null,report.resourceChecks());
        var members=ordered.stream().map(m->new Member(m.slot(),PokemonNameIndex.all().stream().filter(p->p.nationalNo()==m.formId()).map(PokemonNameIndex.Entry::nameZh).findFirst().orElse("形态#"+m.formId()),m.build(),m.build().moves().stream().map(move->options.label("move",move)).toList(),options.label("ability",m.build().ability()),options.label("item",m.build().item()))).toList();
        members=members.stream().map(m->new Member(m.slot(),m.name(),m.build(),m.moveLabels(),m.build().ability().isBlank()?"未明确":m.abilityLabel(),m.build().item().isBlank()?"未明确":m.itemLabel())).toList();
        return new Analysis(team.id(),team.name(),snapshot.revision(),team.dataReleaseId(),team.rulesetId(),members,report);
    }
}
