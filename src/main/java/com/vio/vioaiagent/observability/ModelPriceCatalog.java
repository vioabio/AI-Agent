package com.vio.vioaiagent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Immutable local quotes. Explicit scope and exact requested model; never a provider invoice. */
public final class ModelPriceCatalog {
    public record Price(String id,String provider,String model,String scope,String currency,
                        Instant validFrom,Instant validUntil,String sourceType,String sourceUrl,
                        String inputAccounting,long maxInputTokens,BigDecimal inputPerMillion,
                        BigDecimal cachedInputPerMillion,BigDecimal outputPerMillion) { }
    public record Document(int schemaVersion,String version,List<Price> prices) { }
    public record Selection(String version,String digest,String scope,Instant selectedAt,Price price,String reason) {
        public Map<String,Object> snapshot() {
            var result=new LinkedHashMap<String,Object>();result.put("version",version);result.put("sha256",digest);
            result.put("scope",scope);result.put("selectedAt",selectedAt.toString());result.put("matchedCardId",price==null?null:price.id());
            return Collections.unmodifiableMap(result);
        }
    }
    private final Document document;
    private final String digest,scope,provider;
    private ModelPriceCatalog(Document document,String digest,String scope,String provider) {
        this.document=document;this.digest=digest;this.scope=scope;this.provider=provider;
    }
    public static ModelPriceCatalog disabled() { return new ModelPriceCatalog(null,null,null,null); }
    public static ModelPriceCatalog load(String path,String scope,String provider) {
        try {
            if (!scope.matches("[A-Za-z0-9_.:-]{1,100}") || !Set.of("deepseek","dashscope").contains(provider))throw new IllegalArgumentException();
            Path file=Path.of(path);
            if(!Files.isRegularFile(file) || Files.size(file)>262144)throw new IllegalArgumentException();
            byte[] bytes=Files.readAllBytes(file);
            if(bytes.length>262144)throw new IllegalArgumentException();
            var doc=new ObjectMapper().findAndRegisterModules().readValue(bytes,Document.class);
            validate(doc);
            return new ModelPriceCatalog(new Document(doc.schemaVersion(),doc.version(),List.copyOf(doc.prices())),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),scope,provider);
        } catch(Exception invalid) { throw new IllegalArgumentException("本地计费价格目录无效，请检查版本、适用范围、单价与有效期"); }
    }
    private static void validate(Document doc) {
        if(doc.schemaVersion()!=1 || doc.version()==null || !doc.version().matches("[A-Za-z0-9_.:-]{1,100}")
                || doc.prices()==null || doc.prices().isEmpty() || doc.prices().size()>64)throw new IllegalArgumentException();
        Set<String> ids=new HashSet<>();
        for(var price:doc.prices()) {
            if(price.id()==null || !price.id().matches("[A-Za-z0-9_.:-]{1,100}") || !ids.add(price.id())
                    || !Set.of("deepseek","dashscope").contains(price.provider())
                    || price.model()==null || !price.model().matches("[A-Za-z0-9_.:/-]{1,100}")
                    || price.scope()==null || !price.scope().matches("[A-Za-z0-9_.:-]{1,100}")
                    || !Set.of("USD","CNY").contains(price.currency())
                    || price.validFrom()==null || price.validUntil()==null || !price.validFrom().isBefore(price.validUntil())
                    || !Set.of("OFFICIAL_LIST","LOCAL_CONTRACT","TEST_FIXTURE").contains(price.sourceType())
                    || !Set.of("ALL_INPUT","SPLIT_CACHE").contains(price.inputAccounting())
                    || price.maxInputTokens()<1 || price.maxInputTokens()>1_000_000)throw new IllegalArgumentException();
            if(price.sourceUrl()==null || price.sourceUrl().length()>2048)throw new IllegalArgumentException();
            var url=URI.create(price.sourceUrl());
            if(!"https".equals(url.getScheme()) || url.getHost()==null || url.getRawUserInfo()!=null
                    || url.getRawQuery()!=null || url.getRawFragment()!=null)throw new IllegalArgumentException();
            rate(price.inputPerMillion());rate(price.outputPerMillion());
            if("SPLIT_CACHE".equals(price.inputAccounting()))rate(price.cachedInputPerMillion());
            else if(price.cachedInputPerMillion()!=null)throw new IllegalArgumentException();
        }
        for(int i=0;i<doc.prices().size();i++)for(int j=i+1;j<doc.prices().size();j++) {
            var a=doc.prices().get(i);var b=doc.prices().get(j);
            if(a.provider().equals(b.provider()) && a.model().equals(b.model()) && a.scope().equals(b.scope())
                    && a.validFrom().isBefore(b.validUntil()) && b.validFrom().isBefore(a.validUntil()))throw new IllegalArgumentException();
        }
    }
    private static void rate(BigDecimal rate) {
        if(rate==null || rate.signum()<0 || rate.compareTo(new BigDecimal("1000000"))>0 || rate.scale()>8)throw new IllegalArgumentException();
    }
    public Selection select(String requestedModel,Instant at) {
        if(document==null)return new Selection(null,null,null,at,null,"PRICING_DISABLED");
        for(var price:document.prices())if(price.provider().equals(provider) && price.model().equals(requestedModel)
                && price.scope().equals(scope) && !at.isBefore(price.validFrom()) && at.isBefore(price.validUntil())) {
            return new Selection(document.version(),digest,scope,at,price,null);
        }
        return new Selection(document.version(),digest,scope,at,null,"NO_MATCHING_PRICE");
    }
    public Map<String,Object> policy() {
        var result=new LinkedHashMap<String,Object>();result.put("enabled",document!=null);
        result.put("version",document==null?null:document.version());result.put("sha256",digest);result.put("scope",scope);
        result.put("kind","REFERENCE_ESTIMATE_NOT_INVOICE");return Collections.unmodifiableMap(result);
    }
}
