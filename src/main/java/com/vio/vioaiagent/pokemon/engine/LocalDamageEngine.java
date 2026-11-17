package com.vio.vioaiagent.pokemon.engine;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.domain.*;
import com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Executes a fixed, local JSON bridge without a shell or HTTP service. */
@Service @Profile("local & !prod & !demo")
@ConditionalOnProperty(name={"vio.damage-engine.enabled","vio.pokemon.reference.enabled"},havingValue="true")
public class LocalDamageEngine {
    private final LocalPokeApiRepository reference;
    private final ObjectMapper mapper;
    private final String node;
    private final Path script;
    public LocalDamageEngine(LocalPokeApiRepository reference,ObjectMapper mapper,
            @Value("${vio.damage-engine.node:node}") String node,
            @Value("${vio.damage-engine.script:${user.dir}/tools/pokemon-engine/calculate.cjs}") String script) {
        this.reference=reference;this.mapper=mapper;this.node=node;this.script=Path.of(script).toAbsolutePath().normalize();
    }
    public JsonNode calculate(Request request) {
        if(request==null || request.generation()!=9 || !LocalPokeApiRepository.RELEASE.equals(request.dataReleaseId())
                || request.attacker()==null || request.defender()==null || request.move()==null || request.move().length()>80) {
            throw new IllegalArgumentException("请明确选择第九世代、本地参考资料版本及攻防双方和招式");
        }
        var attack=reference.detail(request.attacker().species(),request.dataReleaseId()).fact();
        var defend=reference.detail(request.defender().species(),request.dataReleaseId()).fact();
        var normalized=new Request(9,request.dataReleaseId(),new Combatant(attack.slug(),request.attacker().build()),
                new Combatant(defend.slug(),request.defender().build()),request.move(),request.field());
        if(!Files.isRegularFile(script))throw new IllegalStateException("未找到本地伤害计算脚本");
        Process process=null;
        try {
            byte[] payload=mapper.writeValueAsBytes(normalized);
            if(payload.length>32768)throw new IllegalArgumentException("计算请求过大");
            process=new ProcessBuilder(node,script.toString()).directory(script.getParent().toFile()).start();
            try(var input=process.getOutputStream()){input.write(payload);}
            if(!process.waitFor(6,TimeUnit.SECONDS))throw new IllegalStateException("本地伤害计算超时");
            byte[] output=process.getInputStream().readNBytes(131073);
            if(process.exitValue()!=0 || output.length>131072)throw new IllegalArgumentException("伤害引擎拒绝该配置，请检查英文招式、道具、特性和物种名称");
            JsonNode result=mapper.readTree(output);
            if(!"@smogon/calc".equals(result.path("engine").asText()) || !"0.12.0".equals(result.path("engineVersion").asText())
                    || result.path("generation").asInt()!=9 || !request.dataReleaseId().equals(result.path("dataReleaseId").asText()))throw new IllegalStateException("伤害引擎版本或上下文不一致");
            verifyStats(attack,result.path("attackerBaseStats")); verifyStats(defend,result.path("defenderBaseStats"));
            ((com.fasterxml.jackson.databind.node.ObjectNode)result).put("attackerSource",attack.sourceUrl()).put("defenderSource",defend.sourceUrl())
                    .put("attackerChecksum",attack.checksum()).put("defenderChecksum",defend.checksum());
            return result;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("伤害计算已取消",e);}
        catch(java.io.IOException e){throw new IllegalStateException("无法运行本地伤害引擎，请安装 Node.js 并在 tools/pokemon-engine 执行 npm ci",e);}
        finally{if(process!=null && process.isAlive())process.destroyForcibly();}
    }
    private void verifyStats(PokemonFact fact,JsonNode stats) {
        if(fact.hp()!=stats.path("hp").asInt(-1)||fact.attack()!=stats.path("atk").asInt(-1)||fact.defense()!=stats.path("def").asInt(-1)
                ||fact.spAttack()!=stats.path("spa").asInt(-1)||fact.spDefense()!=stats.path("spd").asInt(-1)||fact.speed()!=stats.path("spe").asInt(-1))
            throw new IllegalStateException("参考快照与伤害引擎的种族值不一致，拒绝混用版本");
    }
    public record Combatant(String species,TeamBuild build) {
        public Combatant {
            if(species==null||species.isBlank()||species.length()>80)throw new IllegalArgumentException("缺少有效物种名称");
            build=build==null?TeamBuild.defaults():build;
            // Read-only calculation input accepts the explicit no-held-item spelling.
            if (build.item().equalsIgnoreCase("none"))
                build=new TeamBuild(build.moves(),build.ability(),"",build.nature(),build.level(),build.evs(),build.ivs(),build.teraType());
        }
    }
    public record Field(String gameType,@org.springframework.ai.tool.annotation.ToolParam(required=false) String weather,
                        @org.springframework.ai.tool.annotation.ToolParam(required=false) String terrain) {
        public Field {
            gameType=gameType==null||gameType.isBlank()?"Singles":gameType.trim();
            if(!java.util.Set.of("Singles","Doubles").contains(gameType))throw new IllegalArgumentException("gameType 必须为 Singles 或 Doubles");
            weather=emptyCondition(weather);terrain=emptyCondition(terrain);
            if(weather!=null&&!java.util.Set.of("Sun","Rain","Sand","Hail","Snow","Harsh Sunshine","Heavy Rain","Strong Winds").contains(weather))
                throw new IllegalArgumentException("未知 weather；无天气请填 null，已知天气使用 Rain/Sun/Sand/Hail/Snow 等引擎名称");
            if(terrain!=null&&!java.util.Set.of("Electric","Grassy","Misty","Psychic").contains(terrain))
                throw new IllegalArgumentException("未知 terrain；无地形请填 null，地形仅支持 Electric/Grassy/Misty/Psychic");
        }
        private static String emptyCondition(String value) { return value==null||value.isBlank()||value.trim().equalsIgnoreCase("none")||value.trim().equals("无")?null:value.trim(); }
    }
    public record Request(int generation,String dataReleaseId,Combatant attacker,Combatant defender,String move,Field field) { }
}
