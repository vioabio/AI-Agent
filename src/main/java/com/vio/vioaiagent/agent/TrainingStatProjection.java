package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.calculator.StaticStatCalculator;
import com.vio.vioaiagent.pokemon.domain.TeamBuild;
import com.vio.vioaiagent.pokemon.team.TeamWorkflowService;
import java.time.Instant;

/** Read-only static comparison; no role inference, EV allocation or in-game-state assertion. */
public final class TrainingStatProjection {
    private TrainingStatProjection() { }
    public static String describe(TeamWorkflowService.ReadTeam read,TrainingIntentState intent){
        var heading="\n**培养参数 · 静态能力值对照**\n\n";
        try {
            TrainingPlanAnswerBlock.from(read.snapshot().team().id(),read.snapshot().team().ownerId(),read,Instant.now());
            var calculator=new StaticStatCalculator();var constraints=TrainingLevelConstraints.from(intent);
            var out=new StringBuilder(heading).append("按本次已保存等级、性格、EV/IV与同快照种族值计算；仅为静态估算，不代表游戏内实际面板。\n\n| 槽位 | 情景 | HP | 攻击 | 防御 | 特攻 | 特防 | 速度 |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n");
            boolean projected=false;
            for(int i=0;i<read.snapshot().team().members().size();i++){
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                var member=read.snapshot().team().members().get(i);var fact=read.facts().get(i);var build=member.build();
                var base=new TeamBuild.Stats(fact.hp(),fact.attack(),fact.defense(),fact.spAttack(),fact.spDefense(),fact.speed());
                row(out,member.slot(),"保存配置 Lv."+build.level(),calculator.calculate(base,build,build.level()));
                if(constraints.permitsProjectionFrom(build.level())){
                    row(out,member.slot(),"仅等级变为 "+constraints.targetLevel()+" 的假设",calculator.calculate(base,build,constraints.targetLevel()));projected=true;
                }
            }
            if(!projected)out.append("\n本次只列保存配置：目标未明确、条件冲突、练级许可/上限不满足或目标不高于保存等级时，不生成升级面板假设。\n");
            else out.append("\n目标等级行只改变等级，其余保存参数不变，不代表已升级、可立即升级或建议必须升级。\n");
            return out.append("\n不包含特性、持物、能力变化、异常状态、天气/场地等修正，不能据此判断先手或胜率；薄荷、极限特训等游戏内状态未另行核验。本次没有分配EV、提高IV或修改性格、等级与队伍。计算口径：").append(StaticStatCalculator.VERSION).append("。\n\n").toString();
        }catch(java.util.concurrent.CancellationException stop){throw stop;}
        catch(Exception unavailable){return heading+"当前保存配置、成员事实或计算范围未通过核对，本次不提供面板数值。\n\n";}
    }
    private static void row(StringBuilder out,int slot,String scenario,TeamBuild.Stats s){out.append("| ").append(slot).append(" | ").append(scenario).append(" | ").append(s.hp()).append(" | ").append(s.atk()).append(" | ").append(s.def()).append(" | ").append(s.spa()).append(" | ").append(s.spd()).append(" | ").append(s.spe()).append(" |\n");}
}
