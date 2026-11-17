package com.vio.vioaiagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReferenceFailureAnswerTest {
    private static final String QUERY="qa-no-such-pokemon";
    private static final String ARGS="{\"query\":\"qa-no-such-pokemon\",\"dataReleaseId\":\"pokeapi-reference-v1\"}";
    private GuideAnswerEvidence missing() {
        var e=new GuideAnswerEvidence();e.begin("请介绍测试用不存在的宝可梦"+QUERY+"，并引用资料。");
        e.observe("lookupPokemonReference","[tool.error] POKEMON_REFERENCE_NOT_FOUND",ARGS);return e;
    }
    @Test void resourceFailureCannotOverwriteBoundSpeciesNotFound(){
        var e=missing();e.observe("lookupPokemonResource","[tool.error] unknown resource","{\"kind\":\"ability\",\"slug\":\"qa-no-such-pokemon\",\"dataReleaseId\":\"pokeapi-reference-v1\"}");
        String answer=e.verifiedAnswer();assertTrue(answer.contains("未找到宝可梦“"+QUERY+"”"));assertFalse(answer.contains("明确资源英文标识"));
        assertFalse(answer.contains("官方图鉴中不存在"));
    }
    @Test void standaloneReplyUsesFailureEvidenceWithoutModelEvenWithNoResourceCall(){
        var e=missing();var model=mock(ChatModel.class);var agent=new ToolCallAgent(new ToolCallback[0]);
        agent.setGuideAnswerEvidence(e);agent.setCurrentUserRequest("请介绍"+QUERY);agent.setChatClient(ChatClient.builder(model).build());clearInvocations(model);
        var chunks=new ArrayList<String>();agent.setAnswerDeltaSink(chunks::add);
        String answer=agent.summarizeAnswer();assertTrue(answer.contains(QUERY));assertEquals(answer,String.join("",chunks));verifyNoInteractions(model);
    }
    @Test void transportAndVersionFailuresNeverBecomeNotFound(){
        for(String failure:List.of("[tool.error] timeout","[tool.denied] blocked","invalid JSON")) {
            var e=new GuideAnswerEvidence();e.begin("查询"+QUERY);e.observe("lookupPokemonReference",failure,ARGS);
            assertTrue(e.verifiedAnswer().contains("未能读取宝可梦"));assertFalse(e.verifiedAnswer().contains("未找到宝可梦"));
        }
    }
    @Test void soleMissingTargetUsesOneBoundedExplanation() {
        String answer = missing().verifiedAnswer();
        assertTrue(answer.contains("未找到"));
        assertFalse(answer.contains("资料读取提醒"));
        assertFalse(answer.contains("请稍后重试"));
    }
    @Test void resourceOnlyErrorsAndUnboundTargetsCannotCreateSpeciesAbsence(){
        var e=new GuideAnswerEvidence();e.begin("解释激流特性");e.observe("lookupPokemonResource","[tool.error] POKEMON_REFERENCE_NOT_FOUND",ARGS);
        assertFalse(e.verifiedAnswer().contains("未找到宝可梦"));
        e.observe("lookupPokemonReference","[tool.error] POKEMON_REFERENCE_NOT_FOUND",ARGS);
        assertFalse(e.verifiedAnswer().contains(QUERY));
    }
    @Test void newQuestionDoesNotReusePreviousFailedTarget(){
        var e=missing();e.begin("查询杰尼龟");assertFalse(e.verifiedAnswer().contains(QUERY));
    }
    @Test void successfulRetryClearsOnlyTheMatchingSpeciesFailure() throws Exception {
        var e=new GuideAnswerEvidence();e.begin("查询杰尼龟和"+QUERY);
        e.observe("lookupPokemonReference","[tool.error] POKEMON_REFERENCE_NOT_FOUND","{\"query\":\"杰尼龟\",\"dataReleaseId\":\"pokeapi-reference-v1\"}");
        e.observe("lookupPokemonReference","[tool.error] POKEMON_REFERENCE_NOT_FOUND",ARGS);
        e.observe("lookupPokemonReference",BasicFactAnswerTest.detail(43),"{\"query\":\"squirtle\",\"dataReleaseId\":\"pokeapi-reference-v1\"}");
        String answer=e.verifiedAnswer();assertFalse(answer.contains("未找到宝可梦“杰尼龟”"));assertTrue(answer.contains("未找到宝可梦“"+QUERY+"”"));assertTrue(answer.contains("44"));
    }
}
