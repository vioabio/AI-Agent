package com.vio.vioaiagent.pokemon.guides;

import java.net.URI;
import java.time.LocalDate;
import java.util.*;

/** Attribution for original notes, not permission to copy third-party articles. */
public record GuideProvenance(String kind,String contentUsage,String acquiredAt,String reviewStatus,
                              List<Reference> references,List<CodeReference> codeReferences) {
    public GuideProvenance {
        references=references==null?List.of():List.copyOf(references);
        codeReferences=codeReferences==null?List.of():List.copyOf(codeReferences);
    }
    public void validate(String updatedAt) {
        if(!"PROJECT_NOTE".equals(kind) || !"ORIGINAL_NO_UPSTREAM_COPY".equals(contentUsage)
                || !"PENDING_HUMAN_REVIEW".equals(reviewStatus) || acquiredAt==null
                || references.size()>4 || codeReferences.isEmpty() || codeReferences.size()>8)
            throw new IllegalArgumentException("攻略来源类型、使用方式、审核状态或代码依据无效");
        LocalDate acquired=LocalDate.parse(acquiredAt);
        if(acquired.isAfter(LocalDate.now()) || acquired.isBefore(LocalDate.parse(updatedAt)))
            throw new IllegalArgumentException("攻略核对日期与更新日期不一致");
        Set<String> urls=new HashSet<>(),paths=new HashSet<>();
        for(var ref:references) {
            if(ref==null || blank(ref.title(),200) || blank(ref.publisher(),100) || ref.relation()==null
                    || !Set.of("API_FIELD_REFERENCE","ENGINE_INTERFACE_REFERENCE").contains(ref.relation())
                    || ref.checkedAt()==null || ref.url()==null || !urls.add(ref.url()))
                throw new IllegalArgumentException("攻略参考来源元数据无效");
            safeUrl(ref.url());
            if(LocalDate.parse(ref.checkedAt()).isAfter(acquired))throw new IllegalArgumentException("参考核对日期晚于获取日期");
        }
        for(var code:codeReferences) {
            if(code==null || code.path()==null || !code.path().matches("(?:src/main/java/|tools/pokemon-engine/)[A-Za-z0-9_./-]+")
                    || code.path().contains("..") || !paths.add(code.path()) || code.sha256()==null || !code.sha256().matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("攻略代码依据路径或摘要无效");
        }
    }
    public static void safeUrl(String value) {
        if(value==null || value.length()>1000)throw new IllegalArgumentException("攻略参考链接无效");
        URI uri=URI.create(value);
        if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)
            throw new IllegalArgumentException("攻略参考链接无效");
    }
    private static boolean blank(String text,int max){return text==null || text.isBlank() || text.length()>max;}
    public record Reference(String title,String publisher,String url,String checkedAt,String relation) { }
    public record CodeReference(String path,String sha256) { }
}
