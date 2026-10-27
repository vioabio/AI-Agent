package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Application limits in proxy tokens, not an assertion of a provider's context window. */
@ConfigurationProperties("vio.run.context-budget")
public record ContextBudgetProperties(@DefaultValue("32768") int contextTokens,
                                      @DefaultValue("2048") int outputTokens,
                                      @DefaultValue("4096") int historyTokens,
                                      @DefaultValue("8") int historyTurns,
                                      @DefaultValue("125") int safetyPercent) {
    public ContextBudgetProperties {
        if(contextTokens<512 || contextTokens>1_000_000 || outputTokens<64 || outputTokens>=contextTokens
                || historyTokens<0 || historyTokens>contextTokens-outputTokens || historyTurns<1 || historyTurns>10
                || safetyPercent<100 || safetyPercent>200)throw new IllegalArgumentException("上下文预算配置无效");
    }
    public static ContextBudgetProperties defaults(){return new ContextBudgetProperties(32768,2048,4096,8,125);}
    public int inputTokens(){return contextTokens-outputTokens;}
}
