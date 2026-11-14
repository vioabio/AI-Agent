package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.reference.PokemonNameIndex;
import com.vio.vioaiagent.pokemon.team.TeamWorkflowService;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Binds the governed saved-roster assessment to the training snapshot; never numerical damage. */
public final class SavedTrainingGymReview {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern TARGET = Pattern.compile("("+String.join("|",GymConversationContext.NAMES)+")(初战|重赛)");
    private static final Set<Double> MULTIPLIERS = Set.of(0d,.25d,.5d,1d,2d,4d);
    private SavedTrainingGymReview() { }

    public static String describe(TeamWorkflowService.ReadTeam saved, TrainingIntentState intent, Function<String,String> assess) {
        var out = new StringBuilder("**目标对局 · 已保存招式覆盖**\n\n");
        if(intent.conflicted()) return out.append("条件有冲突，本次不发起目标对局评估。\n\n").toString();
        var target = TARGET.matcher(intent.declared("目标").orElse("").replaceAll("\\s+",""));
        if(!target.matches()) return out.append("尚未明确馆主及初战/重赛，不猜测目标，也不发起评估。\n\n").toString();
        if(assess == null) return out.append("目标已明确，但当前没有可用的受治理队伍评估工具，本次未取得覆盖结果。\n\n").toString();
        String phase = target.group(2).equals("初战") ? "initial" : "rematch";
        String id = GymConversationContext.IDS.get(GymConversationContext.NAMES.indexOf(target.group(1)));
        try {
            if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if(!java.time.Instant.now().isBefore(saved.expiresAt())) throw new IllegalArgumentException();
            var args = JSON.createObjectNode().put("teamId",saved.snapshot().team().id()).put("gymId",id).put("phase",phase);
            String output = assess.apply(args.toString());
            if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if(!java.time.Instant.now().isBefore(saved.expiresAt())) throw new IllegalArgumentException();
            if(output == null || output.length() > 131072 || output.startsWith("[tool.")) throw new IllegalArgumentException();
            var analysis = JSON.readTree(output);
            var report = bound(analysis,saved,id,phase);
            var gym = report.path("gym");
            out.append(target.group(1)).append(" · ").append(target.group(2)).append("，按本次读取的同一保存版本核对。\n\n");
            for(var foe : gym.path("opponents")) {
                JsonNode best = null;
                for(var row : report.path("matchups")) if(row.path("opponent").asText().equals(foe.path("name").asText())
                        && (best == null || row.path("afterTera").asDouble() > best.path("afterTera").asDouble())) best = row;
                out.append("- ").append(EvidenceText.inline(foe.path("nameZh").asText(foe.path("name").asText()))).append("：");
                if(best == null) out.append("未取得可评估的已保存攻击招式候选，不代表不存在可用招式。\n");
                else if(best.path("afterTera").asDouble()==0) out.append("已返回的攻击候选在对手太晶后均为0倍，不能据此安排这些候选突破；其他招式与特殊机制仍待核对。\n");
                else out.append("槽位 ").append(best.path("slot").asInt()).append("的").append(EvidenceText.inline(best.path("move").asText()))
                        .append("，太晶前 ").append(best.path("beforeTera").asDouble()).append(" 倍 → 太晶后 ").append(best.path("afterTera").asDouble())
                        .append(" 倍属性效果。该候选仅按工具返回的属性倍率排序，不是最佳出招或伤害排序。\n");
            }
            out.append("\n这只是当前已保存招式的属性覆盖，未计算HP伤害、速度、击倒概率或胜率，也未把目标等级写入计算。实际等级与进度、招式可得性、NPC性格/EV/IV和双方状态仍待确认。");
            if("Doubles".equals(gym.path("battleMode").asText())) out.append("该目标为双打；单项覆盖不能代替首发、队友波及和配合评估。");
            if("rematch".equals(phase)) out.append("重赛解锁未核验，不能仅凭徽章数认定可以挑战。");
            return out.append("\n\n来源：[馆主资料](https://www.serebii.net/scarletviolet/").append(phase.equals("initial")?"gyms":"gymrematches")
                    .append(".shtml)，随包核对日期 ").append(gymCatalog(phase).path("reviewedAt").asText()).append("。本次未修改或保存队伍。\n\n").toString();
        } catch(com.vio.vioaiagent.observability.TaskBudgetExceededException | java.util.concurrent.CancellationException stop) { throw stop; }
        catch(Exception invalid) { return out.append("未取得与本次队伍编号、保存版本、成员配置及目标绑定的评估结果；队伍可能已更新或工具不可用，请重新读取后核对。本次不回退到其他队伍，也不补造伤害结果。\n\n").toString(); }
    }

