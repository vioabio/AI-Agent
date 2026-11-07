package com.vio.vioaiagent.observability;

import java.util.*;

/** An observed input pair, not an estimator certificate or provider context-window guarantee. */
public final class TokenEstimateComparison {
    private TokenEstimateComparison() { }
    private static final List<String> COMPONENTS=List.of("system","history","toolDefinitions","toolResults","currentMessages");
    public static Map<String,Object> compare(Map<String,Object> context,ProviderTokenUsage usage) {
        try {
            if(context==null || !context.containsKey("baseEstimate"))return unknown("ESTIMATE_NOT_RECORDED");
            if(!"context-estimate-pair-v1".equals(context.get("estimateSchemaVersion"))
                    || !"context-budget-v1".equals(context.get("schemaVersion")) || !ContextTokenEstimator.ID.equals(context.get("estimator"))
                    || !"CHECKED".equals(context.get("status")))return unknown("INVALID_ESTIMATE");
            Long base=total(context.get("baseEstimate")),budget=total(context.get("lastEstimate"));
            if(base==null || budget==null || base>budget)return unknown("INVALID_ESTIMATE");
            var raw=(Map<?,?>)context.get("baseEstimate");var guarded=(Map<?,?>)context.get("lastEstimate");
            for(String key:COMPONENTS)if(((Number)raw.get(key)).longValue()>((Number)guarded.get(key)).longValue())return unknown("INVALID_ESTIMATE");
            if(usage==null)return unknown("USAGE_UNAVAILABLE");
            long nativeInput=usage.inputTokens();
            if(nativeInput>9_007_199_254_740_991L)return unknown("UNSUPPORTED_COUNT");
            if(base<=0 || nativeInput<=0)return unknown("NONPOSITIVE_INPUT");
            var result=new LinkedHashMap<String,Object>();result.put("schemaVersion","token-estimate-comparison-v1");
            result.put("status","PAIR_RECORDED");result.put("baseInputTokens",base);result.put("budgetInputTokens",budget);
            result.put("providerInputTokens",nativeInput);result.put("baseErrorTokens",base-nativeInput);
            result.put("budgetErrorTokens",budget-nativeInput);result.put("budgetCovered",budget>=nativeInput);
            return Collections.unmodifiableMap(result);
        }catch(RuntimeException invalid){return unknown("INVALID_ESTIMATE");}
    }
    private static Long total(Object raw) {
        if(!(raw instanceof Map<?,?> map))return null;
        long sum=0;
        for(String key:COMPONENTS) {
            if(!(map.get(key) instanceof Number n) || n.doubleValue()!=n.longValue() || n.longValue()<0 || n.longValue()>Integer.MAX_VALUE)return null;
            sum=Math.addExact(sum,n.longValue());
        }
        if(!(map.get("inputTokens") instanceof Number n) || n.doubleValue()!=n.longValue() || n.longValue()!=sum)return null;
        return sum;
    }
    private static Map<String,Object> unknown(String reason) {
        return Map.of("schemaVersion","token-estimate-comparison-v1","status","UNKNOWN","reason",reason);
    }
}
