package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.reference.TeamBuildCheckFacts;
import java.util.Map;

/** Actual governed tool arguments and full member reports are required before publishing a summary. */
public final class TeamBuildAnswerEvidence {
    public static final String VERSION="bound-team-build-answer-v3";
    private static final ObjectMapper JSON=new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private TeamBuildCheckFacts.Report report;private TeamBuildCheckFacts.Request expected;private boolean attempted;
    private java.util.List<Integer> expectedOrder=java.util.List.of();
    private boolean unfilledDraft;
    public void begin(String question){
        report=null;expected=null;attempted=false;expectedOrder=explicitOrder(question);unfilledDraft=isUnfilledDraft(question);
        if(!requested(question))return;int newline=question.lastIndexOf('\n');if(newline<0)return;
        String header=question.substring(0,newline).strip();if(header.length()>800||!(header.startsWith("请检查整队配置")||header.startsWith("请先调用readCurrentTeam读取当前账号已保存队伍编号 ")))return;
        try{expected=TeamBuildCheckFacts.parseRequest(JSON.readTree(question.substring(newline+1).strip()));}catch(Exception invalid){/* Only explicit valid original JSON constrains the supplied-team comparison. */}
    }
    public static boolean requested(String q){return q!=null&&q.length()<=16000&&q.matches("(?s).*(?:检查|核对).{0,20}(?:整队配置|整队配招|整支队伍配置).*");}
    public boolean attempted(){return attempted;}
    public static String instruction(String q){return requested(q)?"\n用户要求整队配置核对。优先使用checkPokemonTeamBuild，一次传入用户提供的完整原成员列表、位置、pokemonId、build和当前Run版本，不分拆子集、不改写、不代填。members数组必须保留用户列举顺序，不能自行按slot大小或物种排序；只有用户明确要求按位置排序时才排序。"+(explicitOrder(q).isEmpty()?"":"本次用户明确要求的原位置顺序为："+explicitOrder(q)+"；工具请求members必须严格按此顺序传入。")+(isUnfilledDraft(q)?"本次是用户已给定成员、所有培养配置均未填写的草稿，不是已保存队伍。不要列出或读取保存队伍。每个member必须显式包含\"build\":null，不得省略build键，不得传空对象或填默认配置。":"已保存队伍先用readCurrentTeam读取实际配置；成员不明时补问。")+"逐项匹配不证明联合可得或整队合法，不保存或生成修改预览。":"";}
    static boolean isUnfilledDraft(String q){
        return requested(q)&&(q.startsWith("请核对整队配置")||q.startsWith("请检查整队配置"))
                &&q.matches("(?s).*第[1-6].*")&&!q.matches("(?s).*(?:已保存|保存的队伍|当前账号|我的队伍|现有队伍|队伍编号|readCurrentTeam|[{}\\n]).*")
                &&q.matches("(?s).*(?:所有培养.{0,8}(?:未填写|没填写)|招式.{0,80}均未填写).*");
    }
    static org.springframework.ai.tool.ToolCallback[] planningTools(String q,org.springframework.ai.tool.ToolCallback[] tools){
        return !isUnfilledDraft(q)?tools:java.util.Arrays.stream(tools).filter(t->!java.util.Set.of("listCurrentTeams","readCurrentTeam").contains(t.getToolDefinition().name())).toArray(org.springframework.ai.tool.ToolCallback[]::new);
    }
    // Only an explicit ordering clause is interpreted; never infer species, builds or tool arguments.
    static java.util.List<Integer> explicitOrder(String q){
        if(!requested(q))return java.util.List.of();
        int start=q.indexOf("按我给的顺序：");if(start<0)return java.util.List.of();
        String clause=q.substring(start+7).split("[。；\\n]",2)[0];
        var matcher=java.util.regex.Pattern.compile("第([1-6])位").matcher(clause);
        var slots=new java.util.ArrayList<Integer>();while(matcher.find())slots.add(Integer.parseInt(matcher.group(1)));
        if(slots.isEmpty()||slots.size()>6||new java.util.HashSet<>(slots).size()!=slots.size())return java.util.List.of();
        return java.util.List.copyOf(slots);
    }
    public static TeamBuildCheckFacts.Report bound(String output,String arguments){try{
        if(output==null||output.length()>250000||arguments==null||arguments.length()>20000)throw new IllegalArgumentException();
        var input=JSON.readTree(arguments);if(input==null||!input.isObject()||input.size()!=1)throw new IllegalArgumentException();
        var expected=TeamBuildCheckFacts.parseRequest(input.path("request"));var result=TeamBuildCheckFacts.validate(JSON.readTree(output));
        if(!expected.equals(result.request()))throw new IllegalArgumentException();return result;
    }catch(Exception invalid){throw new IllegalArgumentException("整队结果与实际调用原配置不一致",invalid);}}
    public void observe(String output,String arguments){attempted=true;try{var candidate=bound(output,arguments);if(expected!=null&&!expected.equals(candidate.request()))throw new IllegalArgumentException("结果未覆盖原整队配置");if(!expectedOrder.isEmpty()&&!expectedOrder.equals(candidate.request().members().stream().map(TeamBuildCheckFacts.Member::slot).toList()))throw new IllegalArgumentException("结果未保留原成员顺序");if(unfilledDraft)for(var member:JSON.readTree(arguments).path("request").path("members"))if(!member.has("build")||!member.path("build").isNull())throw new IllegalArgumentException("未填写草稿必须显式保留null");report=candidate;}catch(Exception invalid){report=null;}}
    public String summary(String q){
        if(report==null)return attempted||requested(q)?"### 整队配置核对\n本次未取得与原成员配置绑定的整队结果，请补充队伍配置或重试。不能凭模型说明确认整队通过；本次未保存队伍。\n\n":"";
        var text=new StringBuilder("### 整队配置核对\n\n已核对 ").append(report.members().size()).append(" 位成员；联合可得性未验证。\n\n");
        var labels=Map.of("REFERENCE_MATCHED","逐项参考匹配","INCOMPLETE","有缺项","REFERENCE_UNAVAILABLE","资料不足","REFERENCE_MISMATCH","参考不一致","INVALID_INPUT","输入问题");
        for(String status:TeamBuildCheckFacts.STATUSES)text.append(labels.get(status)).append("：").append(report.counts().get(status)).append(" 位；");text.append("\n\n");
        for(var slots:report.duplicateFormSlots())text.append("相同形态出现在第 ").append(String.join("、",slots.stream().map(String::valueOf).toList())).append(" 位，仅记录重复，不据此判断违规。\n\n");
        for(var member:report.members()){
            text.append("#### 第 ").append(member.slot()).append(" 位\n\n");
            var one=new BuildCheckAnswerEvidence();try{one.observe(JSON.writeValueAsString(member.report()),JSON.writeValueAsString(Map.of("request",member.report().request())));}catch(Exception invalid){throw new IllegalStateException("成员报告无法编码",invalid);}
            text.append(one.summary(""));
        }
        return text.append("以上按成员独立核对，未验证整队原子快照、四招与特性共同取得、整队协同或赛制合法性；本次未修改或保存队伍。\n\n").toString();
    }
}
