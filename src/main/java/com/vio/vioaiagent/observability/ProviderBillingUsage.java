package com.vio.vioaiagent.observability;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.api.OpenAiApi;
import java.util.*;

/** Optional native partitions. Reasoning is a subset of output, never an extra output charge. */
public record ProviderBillingUsage(Long cachedInputTokens, Long uncachedInputTokens,
                                   Long reasoningOutputTokens, String status) {
    public static ProviderBillingUsage read(Usage raw, ProviderTokenUsage totals) {
        if (totals == null) return new ProviderBillingUsage(null,null,null,"UNKNOWN");
        if (!(raw.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage)) {
            return new ProviderBillingUsage(null,null,null,"UNKNOWN");
        }
        var prompt=nativeUsage.promptTokensDetails();
        var output=nativeUsage.completionTokenDetails();
        Long cached=prompt==null || prompt.cachedTokens()==null?null:prompt.cachedTokens().longValue();
        Long reasoning=output==null || output.reasoningTokens()==null?null:output.reasoningTokens().longValue();
        if (cached!=null && (cached<0 || cached>totals.inputTokens())
                || reasoning!=null && (reasoning<0 || reasoning>totals.outputTokens())) {
            return new ProviderBillingUsage(null,null,null,"INVALID");
        }
        boolean unsupported=prompt!=null && nonzero(prompt.audioTokens()) || output!=null
                && (nonzero(output.audioTokens()) || nonzero(output.acceptedPredictionTokens()) || nonzero(output.rejectedPredictionTokens()));
        return new ProviderBillingUsage(cached,cached==null?null:totals.inputTokens()-cached,reasoning,
                unsupported?"UNSUPPORTED":cached==null && reasoning==null?"UNKNOWN":"REPORTED");
    }
    private static boolean nonzero(Integer value) { return value!=null && value!=0; }
    public Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>();
        result.put("schemaVersion","billing-usage-v1");result.put("status",status);
        result.put("cachedInputTokens",cachedInputTokens);result.put("uncachedInputTokens",uncachedInputTokens);
        result.put("reasoningOutputTokens",reasoningOutputTokens);result.put("reasoningIncludedInOutput",true);
        return Collections.unmodifiableMap(result);
    }
}
