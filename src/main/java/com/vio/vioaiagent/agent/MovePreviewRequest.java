package com.vio.vioaiagent.agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;
/** Finite explicit first-slot move replacement; unknown/mixed clauses fall back to normal planning. */
public record MovePreviewRequest(long teamId, List<String> moves) {
    private static final Pattern REQUEST = Pattern.compile("^(?:请)?(?:读取我的)?队伍\\s*([1-9][0-9]*)\\s*(?:的|，|,)?\\s*第一位招式(?:改成|只保留)\\s*([a-z][a-z-]{0,79}(?:\\s*[/、,]\\s*[a-z][a-z-]{0,79}){0,3})(.*)$");
    public static Optional<MovePreviewRequest> select(String q) {
        if(q==null||q.length()>500||q.contains("\n"))return Optional.empty();
        var match=REQUEST.matcher(q.strip());if(!match.matches())return Optional.empty();
        String tail=match.group(3).replaceAll("先读取原配置|再出预览|不能先保存|其余配置不变|其余不变|仅生成变更预览|等待确认|不讨论合法性|暂不确认|不直接保存|其他不变","")
                .replaceAll("[，,。；;并\\s]","");
        if(!tail.isEmpty()||!q.contains("预览"))return Optional.empty();
        try {var moves=List.of(match.group(2).split("\\s*[/、,]\\s*"));
            if(new HashSet<>(moves).size()!=moves.size())return Optional.empty();
            return Optional.of(new MovePreviewRequest(Long.parseLong(match.group(1)),moves));
        }catch(Exception invalid){return Optional.empty();}
    }
    public String arguments(boolean preview) {
        try{return new ObjectMapper().writeValueAsString(preview?Map.of("teamId",teamId,"changes",List.of(Map.of("slot",1,"moves",moves)),
                "rationale","按用户明确指定仅替换第一位招式，其他配置保持原值，等待页面确认。") : Map.of("teamId",teamId));}
        catch(Exception invalid){throw new IllegalStateException("预览参数编码失败",invalid);}
    }
}
