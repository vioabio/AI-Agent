package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.reference.TeamBuildCheckFacts;
import java.util.*;

/** Explicit supplied draft only; no extraction from quotes, mixed commands or saved-team requests. */
public final class TeamBuildRequestRoute {
    public static final String VERSION="explicit-team-build-route-v1";
    public static final String ORIGIN="PROGRAM_SUPPLIED_TEAM_REQUEST";
    public static final String UI_HEADER="请检查整队配置，保留原成员和未填写字段；只核对，不保存、不生成修改预览。逐项匹配不代表联合可得或赛制合法。";
    private static final Set<String> HEADERS=Set.of(UI_HEADER,"请检查整队配置。\n保留原位置和未填写字段，只核对，不保存、不生成修改预览；逐项匹配不证明联合可得或赛制合法。");
    private static final ObjectMapper JSON=new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private TeamBuildRequestRoute() { }
    public static Optional<TeamBuildCheckFacts.Request> select(String question){
        if(question==null||question.length()>16000)return Optional.empty();int newline=question.lastIndexOf('\n');
        if(newline<0||!HEADERS.contains(question.substring(0,newline).strip()))return Optional.empty();
        try{return Optional.of(TeamBuildCheckFacts.parseRequest(JSON.readTree(question.substring(newline+1).strip())));}catch(Exception invalid){return Optional.empty();}
    }
    public static String arguments(TeamBuildCheckFacts.Request request){try{return JSON.writeValueAsString(Map.of("request",request));}catch(Exception invalid){throw new IllegalArgumentException("原整队配置编码失败",invalid);}}
}
