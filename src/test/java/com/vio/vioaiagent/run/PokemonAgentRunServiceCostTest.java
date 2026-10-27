package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.ContextBudgetProperties;
import com.vio.vioaiagent.observability.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.openai.api.OpenAiApi;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PokemonAgentRunServiceCostTest {
    @TempDir Path directory;
    private static ChatModel model() {
        var model=mock(ChatModel.class);when(model.getDefaultOptions()).thenReturn(ChatOptions.builder().model("fixture-model").build());
        var usage=new OpenAiApi.Usage(100,1000,1100,new OpenAiApi.Usage.PromptTokensDetails(null,400),new OpenAiApi.Usage.CompletionTokenDetails(30,null,null,null));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("答复"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1000,100,1100,usage)).build()));
        return model;
    }
    private static CreateRunCommand command(){return new CreateRunCommand("owner",null,null,"你好","sv-1","singles","rules-1","zh-CN",null,null,Duration.ofSeconds(20));}
    @SuppressWarnings("unchecked") private static Map<String,Object> usage(AgentEvent event){return (Map<String,Object>)event.payload().get("modelUsage");}
    @SuppressWarnings("unchecked") private static Map<String,Object> cost(Map<String,Object> usage){return (Map<String,Object>)usage.get("cost");}
    @Test void completedRunAndReopenedSqliteHistoryKeepThePriceVersionAndDecimalEstimate() throws Exception {
        var model=model();var executor=new DefaultRunExecutor(model,new org.springframework.ai.tool.ToolCallback[0],null,null,null,null,false);
        try(var f=new Fixture(executor)) {
            var run=f.service.createRun(command());var terminal=RunTestAwait.awaitTerminalEvent(f.events,run.runId());
            assertEquals(AgentEventType.RUN_COMPLETED,terminal.type());assertEquals("0.00188",cost(usage(terminal)).get("amount"));
            assertEquals(2200L,((Map<?,?>)usage(terminal).get("totals")).get("totalTokens"));
            var reopened=new SqliteLocalHistoryRepository(f.path,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
            var answer=reopened.findByConversationForUser(run.conversationId(),"owner").getLast();
            var attempts=(List<?>)answer.metadata().get("modelUsageAttempts");
            assertEquals("0.00188",cost((Map<String,Object>)attempts.getFirst()).get("amount"));
            var calls=(List<?>)((Map<?,?>)attempts.getFirst()).get("calls");
            assertEquals("test-fixture-v1",((Map<?,?>)((Map<?,?>)((Map<?,?>)calls.getFirst()).get("cost")).get("price")).get("version"));
            assertTrue(reopened.findByConversationForUser(run.conversationId(),"other-owner").isEmpty());
        }
    }
    @Test void approvalConfirmationAddsNoModelChargeAndEarlierFrozenQuoteStaysInHistory() throws Exception {
        var model=model();var executions=new AtomicInteger();
        RunExecutor executor=context->{
            if(executions.incrementAndGet()==1) {
                var ledger=context.modelUsage();
                new ObservedChatModel(model,ledger,new ModelContextBudget(ContextBudgetProperties.defaults(),List.of(),ledger)).call(new Prompt("fixture"));
                var parent=ToolExecutionContextHolder.current();
                parent.eventSink().accept(new ToolGovernanceEvent("approval.required",parent.runId(),"fixture","event",Instant.now(),Map.of("approvalId","fixture")));
                return "等待确认";
            }
            context.modelUsage().noModelCalls();return "已确认";
        };
        try(var f=new Fixture(executor)) {
            var run=f.service.createRun(command());long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(f.events.findAfter(run.runId(),0).stream().noneMatch(e->e.type()==AgentEventType.USAGE) && System.nanoTime()<deadline)Thread.sleep(10);
            var before=f.events.findAfter(run.runId(),0).stream().filter(e->e.type()==AgentEventType.USAGE).findFirst().orElseThrow().payload();
            f.service.resolveApproval(run.runId(),"owner",true,"fixture approval");
            var terminal=RunTestAwait.awaitTerminalEvent(f.events,run.runId());assertEquals(AgentEventType.RUN_COMPLETED,terminal.type());
            assertEquals("0.00094",cost(before).get("amount"));assertEquals("NO_MODEL_CALLS",cost(usage(terminal)).get("reason"));
            var answer=f.history.findByConversationForUser(run.conversationId(),"owner").getLast();
            var attempts=(List<?>)answer.metadata().get("modelUsageAttempts");assertEquals(2,attempts.size());
            assertEquals("0.00094",cost((Map<String,Object>)attempts.getFirst()).get("amount"));verify(model,times(1)).call(any(Prompt.class));
        }
    }
    private final class Fixture implements AutoCloseable {
        final String path=directory.resolve(UUID.randomUUID()+".sqlite").toString();
        final InMemoryEventRepository events=new InMemoryEventRepository();
        final SqliteLocalHistoryRepository history=new SqliteLocalHistoryRepository(path,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        final PokemonAgentRunService service;
        Fixture(RunExecutor executor) throws Exception {
            service=new PokemonAgentRunService(new InMemoryRunRepository(),events,history,history,executor,Executors.newSingleThreadExecutor(),Executors.newSingleThreadScheduledExecutor());
            var prices=ModelPriceCatalog.load(Path.of(getClass().getResource("/pricing/fixture-prices.json").toURI()).toString(),"offline-test","deepseek");
            service.setModelPriceCatalog(prices);
        }
        @Override public void close(){service.shutdown();}
    }
}
