package com.vio.vioaiagent.observability;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.api.OpenAiApi;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;

/** Select only numeric provider fields. DefaultUsage getters substitute missing values with zero. */
public record ProviderTokenUsage(long inputTokens,long outputTokens,long totalTokens,String source) {
    public static ProviderTokenUsage read(Usage usage) {
        if(usage==null)return null;
        Object nativeUsage=usage.getNativeUsage();
        Integer input,output,total;
        String source;
        if(nativeUsage instanceof OpenAiApi.Usage value) {
            input=value.promptTokens();output=value.completionTokens();total=value.totalTokens();source="OPENAI_COMPATIBLE_RESPONSE";
        } else if(nativeUsage instanceof DashScopeApi.TokenUsage value) {
            input=value.inputTokens();output=value.outputTokens();total=value.totalTokens();source="DASHSCOPE_RESPONSE";
        } else return null;
        if(input==null || output==null || input<0 || output<0)return null;
        long sum=(long)input+output;
        if(total!=null && (total<0 || total.longValue()!=sum))return null;
        return new ProviderTokenUsage(input,output,sum,source);
    }
}
