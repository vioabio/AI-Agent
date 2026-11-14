package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import java.util.*;

/** Bounded program projection of validated learning records and pinned catalogues; never inventory/unlock proof. */
public final class SavedTrainingResources {
    public static final int MAX_RECORDS=4;
    private static final ObjectMapper JSON=new ObjectMapper();
    private record Candidate(int slot,MachineReferenceFacts.Report report) { }
    private SavedTrainingResources() { }
    public static String describe(TeamBuildCheckFacts.Report supplied) {
        return describe(supplied,TrainingIntentState.resolve(List.of(),null));
    }
    public static String describe(TeamBuildCheckFacts.Report supplied,TrainingIntentState intent) {
        return TrainingResourceBudget.describe(supplied,intent)+references(supplied);
    }
    private static String references(TeamBuildCheckFacts.Report supplied) {
        if(supplied==null)return "未取得可用于资源核对的学习记录，本次不列编号、配方或地点。\n";
        var checked=TeamBuildCheckFacts.validate(JSON.valueToTree(supplied));
        var candidates=new ArrayList<Candidate>();
        for(var member:checked.members())for(var move:member.report().moves()){
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            if(!"RECORDED".equals(move.status())||move.learning()==null||move.learning().entries().stream().noneMatch(e->"machine".equals(e.methodKey())))continue;
            var r=checked.request();
            candidates.add(new Candidate(member.slot(),MachineReferenceFacts.create(member.report().request().pokemonId(),move.normalized(),r.dataReleaseId(),r.gameReleaseId(),move.learning())));
        }
        if(candidates.isEmpty())return "本次没有可用于学习器核对的已验证记录；不据此断言无法学习或没有获取资源。\n";
        candidates.sort(Comparator.comparingInt(c->"MATCHED".equals(c.report().status())?0:1));
        var out=new StringBuilder("以下由本次已核验学习记录与随包固定目录交叉核对，仅为公开参考，不是当前背包或解锁证明。\n\n");
        for(var candidate:candidates.stream().limit(MAX_RECORDS).toList()){
            var r=candidate.report();
            out.append("#### 第 ").append(candidate.slot()).append(" 位 · ").append(EvidenceText.inline(r.moveSlug())).append("\n\n");
            if(!"MATCHED".equals(r.status())){
                out.append("成员有学习器记录，但固定编号目录未收录；本次不提供编号、配方或地点。\n\n");continue;
            }
            out.append(r.machine().label()).append("：成员快照记录学习器途径。来源：[招式](").append(r.machine().moveUrl()).append(") · [学习器](").append(r.machine().machineUrl()).append(") · [道具](").append(r.machine().itemUrl()).append(")\n");
            out.append(MachineRecipeCatalog.answer(r.moveSlug())).append(MachineAcquisitionCatalog.answer(r.moveSlug())).append('\n');
        }
        if(candidates.size()>MAX_RECORDS)out.append("本次最多展开 ").append(MAX_RECORDS).append(" 条；另有 ").append(candidates.size()-MAX_RECORDS).append(" 条学习器记录未展开，请指定成员和招式继续核对。\n\n");
        return out.append("实际剧情解锁、材料库存、精确坐标和可执行路径仍待核对；来源有差异时不补造材料或数量。本次未获取物品、修改或保存队伍。\n").toString();
    }
}
