package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.reference.BuildCheckFacts;
import java.util.*;

/** Supplied fields stay separate from reference matches and unverified joint constraints. */
public final class BuildCheckAnswerEvidence {
    public static final String VERSION="bound-build-field-review-v2-learning-notes";
    private static final ObjectMapper JSON=new ObjectMapper();
    private BuildCheckFacts.Report report;
    private boolean attempted;
    public static boolean requested(String question){return question!=null&&question.length()<3000&&question.matches("(?s).*(?:检查|核对).{0,30}(?:配招|培养配置|四招).*");}
    public boolean attempted(){return attempted;}
    public static BuildCheckFacts.Report bound(String output,String arguments) {
        try {
            var input=JSON.readTree(arguments);if(!input.isObject()||input.size()!=1||output==null||output.length()>50_000)throw new IllegalArgumentException();
            var expected=BuildCheckFacts.parseRequest(input.path("request"));var result=BuildCheckFacts.validate(JSON.readTree(output));
            if(!JSON.valueToTree(expected).equals(JSON.valueToTree(result.request())))throw new IllegalArgumentException();return result;
        }catch(Exception invalid){throw new IllegalArgumentException("配招结果未与实际输入绑定",invalid);}
    }
    public void observe(String output,String arguments){attempted=true;try{report=bound(output,arguments);}catch(IllegalArgumentException invalid){report=null;}}
    public String summary(String question){
        if(report==null)return attempted||requested(question)?"### 配招逐项核对\n本次未取得与原配置绑定的核对结果，不能凭模型说明确认配招。\n\n":"";
        StringBuilder text=new StringBuilder("### 配招逐项核对\n");
        if(report.source()!=null)text.append("成员：").append(EvidenceText.inline(report.source().nameZh())).append("\n\n");
        text.append(switch(report.status()){case "REFERENCE_MATCHED"->"已填招式和特性逐项匹配参考，组合仍待审核。";case "REFERENCE_MISMATCH"->"部分已填配置与本次参考不一致。";case "REFERENCE_UNAVAILABLE"->"部分参考读取失败或未能绑定同一快照。";case "INVALID_INPUT"->"已填配置存在格式、重复招式或数值范围问题。";default->"配置仍有缺项或未解析名称。";}).append("\n\n");
        if(!report.missingFields().isEmpty())text.append("待补字段：").append(String.join("、",report.missingFields().stream().map(BuildCheckAnswerEvidence::field).distinct().toList())).append("。\n\n");
        text.append("特性：").append(switch(report.abilityStatus()){case "MATCHED"->"与该成员参考候选匹配";case "NOT_IN_SNAPSHOT"->"不在该成员本次参考候选中";case "MISSING"->"未填写";case "UNRESOLVED_NAME"->"名称尚未解析，请使用资料英文标识";default->"未取得可用参考";}).append("。\n\n");
        for(var m:report.moves()) {
            text.append("- 招式").append(m.slot()).append(" · ").append(EvidenceText.inline(m.supplied())).append("：")
                .append(switch(m.status()){case "RECORDED"->"有朱紫学习记录";case "NOT_RECORDED"->"该快照未记录；不能据此断言无法学习";case "UNRESOLVED_NAME"->"名称未解析";default->"未取得同一快照的学习参考";}).append("。\n");
            for(String note:com.vio.vioaiagent.pokemon.reference.BuildLearningNotes.describe(m,report.request().build()==null?null:report.request().build().level()))
                text.append("  - ").append(note).append("\n");
        }
        if(report.source()!=null)text.append("\n资料来源：[PokéAPI](").append(report.source().sourceUrl()).append(")\n\n");
        return text.append("仅核对原输入与逐项参考；未验证四招共同可得、遗传/进化/回忆操作、特性与招式组合、道具和性格效果、等级相容或正式赛制合法性。表单或存储默认值不代表用户已明确选择；本次没有修改或保存队伍。\n\n").toString();
    }
    private static String field(String key){if(key.startsWith("evs"))return "努力值";if(key.startsWith("ivs"))return "个体值";return switch(key){case "moves.fourSlots"->"四个招式槽位";case "ability"->"特性";case "item"->"道具字段";case "nature"->"性格";case "level"->"等级";default->"培养配置";};}
}