    private static JsonNode gymCatalog(String phase) throws Exception { return GymCatalogAnswer.load(phase); }
    private static JsonNode bound(JsonNode a, TeamWorkflowService.ReadTeam saved, String id, String phase) throws Exception {
        var team = saved.snapshot().team();
        if(a == null || !a.isObject() || !integer(a.path("teamId"),team.id()) || !integer(a.path("revision"),saved.snapshot().revision())
                || !a.path("teamName").isTextual() || !team.name().equals(a.path("teamName").asText())
                || !team.dataReleaseId().equals(a.path("savedDataReleaseId").asText()) || !team.rulesetId().equals(a.path("savedRulesetId").asText())
                || !a.path("members").isArray() || a.path("members").size()!=team.members().size()) throw new IllegalArgumentException();
        var members = new HashMap<Integer,JsonNode>();
        for(var row : a.path("members")) {
            if(!row.path("slot").isIntegralNumber() || !row.path("slot").canConvertToInt() || members.put(row.path("slot").asInt(),row)!=null) throw new IllegalArgumentException();
        }
        for(var member : team.members()) {
            var row = members.get(member.slot());
            String name = PokemonNameIndex.all().stream().filter(p->p.nationalNo()==member.formId()).map(PokemonNameIndex.Entry::nameZh).findFirst().orElse("形态#"+member.formId());
            if(row == null || !name.equals(row.path("name").asText()) || !JSON.valueToTree(member.build()).equals(row.path("build"))
                    || !row.path("moveLabels").isArray() || row.path("moveLabels").size()!=member.build().moves().size()) throw new IllegalArgumentException();
            for(var label : row.path("moveLabels")) if(!label.isTextual() || label.asText().isBlank() || label.asText().length()>80) throw new IllegalArgumentException();
        }
        var catalog = gymCatalog(phase); JsonNode expected = null;
        for(var row : catalog.path("gyms")) if(id.equals(row.path("id").asText())) expected = row;
        var report = a.path("report");
        if(expected == null || !expected.equals(report.path("gym")) || !report.path("badges").isNull() || !report.path("gameEdition").isNull()
                || !report.path("resourceChecks").isArray() || !report.path("resourceChecks").isEmpty()
                || !("sv-"+phase+"-v2 / 核对"+catalog.path("reviewedAt").asText()).equals(report.path("sourceVersion").asText())
                || !report.path("matchups").isArray() || report.path("matchups").size()>144) throw new IllegalArgumentException();
        var pairs = new HashSet<String>();
        for(var row : report.path("matchups")) {
            if(!row.path("slot").isIntegralNumber() || !row.path("slot").canConvertToInt()) throw new IllegalArgumentException();
            var member = members.get(row.path("slot").asInt()); JsonNode foe = null;
            for(var candidate : expected.path("opponents")) if(candidate.path("name").asText().equals(row.path("opponent").asText())) foe = candidate;
            if(member == null || foe == null || !member.path("name").equals(row.path("pokemon"))
                    || !row.path("move").isTextual() || !contains(member.path("moveLabels"),row.path("move").asText())
                    || !integer(row.path("levelGap"),member.path("build").path("level").asInt()-foe.path("level").asInt())
                    || !row.path("beforeTera").isNumber() || !row.path("afterTera").isNumber()
                    || !MULTIPLIERS.contains(row.path("beforeTera").asDouble()) || !MULTIPLIERS.contains(row.path("afterTera").asDouble())
                    || !pairs.add(row.path("slot").asText()+"/"+row.path("move").asText()+"/"+row.path("opponent").asText())) throw new IllegalArgumentException();
        }
        return report;
    }
    private static boolean integer(JsonNode n,long expected) { return n.isIntegralNumber() && n.canConvertToLong() && n.asLong()==expected; }
    private static boolean contains(JsonNode array,String value) { for(var row:array)if(row.asText().equals(value))return true;return false; }
}
