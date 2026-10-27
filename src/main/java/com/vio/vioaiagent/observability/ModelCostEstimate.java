package com.vio.vioaiagent.observability;

import java.math.BigDecimal;
import java.util.*;

/** Decimal-only reference amounts. Missing partitions and failed calls never become zero-cost calls. */
public final class ModelCostEstimate {
    private ModelCostEstimate() { }
    public static Map<String,Object> unknown(String reason) {
        var result=base("UNKNOWN");result.put("reason",reason);result.put("currency",null);result.put("amount",null);
        return Collections.unmodifiableMap(result);
    }
    private static LinkedHashMap<String,Object> base(String status) {
        var result=new LinkedHashMap<String,Object>();result.put("schemaVersion","model-cost-v1");result.put("status",status);
        result.put("kind","REFERENCE_ESTIMATE_NOT_INVOICE");return result;
    }
    public static Map<String,Object> quote(ModelPriceCatalog.Selection selection,ProviderTokenUsage usage,
                                           ProviderBillingUsage details,boolean returned) {
        if(!returned)return unknown("MODEL_CALL_INCOMPLETE");
        if(usage==null)return unknown("USAGE_UNKNOWN");
        if(selection.price()==null)return unknown(selection.reason());
        var price=selection.price();
        if(details==null || Set.of("INVALID","UNSUPPORTED").contains(details.status()))return unknown("INVALID_OR_UNSUPPORTED_BILLING_DETAILS");
        if(usage.inputTokens()>price.maxInputTokens())return unknown("INPUT_TIER_UNSUPPORTED");
        boolean split="SPLIT_CACHE".equals(price.inputAccounting());
        if(split && (details.cachedInputTokens()==null || details.uncachedInputTokens()==null))return unknown("BILLING_DETAILS_MISSING");
        BigDecimal input=amount(split?details.uncachedInputTokens():usage.inputTokens(),price.inputPerMillion());
        BigDecimal cached=split?amount(details.cachedInputTokens(),price.cachedInputPerMillion()):BigDecimal.ZERO;
        BigDecimal output=amount(usage.outputTokens(),price.outputPerMillion());
        var result=base("ESTIMATED");result.put("currency",price.currency());result.put("amount",decimal(input.add(cached).add(output)));
        var identity=new LinkedHashMap<String,Object>();identity.put("version",selection.version());identity.put("sha256",selection.digest());
        identity.put("cardId",price.id());identity.put("provider",price.provider());identity.put("requestedModelId",price.model());
        identity.put("scope",selection.scope());identity.put("sourceType",price.sourceType());identity.put("sourceUrl",price.sourceUrl());
        identity.put("selectedAt",selection.selectedAt().toString());identity.put("validFrom",price.validFrom().toString());identity.put("validUntil",price.validUntil().toString());
        identity.put("inputAccounting",price.inputAccounting());identity.put("unitTokens",1_000_000);
        identity.put("maxInputTokens",price.maxInputTokens());
        identity.put("inputPerMillion",decimal(price.inputPerMillion()));identity.put("cachedInputPerMillion",price.cachedInputPerMillion()==null?null:decimal(price.cachedInputPerMillion()));
        identity.put("outputPerMillion",decimal(price.outputPerMillion()));identity.put("modelBasis","REQUESTED_MODEL_NOT_ATTESTED");
        result.put("price",Collections.unmodifiableMap(identity));
        result.put("components",Map.of("input",decimal(input),"cachedInput",decimal(cached),"output",decimal(output)));
        return Collections.unmodifiableMap(result);
    }
    public static Map<String,Object> summarize(List<Map<String,Object>> costs,int started,boolean instrumented,boolean truncated) {
        if(started==0)return unknown(instrumented?"NO_MODEL_CALLS":"NOT_INSTRUMENTED");
        if(!instrumented)return unknown("NOT_INSTRUMENTED");
        String currency=null;BigDecimal subtotal=BigDecimal.ZERO;int priced=0;
        for(var cost:costs)if("ESTIMATED".equals(cost.get("status"))) {
            String next=(String)cost.get("currency");
            if(currency!=null && !currency.equals(next))return unknown("MIXED_CURRENCIES");
            currency=next;subtotal=subtotal.add(new BigDecimal((String)cost.get("amount")));priced++;
        }
        if(priced==0)return costs.isEmpty()?unknown("USAGE_UNKNOWN"):costs.getFirst();
        boolean complete=instrumented && !truncated && costs.size()==started && priced==started;
        var result=base(complete?"ESTIMATED":"PARTIAL");result.put("currency",currency);
        result.put("amount",complete?decimal(subtotal):null);result.put("knownSubtotal",decimal(subtotal));
        result.put("pricedCalls",priced);result.put("totalCalls",started);result.put("reason",complete?null:truncated?"RECORDS_TRUNCATED":"INCOMPLETE_CALL_COSTS");
        return Collections.unmodifiableMap(result);
    }
    private static BigDecimal amount(long tokens,BigDecimal rate){return rate.multiply(BigDecimal.valueOf(tokens)).movePointLeft(6);}
    private static String decimal(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
}
