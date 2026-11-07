package com.vio.vioaiagent.pokemon.engine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;

/** Default HTTP/Agent adapter. The original bridge remains the version-bound source primitive. */
@Service @Primary @Profile("local & !prod & !demo")
@ConditionalOnProperty(name={"vio.damage-engine.enabled","vio.pokemon.reference.enabled"},havingValue="true")
public class VerifiedLocalDamageEngine extends LocalDamageEngine {
    private final LocalPokeApiRepository reference;
    private final Path script;
    public VerifiedLocalDamageEngine(LocalPokeApiRepository reference,ObjectMapper mapper,
            @Value("${vio.damage-engine.node:node}") String node,
            @Value("${vio.damage-engine.script:${user.dir}/tools/pokemon-engine/calculate.cjs}") String script) {
        super(reference,mapper,node,script);this.reference=reference;this.script=Path.of(script).toAbsolutePath().normalize();
    }
    @Override public JsonNode calculate(Request request) {
        var result=super.calculate(request);
        var attack=reference.detail(request.attacker().species(),request.dataReleaseId());var defend=reference.detail(request.defender().species(),request.dataReleaseId());
        verifyStats(attack.fact(),result.path("attackerBaseStats"));verifyStats(defend.fact(),result.path("defenderBaseStats"));
        var normalized=new Request(9,request.dataReleaseId(),new Combatant(attack.fact().slug(),request.attacker().build()),new Combatant(defend.fact().slug(),request.defender().build()),request.move(),request.field());
        ((ObjectNode)result).set("conditionValidation",DamageResultContract.verify(request,normalized,result,attack.abilities(),defend.abilities()));return result;
    }
    private static void verifyStats(PokemonFact fact,JsonNode stats) {
        var keys=List.of("hp","atk","def","spa","spd","spe");int[] expected={fact.hp(),fact.attack(),fact.defense(),fact.spAttack(),fact.spDefense(),fact.speed()};
        for(int i=0;i<keys.size();i++){var value=stats.path(keys.get(i));if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()!=expected[i])throw new IllegalStateException("参考快照与伤害引擎的种族值不一致，拒绝混用版本");}
    }
    public String bridgeDigest() {
        try {if(Files.size(script)>131072)return "unavailable";return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(script)));}
        catch(Exception unavailable){return "unavailable";}
    }
}
