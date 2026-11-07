package com.vio.vioaiagent.pokemon.engine;

import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.tool.annotation.*;

@RestController @Profile("local & !prod & !demo")
@ConditionalOnProperty(name={"vio.damage-engine.enabled","vio.pokemon.reference.enabled"},havingValue="true")
public class DamageEngineController {
    private final LocalDamageEngine engine;
    public DamageEngineController(LocalDamageEngine engine){this.engine=engine;}
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<com.vio.vioaiagent.common.Result<Void>> invalidInput(IllegalArgumentException error) {
        return org.springframework.http.ResponseEntity.badRequest().body(com.vio.vioaiagent.common.Result.error(400,
                error.getMessage() == null ? "伤害计算参数无效" : error.getMessage()));
    }
    @PostMapping("/pokemon/calculations/engine-damage")
    public com.fasterxml.jackson.databind.JsonNode calculate(@RequestBody LocalDamageEngine.Request request){return engine.calculate(request);}
    public static class Tools {
        private final LocalDamageEngine engine;
        private final com.vio.vioaiagent.pokemon.reference.RunToolScope scope;
        public Tools(LocalDamageEngine engine,com.vio.vioaiagent.pokemon.reference.RunToolScope scope){this.engine=engine;this.scope=scope;}
        @Tool(description="调用本地 @smogon/calc 0.12.0 计算第九世代伤害范围。必须提供攻防物种、配招/特性/道具/性格/等级/EV/IV等已知条件；缺失条件采用明确返回的默认值。仅支持 pokeapi-reference-v1，结果不是合法性或胜率。场地支持 Singles/Doubles、weather、terrain；不支持未声明的太晶、强化和状态效果。")
        public com.fasterxml.jackson.databind.JsonNode calculateBattleDamage(@ToolParam(description="包含 generation=9、当前dataReleaseId、attacker和defender（species与build）、move、field 的计算请求；field.gameType 为 Singles/Doubles，无天气/地形填 null") LocalDamageEngine.Request request){if(scope!=null&&request!=null)scope.check(request.dataReleaseId(),null);return engine.calculate(request);}
    }
}
