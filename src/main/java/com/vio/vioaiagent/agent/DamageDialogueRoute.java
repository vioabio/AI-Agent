package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.run.AgentRunContext;
import com.vio.vioaiagent.tools.governance.*;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;

/** Bind explicit numerical tasks to user input, never to an assistant's remembered numbers. */
public final class DamageDialogueRoute {
    private DamageDialogueRoute() { }
    static boolean isResultConditionsQuestion(String q) {
        return q!=null&&q.strip().matches("(?:请问|请解释)?工具报告的伤害百分比是在什么条件下成立[？?。]?");
    }
    public static Optional<String> reply(AgentRunContext context, ToolCallback[] tools) {
        String question=context.run().prompt();
        if(isResultConditionsQuestion(question))return Optional.of(resultConditionsReply(context, tools));
        if(question.strip().matches("(?:请|帮我)?(?:计算|算)(?:一下)?伤害[。！？?\\s]*"))
            return Optional.of("请明确本次攻击方、防御方、所用招式及等级、特性、道具、性格、努力值、个体值和场地条件。本次尚未计算，不自动选择保存队伍、招式或默认配置。");
        // A bare named matchup is not a request to select an account's saved team.
        // Resolve only exact public identities; supplied builds/JSON and follow-ups
        // continue through their existing evidence-bound paths below.
        var bare=java.util.regex.Pattern.compile("^(?:请帮我|帮我|请)?(?:计算|算)(?:一下|下)?\\s*(.{1,80}?)(?:打|攻击|对)(.{1,80}?)(?:的)?(?:伤害范围|伤害是多少|伤害)[。？！?\\s]*$").matcher(question.strip());
        if(bare.matches()) {
            var attacker=identity(bare.group(1));var defender=identity(bare.group(2));
            if(attacker.isPresent()&&defender.isPresent())return Optional.of("你要计算的是"+attacker.get()+"攻击"+defender.get()+"的伤害。目前只明确了双方物种，还需提供所用招式、双方等级、实际攻防能力或性格/努力值/个体值、特性、道具，以及天气、场地和太晶等条件。\n本次尚未计算，不自动读取保存队伍、不代选招式或默认培养配置；可直接补充双方完整配置后再计算。");
        }
        boolean current=ExplicitDamageRequest.hasInput(question);
        boolean followup=question.matches("(?s).*(解释.{0,12}(上次|上一次|计算结果)|重新计算|重新算|精确.{0,8}(伤害|数值)|胜率|保证.{0,12}赢|一定赢).*" );
        if(!current&&!followup)return Optional.empty();
        String original=question;
        if(!current) {
            original=null;
            for(var message:context.dialogueHistory())if(message instanceof UserMessage&&ExplicitDamageRequest.hasInput(message.getText()))original=message.getText();
            if(original==null) {
                if(question.matches("(?s).*(解释.{0,12}(上次|上一次|计算结果)|重新计算|精确.{0,8}伤害).*"))return Optional.of("没有找到可核对的原始计算输入，历史回答不能代替实际计算证据。请确认双方配置与招式后重新计算，本次不引用历史数值。");
                return Optional.empty();
            }
        }
        if(question.matches("(?s).*(不调用|不要调用|禁止调用|不用).{0,12}(工具|计算).*"))
            return Optional.of("本轮不调用计算工具，因此不提供新的精确伤害。历史模型文字不能当作已验证计算；需要数值时请允许按完整输入重新计算。单次伤害百分比不是胜率。");
        if(!current&&!question.matches("(?s).*(解释|解读).{0,15}(上次|上一次|计算结果).*" )&&question.matches("(?s).*(胜率|保证.{0,12}赢|一定赢|写进.{0,8}队伍).*"))
            return Optional.of("不能保证整场获胜，也不能由单次伤害推算胜率。历史文字中的数字不自动成为计算证据；本轮未重新计算，不重述未经本轮核对的数字，也未保存或修改队伍。需要修改配置时仍须读取、预览并由页面确认。");
        var input=ExplicitDamageRequest.arguments(original);
        if(input.isEmpty())return Optional.of(ExplicitDamageRequest.INVALID_INPUT);
        try {
            var args=new ObjectMapper().readTree(input.get());String data=args.path("request").path("dataReleaseId").asText();
            if(context.run().dataReleaseId()!=null&&!data.equals(context.run().dataReleaseId()))return Optional.of("计算输入与当前会话的数据版本冲突，本次未计算。请确认一致的资料版本，不会自动替换输入版本。");
            // Mixed tasks retain their team planner; the same arguments are bound at act().
            if(current&&question.substring(0,question.indexOf('{')).matches("(?s).*(分析队伍|分析阵容).*"))return Optional.empty();
            var tool=Arrays.stream(tools).filter(t->t.getToolDefinition().name().equals("calculateBattleDamage")).findFirst();
            if(tool.isEmpty())return Optional.of("当前伤害计算工具未启用，本次未计算，不提供猜测数值。");
            var parent=ToolExecutionContextHolder.peek();if(parent==null||!parent.userId().equals(context.run().userId())||!parent.runId().equals(context.run().id()))return Optional.of("当前计算上下文不可用，本次未计算。");
            if(Thread.currentThread().isInterrupted()||!java.time.Instant.now().isBefore(context.run().deadlineAt()))throw new java.util.concurrent.CancellationException("Run stopped");
            context.modelUsage().checkTaskBudget();
            var marked=parent.withEventSink(event->{var attributes=new LinkedHashMap<String,Object>(event.attributes());attributes.put("agentRole","DAMAGE_CALCULATION");attributes.put("selectionOrigin",ExplicitDamageRequest.ORIGIN);parent.eventSink().accept(new ToolGovernanceEvent(event.eventType(),event.runId(),event.toolName(),event.eventId(),event.occurredAt(),attributes));});
            String output;
            ToolExecutionContextHolder.set(marked);
            try {output=tool.get().call(input.get());}finally {ToolExecutionContextHolder.set(parent);}
            var evidence=new DamageAnswerEvidence();evidence.begin(question);evidence.observe("calculateBattleDamage",output);
            if (!evidence.complete()) throw new DamageCalculationFailedException();
            return Optional.of(evidence.verifiedSummary()+evidence.verifiedInterpretation());
        } catch(com.vio.vioaiagent.observability.TaskBudgetExceededException|com.vio.vioaiagent.observability.ContextBudgetExceededException|java.util.concurrent.CancellationException stop){throw stop;}
        catch(Exception failure){throw new DamageCalculationFailedException();}
    }
    private static Optional<String> identity(String text) {
        String name=text.strip();
        return com.vio.vioaiagent.pokemon.reference.PokemonNameIndex.all().stream()
            .filter(e->e.nameZh().equals(name)||e.slug().equalsIgnoreCase(name)||e.aliases().stream().anyMatch(a->a.equalsIgnoreCase(name)))
            .map(com.vio.vioaiagent.pokemon.reference.PokemonNameIndex.Entry::nameZh).findFirst();
    }

