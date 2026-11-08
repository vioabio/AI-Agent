package com.vio.vioaiagent.pokemon.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.agent.DamageAnswerEvidence;
import com.vio.vioaiagent.pokemon.domain.*;
import com.vio.vioaiagent.pokemon.repository.SavedTeamRepository;
import com.vio.vioaiagent.pokemon.service.*;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.tools.governance.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.ai.tool.annotation.*;
import java.time.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Local, bounded build edits. HITL is the sole approval authority; proposals never write teams. */
@Service @Profile("local & !prod & !demo")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name={"vio.run.durable-resume","vio.agent.v1.enabled"},havingValue="false",matchIfMissing=true)
public class TeamWorkflowService {
    public static final String ACTION="applyConfirmedTeamChanges", VERSION="1.0.0";
    private final PokemonResourceService resources;
    private final SavedTeamRepository teams;
    private final RunRepository runs;
    private final HitlManager hitl;
    private final Map<String,ReadTeam> reads=new ConcurrentHashMap<>();
    private final Map<String,Proposal> proposals=new ConcurrentHashMap<>();
    private final Map<String,String> calculations=new ConcurrentHashMap<>();
    private final ObjectMapper mapper=new ObjectMapper();
    public TeamWorkflowService(PokemonResourceService resources,SavedTeamRepository teams,RunRepository runs,HitlManager hitl) {
        this.resources=resources; this.teams=teams; this.runs=runs; this.hitl=hitl;
    }
    @Tool(description="列出当前登录账号的已保存队伍。没有明确队伍时先列出并请用户选择；不能指定其他用户。")
    public List<SavedTeam> listCurrentTeams() { return resources.listTeams(currentRun().userId(),50); }

    @Tool(description="读取当前账号指定的已保存队伍、真实成员资料和缺失条件。分析或预览前必须调用；队伍编号由用户选择，不接受模型指定用户。")
    public ReadTeam readCurrentTeam(@ToolParam(description="用户选择的已保存队伍ID") long teamId) {
        AgentRun run=currentRun(); cleanup();
        if(proposals.containsKey(run.id())) throw new IllegalStateException("当前 Run 已有固定预览，不能更换读取快照");
        var snapshot=teams.snapshotForOwner(teamId,run.userId());
        checkVersion(run,snapshot.team());
        List<PokemonFact> facts=new ArrayList<>(); List<String> missing=new ArrayList<>();
        for(var member:snapshot.team().members()) {
            var fact=resources.findFactByFormId(snapshot.team().dataReleaseId(),member.formId());
            if(!Objects.equals(run.gameReleaseId(),fact.gameReleaseId()))throw new IllegalArgumentException("队伍游戏版本与 Run 不一致");
            facts.add(fact);
            if(member.build().moves().isEmpty()) missing.add("槽位"+member.slot()+"未配置招式");
            if(member.build().ability().isBlank()) missing.add("槽位"+member.slot()+"未配置特性");
            if(member.build().item().isBlank()) missing.add("槽位"+member.slot()+"道具未明确；不能自行假定有道具");
        }
        var read=new ReadTeam(snapshot,facts,missing,run.deadlineAt());
        if(reads.size()>=1000) throw new IllegalStateException("队伍任务繁忙，请稍后重试");
        reads.put(run.id(),read);
        return read;
    }

