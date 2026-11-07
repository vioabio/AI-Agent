package com.vio.vioaiagent.pokemon.reference;

import org.springframework.ai.tool.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/pokemon/reference/build-check") @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class PokemonBuildCheckController {
    private final LocalBuildCheckService service;
    public PokemonBuildCheckController(LocalBuildCheckService service) { this.service=service; }
    @PostMapping public BuildCheckFacts.Report check(@RequestBody com.fasterxml.jackson.databind.JsonNode request) { return service.check(BuildCheckFacts.parseRequest(request)); }
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<com.vio.vioaiagent.common.Result<Void>> invalid() { return org.springframework.http.ResponseEntity.badRequest().body(com.vio.vioaiagent.common.Result.error(400,"配招核对参数或朱紫参考范围无效")); }
    public static class Tools {
        private final LocalBuildCheckService service; private final RunToolScope scope;
        public Tools(LocalBuildCheckService service,RunToolScope scope) { this.service=service;this.scope=scope; }
        @Tool(description="逐项检查用户明确提供的一个成员培养配置：四招完整性、特性候选、各招式朱紫学习记录、已填EV/IV等结构约束。必须保留原输入，未提供字段为null，不能为通过检查代填配置。REFERENCE_MATCHED仅表示逐项参考匹配，不证明四招/遗传/特性可共同取得或赛制合法。仅只读，不保存、修改队伍或批准预览。")
        public BuildCheckFacts.Report checkPokemonBuild(@ToolParam(description="成员formId、原培养字段和当前Run数据/游戏版本") BuildCheckFacts.Request request) {
            BuildCheckFacts.requireRequest(request);if(scope!=null)scope.check(request.dataReleaseId(),request.gameReleaseId());return service.check(request);
        }
    }
}
