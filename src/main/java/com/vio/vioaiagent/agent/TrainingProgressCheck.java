package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.gym.*;
import java.util.*;

/** Explicit player declarations checked against existing PvE rules, never game-save verification. */
public final class TrainingProgressCheck {
    private TrainingProgressCheck() { }
    public static String describe(TrainingIntentState intent){
        String heading="\n**进度与获取前提 · 用户声明核对**\n\n";
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
        if(intent.conflicted())return heading+"声明条件有冲突，暂不判断重赛前提或学习器获取路线。\n\n";
        try{
            Integer badges=null;String raw=intent.declared("徽章").orElse("");
            if(!raw.isEmpty()){if(!raw.matches("[0-8](?:枚)?"))throw new IllegalArgumentException("徽章需为0至8的整数");badges=Integer.valueOf(raw.replace("枚",""));}
            String progress=intent.declared("进度").orElse("");
            if(badges==null&&progress.matches("[0-8]枚徽章"))badges=Integer.valueOf(progress.substring(0,1));
            var completed=gyms(intent.declared("已击败馆主").orElse(""));
            Boolean story=flag(intent.declared("主线通关").orElse("")),inspection=flag(intent.declared("视察任务").orElse(""));
            var requested=machines(intent.declared("所需学习器").orElse(""));
            var owned=machines(intent.declared("已有学习器").orElse(""));
            if(badges!=null&&completed.size()>badges)throw new IllegalArgumentException("已击败馆主数量多于声明徽章数");
            if(badges!=null&&badges>0&&"无".equals(intent.declared("已击败馆主").orElse("")))throw new IllegalArgumentException("声明未击败任何馆主却已有徽章");
            if(Boolean.TRUE.equals(story)&&badges!=null&&badges!=8)throw new IllegalArgumentException("主线通关声明与徽章数矛盾");
            if(Boolean.TRUE.equals(inspection)&&badges!=null&&badges!=8)throw new IllegalArgumentException("视察任务声明与徽章数矛盾");
            if(Boolean.TRUE.equals(inspection)&&Boolean.FALSE.equals(story))throw new IllegalArgumentException("视察任务声明与主线未通关矛盾");
            if(badges!=null&&progress.matches("[0-8]枚徽章")&&badges!=Integer.parseInt(progress.substring(0,1)))throw new IllegalArgumentException("进度文字与徽章字段矛盾");
            var out=new StringBuilder(heading).append("以下仅核对本会话明确声明，不是读取游戏存档，也不代表已领取奖励或背包仍有物品。\n\n");
            if(intent.declared("目标").orElse("").matches("(?:"+String.join("|",GymConversationContext.NAMES)+")重赛")){
                var missing=new ArrayList<String>();
                if(badges==null)missing.add("徽章");if(story==null)missing.add("主线通关");if(inspection==null)missing.add("视察任务");
                if(badges!=null&&badges<8||Boolean.FALSE.equals(story)||Boolean.FALSE.equals(inspection))out.append("重赛前提：按当前声明尚不满足8枚徽章、主线通关及接到视察任务，不能将目标当作当前可挑战。\n");
                else if(!missing.isEmpty())out.append("重赛前提尚缺明确声明：").append(String.join("、",missing)).append("；不会仅凭目标或8枚徽章推断已解锁。\n");
                else out.append("按声明重赛门槛齐备，但游戏内实际进度尚未核验，不认定已解锁或保证可以挑战。\n");
            }else out.append("本次不推断剧情已解锁，也不由徽章数猜测已击败的具体馆主。\n");
            if(requested.isEmpty())out.append("\n未明确所需学习器；如需逐项检查，可填写“所需学习器：tm048,tm114”。\n");
            else {
                var declared=new GymChallengeService.Progress(completed,Boolean.TRUE.equals(story),Boolean.TRUE.equals(inspection),requested,owned);
                for(var check:GymResourcePolicy.check(declared)){
                    out.append("\n- ").append(check.id().toUpperCase(Locale.ROOT)).append("：");
                    out.append(switch(check.status()){
                        case "OWNED_DECLARED"->"用户声明已有，未核验编号、数量、是否已消耗或成员能否学习。";
                        case "ROUTE_UNLOCKED"->"按声明已击败对应馆主，首胜奖励前提符合；未证明已领取、仍持有、可再次领取或已解锁制作。";
                        case "ROUTE_NOT_CONFIRMED"->"未声明击败对应馆主，首胜奖励前提尚未确认；不排除其他未核验途径。";
                        default->"固定规则未收录其获取途径，不能推断可获得或无法获得。";
                    });
                    if(check.sourceUrl()!=null)out.append("[规则来源](").append(check.sourceUrl()).append(")");
                }
            }
            return out.append("\n\n已有学习器声明不扣减条件式制作预算；数量、实际需求、成员可学习性和制作材料仍须另行核对。本次未获取资源、改变进度或修改队伍。\n\n").toString();
        }catch(IllegalArgumentException invalid){return heading+"声明字段无效或互相矛盾："+invalid.getMessage()+"；请明确更新后再核对，不生成已解锁或已有资源结论。\n\n";}
    }
    private static Boolean flag(String text){return switch(text){case ""->null;case "是"->true;case "否"->false;default->throw new IllegalArgumentException("主线通关/视察任务需明确填写是或否");};}
    private static List<String> gyms(String text){
        if(text.isEmpty()||text.equals("无"))return List.of();var result=new ArrayList<String>();
        for(String token:text.split("[,，、]",-1)){
            String name=token.strip();int index=GymConversationContext.NAMES.indexOf(name);
            String id=index<0?name:GymConversationContext.IDS.get(index);
            if(!GymResourcePolicy.GYMS.contains(id)||result.contains(id)||result.size()>=8)throw new IllegalArgumentException("已击败馆主需为不重复的明确名称或馆主ID");result.add(id);
        }return List.copyOf(result);
    }
    private static List<String> machines(String text){
        if(text.isEmpty()||text.equals("无"))return List.of();var result=new ArrayList<String>();
        for(String token:text.split("[,，、]",-1)){
            String id=token.strip().toLowerCase(Locale.ROOT);
            if(!id.matches("tm[0-9]{3}")||result.contains(id)||result.size()>=8)throw new IllegalArgumentException("学习器需为不重复的TM三位编号，最多8项");result.add(id);
        }return List.copyOf(result);
    }
}