    @Tool(description="仅生成队伍培养配置修改预览并暂停 Run 等待用户点击确认，不直接保存。必须先readCurrentTeam；只改明确列出的字段，null保留原值；无法确认的配置先追问。理由属于策略建议，不能编造计算或合法性证明。")
    public Preview previewTeamChanges(@ToolParam(description="已读取的队伍ID") long teamId,
            @ToolParam(description="按槽位列出修改，最多6名；只提供需要改动的字段") List<BuildChange> changes,
            @ToolParam(description="简明策略理由，不作为已核实事实") String rationale) {
        AgentRun run=currentRun(); cleanup();
        ReadTeam read=reads.get(run.id());
        if(read==null || read.snapshot().team().id()!=teamId) throw new IllegalStateException("请先读取用户指定队伍，不能依据聊天中的配置直接写入");
        var requestedCalculation=new DamageAnswerEvidence(); requestedCalculation.begin(run.prompt());
        if(requestedCalculation.active() && !calculations.containsKey(run.id()))
            throw new IllegalStateException("本任务要求计算伤害，请先单独完成 calculateBattleDamage；计算失败时不能生成保存预览");
        String reason=new com.vio.vioaiagent.agent.TeamAnswerEvidence().checkedAdvice(bounded(rationale,1200));
        if(changes==null||changes.isEmpty()||changes.size()>6) throw new IllegalArgumentException("请选择1至6名成员的明确修改");
        Set<Integer> slots=new HashSet<>(); List<TeamMember> members=new ArrayList<>(read.snapshot().team().members());
        for(var change:changes) {
            if(change==null||!slots.add(change.slot())) throw new IllegalArgumentException("修改槽位不能重复");
            int index=-1;
            for(int n=0;n<members.size();n++) if(members.get(n).slot()==change.slot()) index=n;
            if(index<0) throw new IllegalArgumentException("只能修改现有成员的培养配置");
            var member=members.get(index); var old=member.build();
            var build=new TeamBuild(change.moves()==null?old.moves():change.moves(),pick(change.ability(),old.ability()),
                    pick(change.item(),old.item()),pick(change.nature(),old.nature()),change.level()==null?old.level():change.level(),
                    merge(change.evs(),old.evs()),merge(change.ivs(),old.ivs()),old.teraType());
            members.set(index,new TeamMember(member.id(),member.formId(),member.slot(),build));
        }
        SavedTeam before=read.snapshot().team();
        SavedTeam after=new SavedTeam(before.id(),before.ownerId(),before.name(),before.rulesetId(),before.dataReleaseId(),members);
        List<ChangeLine> lines=diff(read,after);
        if(lines.isEmpty()) throw new IllegalArgumentException("没有需要保存的配置差异");
        String digest=digest(List.of(run.userId(),run.id(),read.snapshot(),after,reason));
        synchronized(this) {
            Proposal existing=proposals.get(run.id());
            if(existing!=null && !existing.digest.equals(digest)) throw new IllegalStateException("本 Run 的预览已经固定，请取消后新建任务");
            var decision=hitl.evaluate(run.userId(),run.id(),ACTION,VERSION,digest,true);
            if(!decision.waiting()) throw new IllegalStateException("预览审批已处理，不能重新生成或替换");
            Instant expires=decision.expiresAt().isBefore(run.deadlineAt())?decision.expiresAt():run.deadlineAt();
            var preview=new Preview(decision.approvalId(),teamId,before.name(),lines,reason,expires,
                    calculations.getOrDefault(run.id(),"未获得本 Run 的有效伤害计算。不能据此给出伤害、胜率或最优配置结论。"),
                    List.of("仅保存预览列出的培养配置；理由是策略建议", "图鉴存在与参数通过不代表招式合法或当前环境强势"));
            Proposal proposal=existing==null?new Proposal(run.userId(),run.id(),read.snapshot(),after,digest,preview):existing;
            proposals.put(run.id(),proposal);
            var context=ToolExecutionContextHolder.peek();
            context.eventSink().accept(new ToolGovernanceEvent("approval.required",run.id(),ACTION,UUID.randomUUID().toString(),Instant.now(),
                    Map.of("approvalId",decision.approvalId(),"expiresAt",expires.toString(),"teamChange",proposal.preview)));
            return proposal.preview;
        }
    }

    /** Consumes only an actual tool result, never a summary supplied by the model. */
    public void observeCalculation(String tool,String output) {
        if(!"calculateBattleDamage".equals(tool))return;
        var context=ToolExecutionContextHolder.peek(); if(context==null)return;
        if(!reads.containsKey(context.runId()))return;
        var evidence=new DamageAnswerEvidence(); evidence.observe(tool,output);
        if(evidence.complete())calculations.put(context.runId(),evidence.verifiedSummary());
        else calculations.remove(context.runId());
    }

