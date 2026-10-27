package com.vio.vioaiagent.observability;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.*;
import org.springframework.ai.chat.messages.*;

/** CL100K is a local proxy for the configured DeepSeek/DashScope model, not its tokenizer. */
public final class ContextTokenEstimator {
    public static final String ID="cl100k-base-proxy-v1";
    private static final Encoding ENCODING=Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    private final int safetyPercent;
    public ContextTokenEstimator(int safetyPercent){
        if(safetyPercent<100 || safetyPercent>200)throw new IllegalArgumentException("估算余量无效");
        this.safetyPercent=safetyPercent;
    }
    public int text(String value) {
        return estimateText(value).budgetTokens();
    }
    public Estimate estimateText(String value) {
        if(value==null || value.isEmpty())return new Estimate(0,0);
        if(value.length()>262144)throw new ContextBudgetExceededException();
        int base=ENCODING.countTokensOrdinary(value);
        return new Estimate(base,Math.toIntExact(((long)base*safetyPercent+99)/100));
    }
    public int message(Message message) {
        return estimateMessage(message).budgetTokens();
    }
    public Estimate estimateMessage(Message message) {
        if(message instanceof UserMessage user && !user.getMedia().isEmpty())throw new ContextBudgetExceededException();
        Estimate count=new Estimate(16,16).plus(estimateText(message.getText()));
        if(message instanceof AssistantMessage assistant)for(var call:assistant.getToolCalls())
            count=count.plus(new Estimate(16,16)).plus(estimateText(call.id())).plus(estimateText(call.name())).plus(estimateText(call.arguments()));
        if(message instanceof ToolResponseMessage tools) {
            // ToolResponseMessage.getText() can be a projection; count each actual response exactly once here.
            count=new Estimate(16,16);
            for(var response:tools.getResponses())count=count.plus(new Estimate(16,16)).plus(estimateText(response.id()))
                    .plus(estimateText(response.name())).plus(estimateText(response.responseData()));
        }
        return count;
    }
    /** Base still includes the same fixed framing allowance; neither value is a provider tokenizer result. */
    public record Estimate(int baseTokens,int budgetTokens) {
        public Estimate plus(Estimate other) {
            return new Estimate(Math.addExact(baseTokens,other.baseTokens),Math.addExact(budgetTokens,other.budgetTokens));
        }
    }
}
