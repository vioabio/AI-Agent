package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.domain.TeamBuild;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.pokemon.team.TeamWorkflowService;
import java.util.*;
import java.util.function.Function;

/** Read-only ordered worklist; a learning record is never proof of resources or joint legality. */
public final class SavedTrainingRoadmap {
    private static final ObjectMapper JSON=new ObjectMapper();
    private SavedTrainingRoadmap() { }
    public static boolean requested(String question){
        return question!=null&&question.length()<=1500&&!question.contains("\n")&&!question.contains("```")
                &&!question.matches("(?s).*(引用|原文|项目说明).{0,20}(培养计划|培养路线|培养步骤).*" )
                &&!question.matches("(?s).*(不要|无需|不需要|不用|别)(?:帮我|替我|给我|为我|再|额外|制定|生成|安排|提供|给出|一份|一个|一套|新的|\\s){0,10}(培养计划|培养路线|培养步骤).*" )
                &&question.matches("(?s).*(制定|生成|安排|给出|列出|规划).{0,50}(培养计划|培养路线|培养步骤).*" );
    }
    public static TeamBuildCheckFacts.Request request(TeamWorkflowService.ReadTeam read){
        var team=read.snapshot().team();var members=new ArrayList<TeamBuildCheckFacts.Member>();
        for(var m:team.members()){
            var b=m.build();members.add(new TeamBuildCheckFacts.Member(m.slot(),m.formId(),new BuildCheckFacts.Build(b.moves(),b.ability(),b.item(),b.nature(),b.level(),stats(b.evs()),stats(b.ivs()))));
        }
        // This checker does not support tera effects. Preserve tera in the snapshot, do not claim it checked.
        return new TeamBuildCheckFacts.Request(team.dataReleaseId(),"pokemon-scarlet-violet",members);
    }
    private static BuildCheckFacts.Stats stats(TeamBuild.Stats s){return new BuildCheckFacts.Stats(s.hp(),s.atk(),s.def(),s.spa(),s.spd(),s.spe());}
    public static String create(TeamWorkflowService.ReadTeam read,Function<String,String> check){
        return create(read,check,TrainingIntentState.resolve(List.of(),null));
    }
    public static String create(TeamWorkflowService.ReadTeam read,Function<String,String> check,TrainingIntentState intent){
        return create(read,check,intent,null);
    }
    public static String create(TeamWorkflowService.ReadTeam read,Function<String,String> check,TrainingIntentState intent,Function<String,String> gymAssessment){
        var text=new StringBuilder("\n### 培养路线 · 配置核对待办\n\n");
        text.append("**步骤一 · 确认目标和进度。**\n\n").append(intent.describe()).append("\n保存等级不是推荐目标，保存值可能包含创建时默认值；实际成员等级需以游戏内状态确认。\n\n");
        text.append(TrainingTargetCheck.describe(read,intent));
        text.append(TrainingProgressCheck.describe(intent));
        text.append("**步骤二 · 补齐并确认原配置。**\n");
        for(var m:read.snapshot().team().members()){
            var gaps=new ArrayList<String>();var b=m.build();
            if(b.moves().size()<4)gaps.add("未填满四招（是否需要填满取决于目标）");if(b.ability().isBlank())gaps.add("特性未填");if(b.item().isBlank())gaps.add("道具未指定，不等于确认不携带");if(b.teraType()==null)gaps.add("太晶属性未指定");
            text.append("- 第 ").append(m.slot()).append(" 位：").append(gaps.isEmpty()?"已保存字段可供核对，仍需确认是否对应游戏内实际配置":String.join("；",gaps)).append("。\n");
        }
        text.append(TrainingStatProjection.describe(read,intent));
        text.append("\n**步骤三 · 逐项核对学习路径。**\n");
        TeamBuildCheckFacts.Report report=null;
        if(check!=null)try{
            String arguments=JSON.writeValueAsString(Map.of("request",request(read)));
            report=TeamBuildAnswerEvidence.bound(check.apply(arguments),arguments);
            for(int i=0;i<report.members().size();i++){
                var source=report.members().get(i).report().source();
                if(source!=null&&!source.referenceChecksum().equals(read.facts().get(i).checksum()))throw new IllegalArgumentException("Reference changed since saved-team read");
            }
        }catch(com.vio.vioaiagent.observability.TaskBudgetExceededException|java.util.concurrent.CancellationException stop){throw stop;}
        catch(Exception unavailable){report=null;}
        if(report==null)text.append("未取得与原配置和本次资料快照绑定的整队核对结果；暂不能列为已验证学习路径，请检查资料连接后重试。\n");
        else for(var m:report.members()){
            var r=m.report();text.append("- 第 ").append(m.slot()).append(" 位：");
            text.append(switch(r.abilityStatus()){case "MATCHED"->"特性逐项匹配参考";case "NOT_IN_SNAPSHOT"->"特性不在本次参考候选中";case "MISSING"->"特性未填";default->"特性仍待核对";}).append("。\n");
            if(r.moves().isEmpty())text.append("  - 没有已填招式，先确定目标招式，不代填。\n");
            for(var move:r.moves()){
                text.append("  - ").append(EvidenceText.inline(move.supplied())).append("：");
                if("RECORDED".equals(move.status())){
                    text.append("有本次朱紫学习记录。\n");
                    for(String note:BuildLearningNotes.describe(move,r.request().build().level()))text.append("    - ").append(note).append("\n");
                    for(String note:TrainingLearningCheck.describe(move,r.request().build().level(),intent))text.append("    - ").append(note).append("\n");
                }else text.append("NOT_RECORDED".equals(move.status())?"本次快照未记录，不能据此断言无法学习。\n":"未取得可用的同快照学习记录。\n");
            }
            if(r.source()!=null)text.append("  - 来源：[PokéAPI](").append(r.source().sourceUrl()).append(")\n");
        }
        return text.append("\n**步骤四 · 核对资源后再执行。** 学习器记录不证明已拥有学习器或已解锁制作；本次未验证这些获取条件，也未核验你的解锁、背包和游戏内实际条件。以下仅提供有来源的目录参考，不当作当前可执行路线；蛋招式等其他操作条件仍需另核对。\n\n")
                .append(SavedTrainingResources.describe(report,intent)).append('\n')
                .append("**步骤五 · 确认配置，再核验目标对局。** 先按真实对手、现有配置和场地核验；只有条件允许且确认有必要时，才另行讨论等级、性格、EV/IV或招式调整，禁止练级时不安排升级；不凭种族值判断先后手或胜率。太晶属性保留在快照中，本次逐项核对不包含太晶效果。\n\n")
                .append(SavedTrainingGymReview.describe(read,intent,gymAssessment))
                .append("这是按已保存配置生成的核对待办，不是最优培养方案；逐项参考不证明共同可得、正式赛制合法或必胜。本次未修改、保存队伍，也未生成修改审批。\n").toString();
    }
}
