package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.reference.PokemonNameIndex;
import com.vio.vioaiagent.run.AgentRunContext;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import java.util.regex.Pattern;

/** General reference-vs-rules explanations need a guide, not an invented species or usage query. */
public final class ReferenceRulesReply {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ReferenceRulesReply() { }
    public static boolean matches(String q){
        if(q==null||q.length()>600||q.contains("\n")||q.contains("```")||q.contains("{")
                ||Pattern.compile("保存|修改|写入|预览|组队|配队|推荐|种族值|属性|伤害|计算|对比|比较|为什么|为何").matcher(q).find())return false;
        if(!Pattern.compile("图鉴|候选|资料快照|PokéAPI",Pattern.CASE_INSENSITIVE).matcher(q).find()
                ||!Pattern.compile("比赛|赛事|参赛|排位|OU|合法",Pattern.CASE_INSENSITIVE).matcher(q).find()
                ||!Pattern.compile("代表|一定|都能|是否|能否|就能|等于|证明").matcher(q).find())return false;
        if(Pattern.compile("剑盾|剑／盾|剑/盾|绿宝石|火红|叶绿|究极之日|究极之月|#[0-9]+|编号[0-9]+").matcher(q).find())return false;
        String lower=q.toLowerCase(Locale.ROOT);
        return PokemonNameIndex.all().stream().noneMatch(p->q.contains(p.nameZh())
                ||Pattern.compile("(?<![a-z0-9-])"+Pattern.quote(p.slug())+"(?![a-z0-9-])").matcher(lower).find());
    }
    public static Optional<String> reply(AgentRunContext context,ToolCallback[] tools){
        if(!"pokemon-scarlet-violet".equals(context.run().gameReleaseId())||!matches(context.run().prompt()))return Optional.empty();
        String summary="不能仅凭图鉴或候选列表判断参赛合法性。图鉴收录、该版本的招式学习记录、特定赛制下的完整配置合法性是不同层次。需核对具体游戏版本、赛事/OU分级规则及日期，以及成员形态、招式、特性和道具等配置；本次不确认某个具体配置合法，也不需要查询单只使用率或热门榜。\n\n";
        var tool=Arrays.stream(tools).filter(t->"searchPokemonGuides".equals(t.getToolDefinition().name())).findFirst();
        if(tool.isEmpty())return Optional.of(summary+"本次未取得本地攻略原文来源，不补造引用。");
        try{
            String args=JSON.writeValueAsString(Map.of("query","图鉴资料 版本 比赛规则 合法性","gameReleaseId",context.run().gameReleaseId()));
            String output=tool.get().call(args);
            if(output==null||output.length()>120000)throw new IllegalArgumentException();
            var rows=JSON.readTree(output);
            if(rows==null||!rows.isArray()||rows.size()>4)throw new IllegalArgumentException();
            var relevant=JSON.createArrayNode();
            for(var row:rows)if("reference-vs-rules-v1".equals(row.path("document").path("id").asText()))relevant.add(row);
            if(relevant.isEmpty())return Optional.of(summary+"本次未取得匹配的规则区别原文，不以无关资料作为引用。");
            var evidence=new GuideAnswerEvidence();evidence.begin(context.run().prompt());evidence.observe("searchPokemonGuides",relevant.toString());
            return Optional.of(summary+evidence.verifiedAnswer(false));
        }catch(com.vio.vioaiagent.observability.TaskBudgetExceededException|com.vio.vioaiagent.observability.ContextBudgetExceededException|java.util.concurrent.CancellationException stop){throw stop;}
        catch(Exception unavailable){return Optional.of(summary+"本次规则区别资料未能读取或校验，不补写原文来源。");}
    }
}
