package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationRecallReplyTest {
    AgentRunContext context(String prompt,List<Message> modelHistory,List<Message> dialogueHistory) {
        var run=AgentRun.create("recall-run","s","c","owner",prompt,"pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(30));
        return new AgentRunContext(run,null,modelHistory,RunMutationBoundary.denied(),new com.vio.vioaiagent.observability.ModelUsageLedger(),ConversationConstraints.defaults(),dialogueHistory);
    }
    @Test void backgroundAcknowledgementsAreExactAndNeverCallAModel() {
        var model=mock(ChatModel.class);var executor=new DefaultRunExecutor(model,new ToolCallback[0],null,null,null,null,false);
        for(int index=1;index<=12;index++)assertEquals("收到",executor.execute(context("这是背景备注第"+index+"条：只回复收到，不新增目标。",List.of(),List.of())));
        assertEquals("收到",executor.execute(context("先记录背景，我目前只讨论阿枫初战，本轮只回复收到。",List.of(),List.of())));
        verifyNoInteractions(model);
        assertTrue(ConversationRecallReply.reply(context("这是背景备注：只回复收到，然后修改队伍。",List.of(),List.of())).isEmpty());
        assertTrue(ConversationRecallReply.reply(context("先记录背景，我目前只讨论阿枫初战，本轮只回复收到，然后保存队伍。",List.of(),List.of())).isEmpty());
    }
    @Test void recalledTopicComesFromPersistedUserMessagesEvenAfterTheModelWindowWasTrimmed() {
        var history=new ArrayList<Message>();history.add(new UserMessage("问一条培养通用建议。"));history.add(new AssistantMessage("热门榜与队伍都很强"));
        for(int index=1;index<=9;index++){history.add(new UserMessage("这是背景备注第"+index+"条：只回复收到，不新增目标。"));history.add(new AssistantMessage("收到"));}
        var model=mock(ChatModel.class);
        String answer=new DefaultRunExecutor(model,new ToolCallback[0],null,null,null,null,false).execute(context("回到刚才的话题，我之前问的是培养还是热门榜？",history.subList(4,history.size()),history));
        assertTrue(answer.contains("问一条培养通用建议"));assertFalse(answer.contains("热门榜与队伍都很强"));verifyNoInteractions(model);
    }
    @Test void assistantOnlyOrAnotherConversationCannotSupplyAnInventedUserTopic() {
        var ctx=context("我之前问的是什么？",List.of(),List.of(new AssistantMessage("用户问过培养")));
        // This wording is handled only when the program can identify a bounded recall request.
        assertTrue(ConversationRecallReply.reply(ctx).orElseThrow().contains("无法定位"));
        assertTrue(ConversationRecallReply.reply(context("之前问的是培养吗？",List.of(),List.of())).orElseThrow().contains("无法定位"));
    }
}
