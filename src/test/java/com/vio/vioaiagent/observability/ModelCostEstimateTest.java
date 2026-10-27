package com.vio.vioaiagent.observability;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.openai.api.OpenAiApi;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelCostEstimateTest {
    @TempDir Path directory;
    static Path fixture() throws Exception {return Path.of(ModelCostEstimateTest.class.getResource("/pricing/fixture-prices.json").toURI());}
    static ModelPriceCatalog catalog() throws Exception{return ModelPriceCatalog.load(fixture().toString(),"offline-test","deepseek");}
    static ChatResponse response(Integer cached,Integer reasoning) {
        var usage=new OpenAiApi.Usage(100,1000,1100,new OpenAiApi.Usage.PromptTokensDetails(null,cached),
                new OpenAiApi.Usage.CompletionTokenDetails(reasoning,null,null,null));
        return new ChatResponse(List.of(new Generation(new AssistantMessage("private answer"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1000,100,1100,usage)).build());
    }
    @SuppressWarnings("unchecked") static Map<String,Object> cost(ModelUsageLedger ledger){return (Map<String,Object>)ledger.snapshot().get("cost");}
    @Test void splitCacheQuoteUsesDecimalRatesAndDoesNotAddReasoningToOutputAgain() throws Exception {
        var ledger=new ModelUsageLedger(null,catalog());ledger.attach();
        var call=ledger.begin("CALL","configured-other",null,"fixture-model");ledger.response(call,response(400,30));ledger.finish(call,"RETURNED");
        assertEquals("ESTIMATED",cost(ledger).get("status"));assertEquals("0.00094",cost(ledger).get("amount"));
        assertEquals(1100L,((Map<?,?>)ledger.snapshot().get("totals")).get("totalTokens"));
        var row=(Map<?,?>)((List<?>)ledger.freeze().get("calls")).getFirst();
        assertEquals(30L,((Map<?,?>)row.get("billingUsage")).get("reasoningOutputTokens"));
        var quote=(Map<?,?>)row.get("cost");var price=(Map<?,?>)quote.get("price");
        assertEquals("fixture-model",price.get("requestedModelId"));assertEquals("test-fixture-v1",price.get("version"));
        assertFalse(ledger.snapshot().toString().contains("private answer"));
    }
    @Test void missingOrInvalidPartitionsAreUnknownWhileFlatContractCanUseReportedTotalInput() throws Exception {
        for(Integer cached:Arrays.asList(null,-1,1001)) {
            var ledger=new ModelUsageLedger(null,catalog());ledger.attach();var call=ledger.begin("CALL","fixture-model");
            ledger.response(call,response(cached,30));ledger.finish(call,"RETURNED");
            assertEquals("UNKNOWN",cost(ledger).get("status"));assertEquals("COMPLETE",ledger.snapshot().get("usageStatus"));
        }
        var ledger=new ModelUsageLedger(null,catalog());ledger.attach();var call=ledger.begin("CALL","fixture-cny");
        ledger.response(call,response(null,null));ledger.finish(call,"RETURNED");
        assertEquals("0.0024",cost(ledger).get("amount"));assertEquals("CNY",cost(ledger).get("currency"));
    }
    @Test void zeroReportedCacheIsKnownButMissingReasoningDoesNotPreventUniformOutputPricing() throws Exception {
        var ledger=new ModelUsageLedger(null,catalog());ledger.attach();var call=ledger.begin("CALL","fixture-model");
        ledger.response(call,response(0,null));ledger.finish(call,"RETURNED");
        assertEquals("0.0013",cost(ledger).get("amount"));
        var details=ProviderBillingUsage.read(response(0,null).getMetadata().getUsage(),new ProviderTokenUsage(1000,100,1100,"fixture"));
        assertEquals(0L,details.cachedInputTokens());assertNull(details.reasoningOutputTokens());
    }
    @Test void repeatedChunksDoNotMultiplyCostsAndPartitionRegressionKeepsCostUnknown() throws Exception {
        var ledger=new ModelUsageLedger(null,catalog());ledger.attach();var call=ledger.begin("STREAM","fixture-model");
        ledger.response(call,response(400,10));ledger.response(call,response(400,30));ledger.response(call,response(400,30));ledger.finish(call,"RETURNED");
        assertEquals("0.00094",cost(ledger).get("amount"));
        var bad=new ModelUsageLedger(null,catalog());bad.attach();var other=bad.begin("STREAM","fixture-model");
        bad.response(other,response(400,30));bad.response(other,response(300,20));bad.finish(other,"RETURNED");
        assertEquals("UNKNOWN",cost(bad).get("status"));assertEquals("COMPLETE",bad.snapshot().get("usageStatus"));
    }
    @Test void failedCallsStayInDenominatorAndMixedCurrenciesNeverProduceOneTotal() throws Exception {
        var ledger=new ModelUsageLedger(null,catalog());ledger.attach();var first=ledger.begin("CALL","fixture-model");
        ledger.response(first,response(400,30));ledger.finish(first,"RETURNED");var failed=ledger.begin("CALL","fixture-model");ledger.finish(failed,"FAILED");
        assertEquals("PARTIAL",cost(ledger).get("status"));assertNull(cost(ledger).get("amount"));
        assertEquals("0.00094",cost(ledger).get("knownSubtotal"));assertEquals(2,cost(ledger).get("totalCalls"));
        var mixed=new ModelUsageLedger(null,catalog());mixed.attach();
        for(String model:List.of("fixture-model","fixture-cny")) {var call=mixed.begin("CALL",model);mixed.response(call,response(400,30));mixed.finish(call,"RETURNED");}
        assertEquals("MIXED_CURRENCIES",cost(mixed).get("reason"));assertNull(cost(mixed).get("amount"));
    }
    @Test void catalogIsImmutableAndCallStartFreezesVersionWhileMissingModelsAndExpiredQuotesStayUnknown() throws Exception {
        var path=directory.resolve("prices.json");Files.copy(fixture(),path);
        var loaded=ModelPriceCatalog.load(path.toString(),"offline-test","deepseek");
        assertNotNull(loaded.select("fixture-model",Instant.parse("2026-01-01T00:00:00Z")).price());
        assertNull(loaded.select("fixture-model",Instant.parse("2100-01-01T00:00:00Z")).price());
        assertNull(loaded.select("unknown-model",Instant.now()).price());
        Files.writeString(path,"{}");assertEquals("test-fixture-v1",loaded.policy().get("version"));
        assertThrows(IllegalArgumentException.class,()->ModelPriceCatalog.load(path.toString(),"offline-test","deepseek"));
        var ledger=new ModelUsageLedger(null,loaded);ledger.attach();var call=ledger.begin("CALL","fixture-model");
        var frozen=ledger.freeze();ledger.response(call,response(400,30));ledger.finish(call,"RETURNED");assertSame(frozen,ledger.snapshot());
        assertEquals("UNKNOWN",cost(ledger).get("status"));
    }
    @Test void malformedCatalogsFailClosedAndUnsupportedBillingMetadataDoesNotFailTheAnswer() throws Exception {
        String original=Files.readString(fixture());
        for(String altered:List.of(original.replace("\"1\"","\"-1\""),original.replace("\"0.1\"","\"0.000000001\""),
                original.replace("\"USD\"","\"EUR\""),original.replace("fixture-cny","fixture-model"),original.replace("https://example.invalid/prices","https://user:secret@example.invalid/prices"))) {
            var path=directory.resolve(UUID.randomUUID()+".json");Files.writeString(path,altered);
            var error=assertThrows(IllegalArgumentException.class,()->ModelPriceCatalog.load(path.toString(),"offline-test","deepseek"));
            assertFalse(error.getMessage().contains("secret"));
        }
        var usage=new OpenAiApi.Usage(100,1000,1100,new OpenAiApi.Usage.PromptTokensDetails(1,400),null);
        var reply=new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))),ChatResponseMetadata.builder().usage(new DefaultUsage(1000,100,1100,usage)).build());
        var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(reply);
        var ledger=new ModelUsageLedger(null,catalog());assertSame(reply,new ObservedChatModel(model,ledger).call(new Prompt("hi",ChatOptions.builder().model("fixture-model").build())));
        assertEquals("UNKNOWN",cost(ledger).get("status"));assertEquals("COMPLETE",ledger.snapshot().get("usageStatus"));
    }
}
