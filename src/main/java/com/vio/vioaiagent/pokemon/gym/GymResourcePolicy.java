package com.vio.vioaiagent.pokemon.gym;

import java.util.*;

/** Checks a specific acquisition route, not universal reachability or inventory. */
public final class GymResourcePolicy {
    private GymResourcePolicy() { }
    public static final List<String> GYMS=List.of("katy","brassius","iono","kofu","larry","ryme","tulip","grusha");
    private static final List<String> TMS=List.of("tm021","tm020","tm048","tm022","tm025","tm114","tm120","tm124");
    private static final List<String> NAMES=List.of("虫扑","起草","伏特替换","泼冷水","硬撑","暗影球","精神强念","冰旋");
    public record Rule(String id,String name,String requiredGym,String sourceUrl) { }
    public record Check(String id,String name,String status,String reason,String sourceUrl) { }
    public static List<Rule> catalog(){var rows=new ArrayList<Rule>();for(int i=0;i<8;i++)rows.add(new Rule(TMS.get(i),NAMES.get(i),GYMS.get(i),"https://pokemondb.net/scarlet-violet/tms"));return List.copyOf(rows);}
    public static void validateProgress(GymChallengeService.Progress progress,int badges){
        if(new HashSet<>(progress.completedGyms()).size()!=progress.completedGyms().size()||!GYMS.containsAll(progress.completedGyms())||progress.completedGyms().size()>badges)
            throw new IllegalArgumentException("已击败馆主不可重复、不可未知或多于道馆徽章数");
        if(progress.mainStoryComplete()&&badges!=8||progress.inspectionAssigned()&&!progress.mainStoryComplete())throw new IllegalArgumentException("主线通关/视察任务与徽章进度矛盾");
    }
    public static List<Check> check(GymChallengeService.Progress progress){
        var result=new ArrayList<Check>();
        for(String id:progress.requestedResources()){
            var rule=catalog().stream().filter(r->r.id().equals(id)).findFirst();
            if(progress.ownedResources().contains(id))result.add(new Check(id,rule.map(Rule::name).orElse(id),"OWNED_DECLARED","玩家声明背包已有；尚未证明成员可学习或比赛合法",rule.map(Rule::sourceUrl).orElse(null)));
            else if(rule.isEmpty())result.add(new Check(id,id,"UNKNOWN","未收录该资源的获取途径，不能断言可获得",null));
            else {var r=rule.get();boolean unlocked=progress.completedGyms().contains(r.requiredGym());result.add(new Check(id,r.name(),unlocked?"ROUTE_UNLOCKED":"ROUTE_NOT_CONFIRMED",unlocked?"已声明击败对应馆主，首胜奖励途径已满足；不代表背包仍有，也不代表可再次领取或已满足制作条件":"未确认击败对应馆主，不能依靠首胜奖励获取；不排除其他未核实途径",r.sourceUrl()));}
        }
        return List.copyOf(result);
    }
}
