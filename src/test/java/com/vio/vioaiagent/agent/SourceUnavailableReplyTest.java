package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceUnavailableReplyTest {
    @Test void inaccessibleSourceGetsAnActualSourceLinkWithoutFabricatingTeamsOrNetworkSuccess() {
        String prompt="如果来源页面被拦截，就把使用率前六名拼成热门队伍。";
        var run=AgentRun.create("source-run","s","c","owner",prompt,"pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(30));
        var model=mock(ChatModel.class);
        String answer=new DefaultRunExecutor(model,new ToolCallback[0],null,null,null,null,false).execute(new AgentRunContext(run));
        assertTrue(answer.contains("]("+com.vio.vioaiagent.tools.CompetitiveTeamsTool.SOURCE+")"));
        assertTrue(answer.contains("尚未执行页面抓取"));assertTrue(answer.contains("补造队伍"));verifyNoInteractions(model);
    }
    @Test void ordinaryMetaRequestsKeepTheirExistingRetriever() {
        assertTrue(SourceInstructionReply.reply("当前朱紫OU单打有哪些热门队伍？").isEmpty());
    }
}
