package com.vio.vioaiagent.tools.governance;

import java.util.*;

/** One physical callback attempt. Freeze at the caller boundary; interrupt-ignoring callbacks cannot rewrite it. */
public final class ToolAttemptTiming {
    private Long submitted,worker,callback,finished;
    private boolean accepted;
    private Map<String,Object> frozen;
    synchronized void submitted(){if(frozen==null)submitted=System.nanoTime();}
    synchronized void accepted(){if(frozen==null)accepted=true;}
    synchronized void worker(){if(frozen==null)worker=System.nanoTime();}
    synchronized void callback(){if(frozen==null)callback=System.nanoTime();}
    synchronized void finished(long at){if(frozen==null)finished=at;}
    public synchronized Map<String,Object> freeze() {
        if(frozen!=null)return frozen;
        long end=System.nanoTime();
        var result=new LinkedHashMap<String,Object>();
        result.put("accepted",accepted);result.put("callbackStarted",callback!=null);result.put("callbackCompleted",finished!=null);
        result.put("queueWaitMs",accepted?ms(submitted,worker!=null?worker:end):null);
        result.put("authorizationMs",worker!=null?ms(worker,callback!=null?callback:end):null);
        result.put("executionMs",callback!=null?ms(callback,finished!=null?finished:end):null);
        result.put("observedMs",submitted!=null?ms(submitted,end):null);
        frozen=Collections.unmodifiableMap(result);return frozen;
    }
    private static long ms(long from,long to){return Math.max(0,(to-from)/1_000_000);}
    public static Map<String,Object> summarize(List<ToolAttemptTiming> attempts) {
        var rows=attempts.stream().map(ToolAttemptTiming::freeze).toList();
        var result=new LinkedHashMap<String,Object>();
        result.put("schemaVersion","tool-timing-v1");result.put("scope","CALLBACK_ATTEMPTS");result.put("attempts",rows);
        result.put("complete",!rows.isEmpty() && rows.stream().allMatch(row->Boolean.TRUE.equals(row.get("callbackCompleted"))));
        for(String field:List.of("queueWaitMs","authorizationMs","executionMs","observedMs")) {
            long sum=0;boolean known=false;
            for(var row:rows)if(row.get(field) instanceof Number value){sum=Math.addExact(sum,value.longValue());known=true;}
            result.put(field,known?sum:null);
        }
        return Collections.unmodifiableMap(result);
    }
    public static Map<String,Object> bypass(String reason) {
        return Map.of("schemaVersion","tool-timing-v1","scope","BYPASS","reason",reason);
    }
    /** Optional numeric metadata must not retain arbitrary tool/plugin content in history. */
    public static Map<String,Object> safeCopy(Object raw) {
        try {
            if(!(raw instanceof Map<?,?> value) || !"tool-timing-v1".equals(value.get("schemaVersion")))return null;
            if("BYPASS".equals(value.get("scope"))) {
                return Set.of("CACHED_RESULT","IDEMPOTENT_REPLAY").contains(value.get("reason"))?bypass((String)value.get("reason")):null;
            }
            if(!"CALLBACK_ATTEMPTS".equals(value.get("scope")) || !(value.get("attempts") instanceof List<?> rows)
                    || rows.isEmpty() || rows.size()>8)return null;
            List<Map<String,Object>> clean=new ArrayList<>();
            for(Object rawRow:rows) {
                if(!(rawRow instanceof Map<?,?> row))return null;
                var item=new LinkedHashMap<String,Object>();
                for(String flag:List.of("accepted","callbackStarted","callbackCompleted")) {
                    if(!(row.get(flag) instanceof Boolean))return null;item.put(flag,row.get(flag));
                }
                for(String field:List.of("queueWaitMs","authorizationMs","executionMs","observedMs")) {
                    Object number=row.get(field);
                    if(number!=null && (!(number instanceof Number n) || n.longValue()<0 || n.longValue()>604800000
                            || n.doubleValue()!=n.longValue()))return null;
                    item.put(field,number==null?null:((Number)number).longValue());
                }
                boolean started=Boolean.TRUE.equals(item.get("callbackStarted")),accepted=Boolean.TRUE.equals(item.get("accepted"));
                if(Boolean.TRUE.equals(item.get("callbackCompleted")) && !started || started && !accepted
                        || started && (item.get("executionMs")==null || item.get("authorizationMs")==null || item.get("queueWaitMs")==null)
                        || !started && item.get("executionMs")!=null || !accepted && item.get("queueWaitMs")!=null)return null;
                long phaseSum=0;for(String field:List.of("queueWaitMs","authorizationMs","executionMs"))if(item.get(field) instanceof Long n)phaseSum+=n;
                if(item.get("observedMs")==null?phaseSum>0:phaseSum>((Long)item.get("observedMs"))+3)return null;
                clean.add(Collections.unmodifiableMap(item));
            }
            var result=new LinkedHashMap<String,Object>();result.put("schemaVersion","tool-timing-v1");
            result.put("scope","CALLBACK_ATTEMPTS");result.put("attempts",List.copyOf(clean));
            boolean complete=clean.stream().allMatch(row->Boolean.TRUE.equals(row.get("callbackCompleted")));
            if(!Objects.equals(value.get("complete"),complete))return null;result.put("complete",complete);
            for(String field:List.of("queueWaitMs","authorizationMs","executionMs","observedMs")) {
                Long sum=null;for(var row:clean)if(row.get(field) instanceof Long number)sum=(sum==null?0:sum)+number;
                Object number=value.get(field);
                if(number!=null && (!(number instanceof Number n) || n.doubleValue()!=n.longValue()))return null;
                if(!Objects.equals(number==null?null:((Number)number).longValue(),sum))return null;
                result.put(field,sum);
            }
            return Collections.unmodifiableMap(result);
        } catch(RuntimeException invalid){return null;}
    }
}
