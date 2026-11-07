package com.vio.vioaiagent.observability;

import java.util.*;

/** Monotonic per-attempt readiness, not socket delivery or supplier TTFT. */
public final class RunAttemptTiming {
    private final long registered,entered;
    private Long first;
    private String path;
    private Map<String,Object> frozen;
    public RunAttemptTiming(long registered,long entered){this.registered=registered;this.entered=entered;}
    public synchronized Long answerReady(String path) {
        if(frozen!=null || first!=null || !Set.of("DELTA","BUFFERED").contains(path))return null;
        first=elapsed(registered,System.nanoTime());this.path=path;return first;
    }
    public synchronized Map<String,Object> freeze() {
        if(frozen!=null)return frozen;
        frozen=snapshot();return frozen;
    }
    public synchronized Map<String,Object> snapshot() {
        if(frozen!=null)return frozen;
        var result=new LinkedHashMap<String,Object>();result.put("schemaVersion","response-timing-v1");
        result.put("scope","EXECUTION_ATTEMPT");result.put("origin","TASK_REGISTERED");
        result.put("dispatchWaitMs",elapsed(registered,entered));result.put("firstAnswerReadyMs",first);
        result.put("answerPath",path);result.put("observedMs",elapsed(registered,System.nanoTime()));
        result.put("deliveryMeasured",false);return Collections.unmodifiableMap(result);
    }
    private static long elapsed(long from,long to){return Math.max(0,(to-from)/1_000_000);}
}
