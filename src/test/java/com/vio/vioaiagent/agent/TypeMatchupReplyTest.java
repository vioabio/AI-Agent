package com.vio.vioaiagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TypeMatchupReplyTest {
    static final String ORIGINAL="地面招式打水属性，以及水／超能力双属性，基础属性倍率分别是多少？不考虑特性、道具和太晶。";
    static final String MULTIPLE_ATTACKS="电和草属性招式攻击水／超能力双属性，各是多少倍？";
    @Test void reportedMultipleAttackQuestionUsesTheCorrectFactorForEachDefendingType(){
        for(String question:List.of(MULTIPLE_ATTACKS,
                "电属性和草属性招式攻击水/超能力双属性，分别是多少倍？",
                "电系与草系对水/超能各自为多少倍？",
                "电、草招式分别攻击水/超能力，倍率各是多少？")){
            String answer=TypeMatchupReply.reply(question).orElseThrow();
            assertTrue(answer.contains("| 电 | 水／超能力 | 2.0× × 1.0× | 2.0× |"),question);
            assertTrue(answer.contains("| 草 | 水／超能力 | 2.0× × 1.0× | 2.0× |"),question);
            assertFalse(answer.contains("0.5"),question);
        }
    }
    @Test void multipleAttacksAndTargetsKeepImmunityResistanceAndWeaknessSeparate(){
        String answer=TypeMatchupReply.reply("地面和草打电/飞行以及火/岩石，分别是多少倍？").orElseThrow();
        assertTrue(answer.contains("| 地面 | 电／飞行 | 2.0× × 0.0× | 0.0× |"));
        assertTrue(answer.contains("| 地面 | 火／岩石 | 2.0× × 2.0× | 4.0× |"));
        assertTrue(answer.contains("| 草 | 电／飞行 | 1.0× × 0.5× | 0.5× |"));
        assertTrue(answer.contains("| 草 | 火／岩石 | 0.5× × 2.0× | 1.0× |"));
    }
    @Test void multipleAttackParsingRequiresACompleteUnambiguousPureTypeQuestion(){
        for(String question:List.of("电和草攻击水/超能力，考虑避雷针特性各是多少倍？",
                "电和草攻击水/超能力，各是多少倍？再推荐一支队伍。",
                "电和未知属性攻击水/超能力，各是多少倍？",
                "电和草攻击水和超能力双属性，各是多少倍？",
                "火/飞行打草的倍率是多少？",
                "火、水、电、草、冰、地面、飞行对草各是多少倍？"))
            assertTrue(TypeMatchupReply.reply(question).isEmpty(),question);
    }
    @Test void finalAnswerReplacesInconsistentModelExplanationWithComputedFactors(){
        var model=mock(ChatModel.class);var agent=new ToolCallAgent(new ToolCallback[0]);
        agent.setChatClient(ChatClient.builder(model).build());clearInvocations(model);
        agent.setCurrentUserRequest(MULTIPLE_ATTACKS);
        agent.getMessageList().add(new org.springframework.ai.chat.messages.AssistantMessage("电和草对超能力0.5倍，2×0.5=2倍。"));
        var deltas=new ArrayList<String>();agent.setAnswerDeltaSink(deltas::add);
        String answer=agent.summarizeAnswer();
        assertEquals(TypeMatchupReply.reply(MULTIPLE_ATTACKS).orElseThrow(),answer);
        assertEquals(answer,String.join("",deltas));assertFalse(answer.contains("0.5"));
        verifyNoInteractions(model);
    }
    @Test void colloquialTimesDamageAndPresentationInstructionStillUseTheTypeChart() {
        for (String question : List.of("火属性招式攻击草属性是几倍伤害？请查询属性工具后用中文简短回答。",
                "冰打地面/龙几倍伤害？", "火打草是几倍？")) {
            String answer = TypeMatchupReply.reply(question).orElseThrow();
            assertTrue(answer.contains(question.startsWith("冰") ? "4.0×" : "2.0×"));
            assertFalse(answer.contains("宝可梦查询结果"));
        }
        assertTrue(TypeMatchupReply.reply("火打草是几倍伤害？然后推荐队伍").isEmpty());
    }
    @Test void prefixAndTimesWordingUsesDualProduct(){
        assertTrue(TypeMatchupReply.reply("基础属性关系下，冰招式打草/钢是多少倍？").orElseThrow().contains("2.0× × 0.5× | 1.0×"));
        assertTrue(TypeMatchupReply.reply("不考虑特性道具太晶，电属性招式打水/飞行的倍率是多少？").orElseThrow().contains("4.0×"));
    }
    @Test void psychicShortNameKeepsDualImmunity(){
        assertTrue(TypeMatchupReply.reply("基础属性关系下，幽灵招式打一般/超能是多少？").orElseThrow().contains("0.0× × 2.0× | 0.0×"));
    }
    @Test void originalQuestionKeepsOnlyComputedMatchupsAndNoGeneratedAdvice(){
        String answer=TypeMatchupReply.reply(ORIGINAL).orElseThrow();
        assertTrue(answer.contains("| 地面 | 水 | 1.0× | 1.0× |"));
        assertTrue(answer.contains("| 地面 | 水／超能力 | 1.0× × 1.0× | 1.0× |"));
        assertFalse(answer.contains("抵消"));assertFalse(answer.contains("电属性对水"));
    }
    @Test void dualTypeWeaknessImmunityAndStackingAreCalculatedIndependently(){
        for(String attack:List.of("电","草"))
            assertTrue(TypeMatchupReply.reply(attack+"打水/超能力的倍率是多少？").orElseThrow().contains("2.0× × 1.0× | 2.0×"));
        assertTrue(TypeMatchupReply.reply("地面打电/飞行的倍率是多少？").orElseThrow().contains("2.0× × 0.0× | 0.0×"));
        assertTrue(TypeMatchupReply.reply("冰打地面/龙的倍率是多少？").orElseThrow().contains("2.0× × 2.0× | 4.0×"));
        assertTrue(TypeMatchupReply.reply("草打火/飞行的倍率是多少？").orElseThrow().contains("0.5× × 0.5× | 0.25×"));
    }
    @Test void unsupportedMixedAndMechanicQuestionsKeepTheirExistingPaths(){
        for(String q:List.of("水打火的伤害是多少？","地面打水属性，考虑特性和太晶，推荐队伍","电打水和超能力双属性的倍率是多少","地面打水/水双属性的倍率是多少","皮卡丘打杰尼龟倍率多少","解释为什么地面打水是普通效果"))
            assertTrue(TypeMatchupReply.reply(q).isEmpty(),q);
    }
    @Test void standaloneFinalAnswerCannotAppendModelInventedTypeAdvice(){
        var model=mock(ChatModel.class);var agent=new ToolCallAgent(new ToolCallback[0]);
        agent.setChatClient(ChatClient.builder(model).build());clearInvocations(model);
        agent.setCurrentUserRequest(ORIGINAL);var deltas=new ArrayList<String>();agent.setAnswerDeltaSink(deltas::add);
        String answer=agent.summarizeAnswer();assertEquals(TypeMatchupReply.reply(ORIGINAL).orElseThrow(),answer);
        assertEquals(answer,String.join("",deltas));verifyNoInteractions(model);
    }
}
