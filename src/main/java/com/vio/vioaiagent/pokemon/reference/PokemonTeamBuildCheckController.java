package com.vio.vioaiagent.pokemon.reference;

import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;

@RestController @RequestMapping("/pokemon/reference/team-build-check") @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class PokemonTeamBuildCheckController {
    private final LocalBuildCheckService service;
    public PokemonTeamBuildCheckController(LocalBuildCheckService service){this.service=service;}
    @PostMapping public TeamBuildCheckFacts.Report check(@RequestBody com.fasterxml.jackson.databind.JsonNode raw){
        return checkMembers(service,TeamBuildCheckFacts.parseRequest(raw));
    }
    public static TeamBuildCheckFacts.Report checkMembers(LocalBuildCheckService service,TeamBuildCheckFacts.Request request){
        TeamBuildCheckFacts.requireRequest(request);var members=new ArrayList<TeamBuildCheckFacts.CheckedMember>();
        for(var member:request.members()){
            if(Thread.currentThread().isInterrupted())throw new CancellationException("整队核对已取消");
            members.add(new TeamBuildCheckFacts.CheckedMember(member.slot(),service.check(TeamBuildCheckFacts.memberRequest(request,member))));
        }
        return TeamBuildCheckFacts.seal(request,members);
    }
    public static class Tools {
        private final LocalBuildCheckService service;private final RunToolScope scope;
        public Tools(LocalBuildCheckService service,RunToolScope scope){this.service=service;this.scope=scope;}
        @org.springframework.ai.tool.annotation.Tool(description="只读核对用户明确给出的整队配置，最多6位成员，按原位置汇总逐项结果、缺项和重复形态。已保存队伍须先用readCurrentTeam读取实际成员及配置；不能猜测或代填配置，未提供字段保持null。所有逐项匹配也不证明四招与特性共同可得、整队协同或赛制合法。不保存、修改队伍或批准预览。")
        public TeamBuildCheckFacts.Report checkPokemonTeamBuild(@org.springframework.ai.tool.annotation.ToolParam(description="原成员位置、formId对应的pokemonId、原build及当前Run数据和游戏版本") TeamBuildCheckFacts.Request request){
            TeamBuildCheckFacts.requireRequest(request);if(scope!=null)scope.check(request.dataReleaseId(),request.gameReleaseId());return checkMembers(service,request);
        }
    }
    @ExceptionHandler(IllegalArgumentException.class) public org.springframework.http.ResponseEntity<com.vio.vioaiagent.common.Result<Void>> invalid(){return org.springframework.http.ResponseEntity.badRequest().body(com.vio.vioaiagent.common.Result.error(400,"整队核对需要1至6位有效成员、唯一位置和当前朱紫参考版本"));}
}
