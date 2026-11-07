package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.reference.BuildCheckFacts;
import java.util.*;

/** A narrow explicit single-member JSON request, not extraction from arbitrary source prose. */
public final class BuildCheckRequestRoute {
    public static final String VERSION = "explicit-build-request-route-v1";
    public static final String ORIGIN = "PROGRAM_SUPPLIED_BUILD_REQUEST";
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private BuildCheckRequestRoute() { }
    public static Optional<BuildCheckFacts.Request> select(String question) {
        if (question == null || question.length() > 8000) return Optional.empty();
        int newline = question.indexOf('\n'); if (newline < 0) return Optional.empty();
        String head = question.substring(0,newline).strip();
        if (MachineAnswerEvidence.requested(head) || head.length() > 250 || !head.matches("^请(?:检查|核对)下列.*配招.*$")
                || java.util.regex.Pattern.compile("(?:并|同时|然后|请|帮我)(?:保存|修改|删除|计算|推荐|搜索)").matcher(head).find()) return Optional.empty();
        try { return Optional.of(BuildCheckFacts.parseRequest(JSON.readTree(question.substring(newline+1).strip()))); }
        catch (Exception invalid) { return Optional.empty(); }
    }
    public static String arguments(BuildCheckFacts.Request request) {
        try { return JSON.writeValueAsString(Map.of("request",request)); }
        catch (Exception invalid) { throw new IllegalArgumentException("原配置编码失败",invalid); }
    }
}