    /** Called on the resumed Run worker, before any model call. No model controls confirmation. */
    public String finishIfPending(AgentRunContext context) {
        AgentRun run=context.run(); Proposal proposal=proposals.get(run.id());
        if(proposal==null)return null;
        synchronized(proposal) {
            if(!proposal.owner.equals(run.userId()))throw new SecurityException("队伍任务归属不匹配");
            if(proposal.receipt!=null)return publish(context,proposal.receipt);
            var approval=hitl.getApproval(proposal.preview.proposalId());
            if(approval==null || !approval.userId().equals(run.userId())||!approval.runId().equals(run.id())
                    || !approval.toolName().equals(ACTION)||!approval.parameterHash().equals(proposal.digest)
                    || approval.status()!=HitlManager.ApprovalStatus.APPROVED)
                throw new IllegalStateException("尚未获得匹配的用户确认，不保存队伍");
            if(!Instant.now().isBefore(proposal.preview.expiresAt())) {
                proposal.outcome="EXPIRED"; proposal.receipt="预览已过期，未保存。请重新分析队伍后确认。";
                return publish(context,proposal.receipt);
            }
            checkVersion(run,proposal.before.team());
            try {
                Optional<SavedTeam> saved=context.mutations().write(()->teams.compareAndSet(proposal.before,proposal.after,context.mutations()::check));
                if(saved.isEmpty()) {
                    proposal.outcome="CONFLICT"; proposal.receipt="队伍已被修改或删除，本次预览未保存。请重新读取队伍并生成新预览。";
                } else {
                    proposal.saved=saved.get(); proposal.outcome="APPLIED";
                    proposal.receipt="### 队伍修改已保存\n已保存并回读队伍「"+safe(saved.get().name())+"」，仅应用你确认的以下变更：\n\n"+table(proposal.preview.changes())
                            +"\n这些是培养配置记录，不代表招式合法性、最优搭配或胜率证明。";
                }
            } catch(RuntimeException failure) {
                proposal.outcome="UNKNOWN"; // Do not repeat a possibly committed SQLite operation.
                proposal.receipt="保存过程未能确认完成，请回读队伍核对。当前任务不会自动重放写入。";
                throw failure;
            }
            return publish(context,proposal.receipt);
        }
    }
    public Outcome outcome(String runId,String owner) {
        AgentRun run=runs.findByIdForUser(runId,owner).orElseThrow(()->new PokemonResourceNotFoundException("任务不存在"));
        Proposal proposal=proposals.get(runId);
        if(proposal==null||!proposal.owner.equals(owner))throw new PokemonResourceNotFoundException("队伍预览不存在");
        synchronized(proposal) {
            String status=proposal.outcome;
            if(status.equals("PENDING")) {
                var approval=hitl.getApproval(proposal.preview.proposalId());
                if(run.status().isTerminal())status=run.status().name();
                if(approval!=null&&approval.status()==HitlManager.ApprovalStatus.DENIED)status="DENIED";
                else if(!Instant.now().isBefore(proposal.preview.expiresAt()))status="EXPIRED";
            }
            return new Outcome(proposal.preview,status,proposal.saved,proposal.receipt);
        }
    }
    private String publish(AgentRunContext context,String answer) { if(context.answerDeltaSink()!=null) context.answerDeltaSink().accept(answer); return answer; }
    private AgentRun currentRun() {
        var context=ToolExecutionContextHolder.peek();
        if(context==null||!context.runId().startsWith("run-")||context.userId().equals("anonymous")) throw new SecurityException("仅支持登录账号的正式 Run");
        var run=runs.findByIdForUser(context.runId(),context.userId()).orElseThrow(()->new SecurityException("任务不存在"));
        if(run.status()!=AgentRunStatus.RUNNING || Thread.currentThread().isInterrupted() || !Instant.now().isBefore(run.deadlineAt()))
            throw new IllegalStateException("任务已停止或超时");
        return run;
    }
    private void checkVersion(AgentRun run,SavedTeam team) {
        if(!Objects.equals(run.dataReleaseId(),team.dataReleaseId())||!Objects.equals(run.rulesetVersionId(),team.rulesetId()))
            throw new IllegalArgumentException("队伍规则/资料版本与当前 Run 不一致，请新建对应版本的会话");
    }
    private void cleanup() {
        Instant now=Instant.now();
        reads.entrySet().removeIf(entry->entry.getValue().expiresAt().plusSeconds(600).isBefore(now));
        proposals.entrySet().removeIf(entry->entry.getValue().preview.expiresAt().plusSeconds(600).isBefore(now));
        calculations.keySet().removeIf(key->!reads.containsKey(key));
    }
    private List<ChangeLine> diff(ReadTeam read,SavedTeam after) {
        List<ChangeLine> lines=new ArrayList<>();
        String[] fields={"招式","特性","道具","性格","等级","努力值","个体值"};
        for(int n=0;n<after.members().size();n++) {
            var before=read.snapshot().team().members().get(n); var member=after.members().get(n);
            String[] old=values(before.build()),next=values(member.build());
            for(int f=0;f<fields.length;f++) if(!old[f].equals(next[f]))lines.add(new ChangeLine(member.slot(),read.facts().get(n).speciesNameZh(),fields[f],old[f],next[f]));
        }
        return List.copyOf(lines);
    }
    private static String[] values(TeamBuild b) { return new String[]{String.join(" / ",b.moves()),b.ability(),b.item(),b.nature(),b.level().toString(),stats(b.evs()),stats(b.ivs())}; }
    private static String stats(TeamBuild.Stats s) { return "HP "+s.hp()+" / 攻 "+s.atk()+" / 防 "+s.def()+" / 特攻 "+s.spa()+" / 特防 "+s.spd()+" / 速 "+s.spe(); }
    private static TeamBuild.Stats merge(StatChange p,TeamBuild.Stats s) { return p==null?s:new TeamBuild.Stats(v(p.hp(),s.hp()),v(p.atk(),s.atk()),v(p.def(),s.def()),v(p.spa(),s.spa()),v(p.spd(),s.spd()),v(p.spe(),s.spe())); }
    private static int v(Integer n,int old) { return n==null?old:n; }
    private static String pick(String n,String old) { return n==null?old:n; }
    private static String bounded(String text,int max) { if(text==null||text.isBlank()||text.length()>max||text.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n')) throw new IllegalArgumentException("请提供简短、有效的修改理由"); return text.trim(); }
    private String digest(Object value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException("预览摘要计算失败",e);} }
    private static String safe(String text) { return text.replaceAll("[|\\r\\n*`<>]"," "); }
    private static String table(List<ChangeLine> changes) { StringBuilder text=new StringBuilder("| 成员 | 配置 | 修改前 | 修改后 |\n| --- | --- | --- | --- |\n"); for(var line:changes)text.append("| ").append(safe(line.member())).append(" | ").append(line.field()).append(" | ").append(safe(line.before())).append(" | ").append(safe(line.after())).append(" |\n");return text.toString(); }
    public record StatChange(@ToolParam(required=false) Integer hp,@ToolParam(required=false) Integer atk,
            @ToolParam(required=false) Integer def,@ToolParam(required=false) Integer spa,@ToolParam(required=false) Integer spd,@ToolParam(required=false) Integer spe) { }
    public record BuildChange(int slot,@ToolParam(required=false) List<String> moves,@ToolParam(required=false) String ability,
            @ToolParam(required=false) String item,@ToolParam(required=false) String nature,@ToolParam(required=false) Integer level,
            @ToolParam(required=false) StatChange evs,@ToolParam(required=false) StatChange ivs) { }
    public record ReadTeam(SavedTeamRepository.Snapshot snapshot,List<PokemonFact> facts,List<String> missingConditions,Instant expiresAt) { public ReadTeam { facts=List.copyOf(facts);missingConditions=List.copyOf(missingConditions); } }
    public record ChangeLine(int slot,String member,String field,String before,String after) { }
    public record Preview(String proposalId,long teamId,String teamName,List<ChangeLine> changes,String rationale,Instant expiresAt,String calculationEvidence,List<String> limitations) { }
    public record Outcome(Preview preview,String status,SavedTeam savedTeam,String message) { }
    private static final class Proposal {
        final String owner,runId,digest; final SavedTeamRepository.Snapshot before; final SavedTeam after; final Preview preview;
        String outcome="PENDING",receipt; SavedTeam saved;
        Proposal(String owner,String runId,SavedTeamRepository.Snapshot before,SavedTeam after,String digest,Preview preview) { this.owner=owner;this.runId=runId;this.before=before;this.after=after;this.digest=digest;this.preview=preview; }
    }
}
