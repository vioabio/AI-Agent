package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.run.AgentRunContext;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import java.util.regex.Pattern;

/** Concept questions use a source read, without selecting an arbitrary member or saved team. */
public final class ConfigurationExplanationRoute {
    public static final String VERSION="configuration-concepts-with-bound-source-v1";
    private ConfigurationExplanationRoute() { }
    enum Topic {
        IDENTIFIERS("move-version-and-identifiers-v1","朱紫招式候选与英文标识",
                "英文名称或slug用于匹配资料资源和传递工具参数，中文显示名用于界面阅读。显示名不能代替资源身份，未知译名应保留返回的英文标识。文本导入会统一英文招式名称的大小写和分隔符，便于后续查询、校验和计算；规范化后仍需核对真实资源、实际候选与版本，不能由名称规范化推断配招合法。"),
        ITEM_INTENT("ability-item-traceability-v1","特性与道具先查询 再核对计算回读",
                "在收集条件时，未填写道具表示选择尚未确认，明确不携带表示用户已作选择。当前队伍保存对象会把未提供、空值和空字符串道具归一化为空字符串，因此仅凭保存记录的空字段无法还原当初意图。分析时应说明这个缺口，不能据此宣称某个道具已经生效。"),
        DAMAGE_INPUTS("damage-request-checklist-v1","发起伤害计算前要补齐什么",
                "请准备双方物种及形态、所用招式、等级、性格、EV/IV、特性、道具，以及单双打、天气和场地条件。互相攻击需要分别指定两个方向的招式。允许采用的默认值或NPC数值假设应先确认并在结果中保留，随后按对应工具Schema核对可表达的条件。当前只是整理输入清单，尚未计算伤害。"),
        TEAM_ROLES("team-roles-v1","围绕核心成员构建队伍",
                "一条通用原则是先确定核心成员的角色，再用队友补齐它难以处理的弱点和对局。不要只挑六只攻击高的成员，还应考虑速度控制、耐久支点与可靠输出。当前只讨论通用思路，没有读取或修改你的私人队伍。"),
        SAVE_WORKFLOW("team-change-confirmation-v1","队伍 修改预览 用户确认 实际保存",
                "AI提出的调整是建议。实际保存应有本次变更预览、页面确认结果和保存后的配置回读，可在队伍页核对。预览尚未确认、被取消、过期或发生版本冲突时，不能把聊天中的完成措辞当成保存回执。本次只解释流程，没有读取或修改任何队伍。");
        final String documentId,query,summary;
        Topic(String documentId,String query,String summary){this.documentId=documentId;this.query=query;this.summary=summary;}
        boolean matches(String q) {
            return switch(this) {
                case IDENTIFIERS -> q.contains("招式") && has(q,"英文|slug|标识") && has(q,"中文|显示|导入|转换|稳定");
                case ITEM_INTENT -> q.contains("道具") && has(q,"未填|没填|不填|空") && has(q,"不带|不携带|不持有|未选择") && has(q,"区别|相同|一样|是不是|是否|能区分|能否区分");
                case DAMAGE_INPUTS -> has(q,"伤害|互相打") && has(q,"哪些信息|什么信息|准备|提供哪些|需要哪些|补齐哪些|条件清单");
                case TEAM_ROLES -> has(q,"通用.{0,6}(?:组队|配队).{0,4}(?:原则|思路)|(?:组队|配队).{0,4}通用原则") && has(q,"不用|不要|无需|不必|别");
                case SAVE_WORKFLOW -> has(q,"AI|建议|预览|流程") && has(q,"队伍|配置|调整") && has(q,"保存|写入|应用") && has(q,"误认|误以|以为|如何确认|怎样确认|区分|区别|怎么判断|如何避免|怎样避免|是否自动");
            };
        }
    }
    private static boolean has(String q,String expression){return Pattern.compile(expression,Pattern.CASE_INSENSITIVE).matcher(q).find();}
    static Optional<Topic> select(String question) {
        if(question==null||question.length()>500||has(question,"[\\r\\n]|```|[{}]|以下资料|以下数据")
                ||has(question,"^(?:请|帮我|麻烦|现在)?(?:读取|保存|修改|删除|生成|应用|导入文件|导出)")
                ||has(question,"(?:再|然后|并|同时|以及|顺便)(?:帮我|请)?(?:读取|保存|修改|删除|生成|应用|计算|推荐|分析|组队|配队|给出)")
                ||!has(question,"为什么|为何|作用|区别|相同|一样|是不是|是否|如何|怎么|怎样|哪些信息|什么信息|准备|说明|解释|能区分|能否区分|通用.{0,6}(?:组队|配队).{0,4}(?:原则|思路)"))return Optional.empty();
        var topics=Arrays.stream(Topic.values()).filter(topic->topic.matches(question)).toList();
        return topics.size()==1?Optional.of(topics.getFirst()):Optional.empty();
    }
    public static Optional<String> reply(AgentRunContext context,ToolCallback[] tools) {
        var selected=select(context.run().prompt());
        if(selected.isEmpty()||!"pokemon-scarlet-violet".equals(context.run().gameReleaseId()))return Optional.empty();
        var topic=selected.get();
        var tool=Arrays.stream(tools).filter(t->"searchPokemonGuides".equals(t.getToolDefinition().name())).findFirst();
        String unavailable="\n\n本次未取得匹配的本地说明原文，以上为项目字段与执行流程说明；来源引用暂缺。";
        if(tool.isEmpty())return Optional.of(topic.summary+unavailable);
        try {
            String input=new ObjectMapper().writeValueAsString(Map.of("query",topic.query,"gameReleaseId",context.run().gameReleaseId()));
            var evidence=new GuideAnswerEvidence();evidence.begin(context.run().prompt());
            evidence.observe("searchPokemonGuides",tool.get().call(input),input);
            String question = context.run().prompt();
            if (topic == Topic.TEAM_ROLES && has(question,"只|仅|一条|一句|简短|简要")
                    && !has(question,"完整原文|全文|附上原文|给出原文")) {
                return Optional.of("先确定核心成员的角色，再用队友补齐它难以处理的弱点和对局。\n\n"
                        + evidence.documentAttribution(topic.documentId).orElse(unavailable));
            }
            return Optional.of(topic.summary+"\n\n"+evidence.fullDocumentAnswer(topic.documentId).orElse(unavailable));
        }catch(com.vio.vioaiagent.observability.TaskBudgetExceededException|com.vio.vioaiagent.observability.ContextBudgetExceededException|java.util.concurrent.CancellationException stop){throw stop;}
        catch(Exception unavailableSource){return Optional.of(topic.summary+unavailable);}
    }
}
