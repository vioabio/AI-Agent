package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.team.TeamWorkflowService;
import java.util.*;
import java.util.regex.Pattern;

/** NPC reference and declared level arithmetic, never an optimal target, unlock or battle verdict. */
public final class TrainingTargetCheck {
    private static final Pattern GYM=Pattern.compile("("+String.join("|",GymConversationContext.NAMES)+")(初战|重赛)");
    private TrainingTargetCheck() { }
    public static String describe(TeamWorkflowService.ReadTeam read,TrainingIntentState intent){
        var out=new StringBuilder("\n**目标与等级差距核对**\n\n");
        if(intent.conflicted())return out.append("条件仍有冲突，暂不推导目标资料和等级差距，请先明确更新冲突条件。\n\n").toString();
        out.append(gymReference(intent.declared("目标").orElse("")));
        String raw=intent.declared("目标等级").orElse("");
        if(raw.isEmpty())return out.append("目标等级尚未声明，本次不从对手等级或保存值推导推荐等级；需要数值对照时可填写“目标等级：55”。\n\n").toString();
        var constraints=TrainingLevelConstraints.from(intent);
        if(constraints.targetLevel()==null)return out.append("目标等级需明确为1至100的整数，未生成等级差距或练级步骤。\n\n").toString();
        int target=constraints.targetLevel();
        if(constraints.permission()==TrainingLevelConstraints.Permission.PROHIBITED)return out.append("已声明不允许练级，本次不安排升级；请核对现有配置是否对应目标，必要时另选成员或明确更新练级条件。\n\n").toString();
        if(constraints.permission()!=TrainingLevelConstraints.Permission.ALLOWED)return out.append("练级条件尚未明确为允许/不允许，或“允许到60级”这类上限；暂不生成训练差距。\n\n").toString();
        if(constraints.cap()!=null&&target>constraints.cap())return out.append("目标等级高于已声明练级上限，条件不一致，未生成升级步骤；请明确更新目标或上限。\n\n").toString();
        for(var member:read.snapshot().team().members()){
            int level=member.build().level();out.append("- 第 ").append(member.slot()).append(" 位：保存等级 ").append(level).append("，声明目标 ").append(target).append("；");
            if(target>level)out.append("数值相差 ").append(target-level).append(" 级。该差额只比较保存值，不是实测所需练级量或经验值。");
            else if(target==level)out.append("保存等级与声明目标相同，不据此额外安排升级。");
            else out.append("目标低于保存等级，先核对记录或成员选择，不生成降级步骤；若是赛事等级缩放，应另核对对应规则。");
            out.append('\n');
        }
        return out.append("\n这只是用户目标与保存配置的数值对照，不证明成员达到该目标即可获胜；实际等级、训练资源、经验需求和对局伤害仍待核对。本次不调整等级、性格、EV/IV或队伍。\n\n").toString();
    }
    private static String gymReference(String target){
        var match=GYM.matcher(target.replaceAll("\\s+",""));
        if(!match.matches())return "目标尚未对应到本批资料中的明确馆主及初战/重赛阶段，不从名称或顺序猜测对手等级。\n\n";
        String phase=match.group(2).equals("初战")?"initial":"rematch";
        try{
            var catalog=GymCatalogAnswer.load(phase);
            if(catalog.path("schemaVersion").asInt()!=2||!"pokemon-scarlet-violet".equals(catalog.path("gameReleaseId").asText())||!phase.equals(catalog.path("phase").asText()))throw new IllegalArgumentException();
            var selected=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();for(var gym:catalog.path("gyms"))if(match.group(1).equals(gym.path("name").asText()))selected.add(gym);
            if(selected.size()!=1)throw new IllegalArgumentException();
            var rows=selected.getFirst().path("opponents");if(!rows.isArray()||rows.isEmpty()||rows.size()>6)throw new IllegalArgumentException();
            int min=100,max=1;for(var row:rows){var n=row.path("level");if(!n.isIntegralNumber()||!n.canConvertToInt()||n.asInt()<1||n.asInt()>100)throw new IllegalArgumentException();min=Math.min(min,n.asInt());max=Math.max(max,n.asInt());}
            String date=java.time.LocalDate.parse(catalog.path("reviewedAt").asText()).toString();
            String url="https://www.serebii.net/scarletviolet/"+(phase.equals("initial")?"gyms":"gymrematches")+".shtml";
            return "目标资料参考："+match.group(1)+" · "+match.group(2)+"，对手等级范围 "+min+"–"+max+"（资料事实，不是推荐目标等级）。\n\n来源：[馆主资料]("+url+")，随包核对日期 "+date+"。"+(phase.equals("rematch")?"重赛实际解锁条件未核验，不能仅凭徽章数认定可以挑战。":"查看资料不代表已核验当前可挑战条件。")+"\n\n";
        }catch(Exception unavailable){return "目标馆主资料未能通过读取或校验，本次不从模型记忆补写对手等级。\n\n";}
    }
}
