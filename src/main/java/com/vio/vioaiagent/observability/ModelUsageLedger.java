package com.vio.vioaiagent.observability;

import org.springframework.ai.chat.model.ChatResponse;
import java.util.*;

/** One physical execution attempt, isolated from other runs. Never stores prompts, outputs or native metadata. */
public final class ModelUsageLedger {
    private static final int MAX_RECORDS=64;
    private final String attemptId=UUID.randomUUID().toString();
    private final List<Call> calls=new ArrayList<>();
    private int started;
    private boolean attached,frozen,noModelCalls;
    private Map<String,Object> frozenSnapshot;

    public synchronized void attach() {if(!frozen)attached=true;}
    public synchronized void noModelCalls() {if(!frozen && started==0){attached=true;noModelCalls=true;}}
    public synchronized Call begin(String mode,String configuredModel) {
        if(frozen)throw new java.util.concurrent.CancellationException("Model observation scope closed");
        started++;
        Call call=new Call(started,mode,safeModel(configuredModel));
        if(calls.size()<MAX_RECORDS)calls.add(call);
        return call;
    }
    public synchronized void response(Call call,ChatResponse response) {
        if(frozen || call.finished || response==null)return;
        try {
            var metadata=response.getMetadata();
            if(metadata!=null) {
                String model=safeModel(metadata.getModel());
                if(model!=null)call.responseModel=model;
                var reported=metadata.getUsage();
                ProviderTokenUsage usage=ProviderTokenUsage.read(reported);
                if(usage!=null && call.lastUsage!=null && (usage.inputTokens()<call.lastUsage.inputTokens()
                        || usage.outputTokens()<call.lastUsage.outputTokens()))call.usageInvalid=true;
                if(reported!=null && reported.getNativeUsage()!=null)
                    call.lastUsage=usage; // Replace cumulative snapshots; malformed final usage clears prior usage.
            }
        } catch(RuntimeException invalidMetadata) {
            // Optional observation cannot turn an otherwise valid model response into a failed answer.
            call.usageInvalid=true;call.lastUsage=null;
        }
        if("STREAM".equals(call.mode) && call.firstTextMs==null && response.getResult()!=null
                && response.getResult().getOutput()!=null && response.getResult().getOutput().getText()!=null
                && !response.getResult().getOutput().getText().isEmpty())call.firstTextMs=elapsed(call.startedAt);
    }
    public synchronized void finish(Call call,String outcome) {
        if(frozen || call.finished)return;
        call.finished=true;call.outcome=outcome;call.elapsedMs=elapsed(call.startedAt);
    }
    public synchronized Map<String,Object> freeze() {if(frozenSnapshot==null){frozen=true;frozenSnapshot=snapshot();}return frozenSnapshot;}
    public synchronized Map<String,Object> snapshot() {
        if(frozenSnapshot!=null)return frozenSnapshot;
        List<Map<String,Object>> rows=new ArrayList<>();long input=0,output=0,total=0,duration=0;int known=0;
        for(Call call:calls) {
            var row=new LinkedHashMap<String,Object>();
            row.put("callNo",call.number);row.put("mode",call.mode);row.put("outcome",call.outcome);
            row.put("configuredModelId",call.configuredModel);row.put("responseModelId",call.responseModel);
            row.put("durationMs",call.finished?call.elapsedMs:elapsed(call.startedAt));row.put("firstTextDeltaMs",call.firstTextMs);
            duration+=(long)row.get("durationMs");
            boolean complete="RETURNED".equals(call.outcome) && call.lastUsage!=null && !call.usageInvalid;
            row.put("usageStatus",complete?"PROVIDER_REPORTED":"UNKNOWN");
            row.put("tokens",complete?tokens(call.lastUsage):unknownTokens());
            if(complete) {known++;input+=call.lastUsage.inputTokens();output+=call.lastUsage.outputTokens();total+=call.lastUsage.totalTokens();}
            rows.add(Collections.unmodifiableMap(row));
        }
        boolean complete=attached && (started>0 && known==started || started==0 && noModelCalls);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("schemaVersion","model-usage-v1");result.put("attemptId",attemptId);result.put("scope","EXECUTION_ATTEMPT");
        result.put("coverage",attached?"INSTRUMENTED":"NOT_INSTRUMENTED");result.put("modelCallCount",started);
        result.put("callsWithUsage",known);result.put("usageStatus",complete?"COMPLETE":known>0?"PARTIAL":"UNKNOWN");
        result.put("totals",complete?tokens(new ProviderTokenUsage(input,output,total,"PROVIDER_REPORTED_SUM")):unknownTokens());
        result.put("knownSubtotal",tokens(new ProviderTokenUsage(input,output,total,"COMPLETE_CALLS_ONLY")));
        result.put("modelDurationMs",duration);result.put("calls",List.copyOf(rows));result.put("recordsTruncated",started>MAX_RECORDS);
        result.put("streamAggregation","LAST_REPORTED_SNAPSHOT");result.put("httpRequestCount","UNKNOWN");
        result.put("cost",Map.of("status","UNKNOWN","reason","NO_PRICE_VERSION_OR_BILLING_BREAKDOWN"));
        return Collections.unmodifiableMap(result);
    }
    private static Map<String,Object> tokens(ProviderTokenUsage usage) {
        return Map.of("inputTokens",usage.inputTokens(),"outputTokens",usage.outputTokens(),"totalTokens",usage.totalTokens(),"source",usage.source());
    }
    private static Map<String,Object> unknownTokens() {
        Map<String,Object> map=new LinkedHashMap<>();map.put("inputTokens",null);map.put("outputTokens",null);map.put("totalTokens",null);map.put("source","UNKNOWN");return Collections.unmodifiableMap(map);
    }
    private static String safeModel(String value){return value!=null && value.matches("[A-Za-z0-9_.:/-]{1,100}")?value:null;}
    private static long elapsed(long since){return Math.max(0,(System.nanoTime()-since)/1_000_000);}
    public static final class Call {
        private final int number;
        private final String mode,configuredModel;
        private final long startedAt=System.nanoTime();
        private String responseModel,outcome="IN_FLIGHT";
        private Long firstTextMs;
        private long elapsedMs;
        private boolean finished;
        private boolean usageInvalid;
        private ProviderTokenUsage lastUsage;
        private Call(int number,String mode,String model){this.number=number;this.mode=mode;this.configuredModel=model;}
    }
}