    private static String resultConditionsReply(AgentRunContext context, ToolCallback[] tools) {
        String summary = "伤害百分比只对报告中明确的双方配置与计算假设成立。百分比的分母是防御方最大HP；范围还依赖等级、招式、能力值、特性、道具、性格、EV/IV、天气、场地及太晶等输入。当前接口范围需区分：通用 calculateBattleDamage 只接基础培养配置、单双打、天气与场地，不接强化、异常状态、墙或太晶参数；馆主 calculateGymDamage 支持其Schema中已建模的双方状态、能力变化、太晶及场地侧条件，必须明确输入并核对回读，不能把NPC假设当真实数值。解释报告条件不需要另选成员计算，本次未执行新计算。缺少具体报告时不能确认某个数值适用于你的队伍。";
        var tool = Arrays.stream(tools).filter(t -> t.getToolDefinition().name().equals("searchPokemonGuides")).findFirst();
        if (tool.isEmpty()) return summary + "\n\n本次未取得本地攻略原文来源。";
        try {
            String args = new ObjectMapper().writeValueAsString(Map.of("query", "伤害计算 假设 双方配置 条件", "gameReleaseId", context.run().gameReleaseId()));
            String output = tool.get().call(args);
            var evidence = new GuideAnswerEvidence(); evidence.begin(context.run().prompt()); evidence.observe("searchPokemonGuides", output);
            return summary + "\n\n" + evidence.verifiedAnswer(false);
        } catch (com.vio.vioaiagent.observability.TaskBudgetExceededException | com.vio.vioaiagent.observability.ContextBudgetExceededException | java.util.concurrent.CancellationException stopped) { throw stopped; }
        catch (Exception unavailable) { return summary + "\n\n本次本地攻略读取失败，不补写资料引用。"; }
    }

}
