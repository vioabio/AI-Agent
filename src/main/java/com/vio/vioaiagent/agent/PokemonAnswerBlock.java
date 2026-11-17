package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import java.util.*;

/** U2 first typed block: facts only. No model-authored numbers or mutation actions. */
public record PokemonAnswerBlock(String schemaVersion,String blockId,String kind,
        long formId,String name,List<String> types,List<Integer> stats,String gameReleaseId,
        String dataReleaseId,String evidenceDigest) {
    public PokemonAnswerBlock {
        if(!"answer-block-v1".equals(schemaVersion)||!"pokemon".equals(kind)||formId<1
            ||blockId==null||!blockId.matches("pokemon-[0-9]+")||!blockId.equals("pokemon-"+formId)
            ||name==null||name.isBlank()||name.length()>100||types==null||types.isEmpty()||types.size()>2
            ||types.stream().anyMatch(t->t==null||t.isBlank()||t.length()>10)||stats==null||stats.size()!=6
            ||stats.stream().anyMatch(n->n==null||n<1||n>255)||gameReleaseId==null||gameReleaseId.isBlank()
            ||dataReleaseId==null||dataReleaseId.isBlank()||evidenceDigest==null||!evidenceDigest.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid verified Pokemon block");
        types=List.copyOf(types);stats=List.copyOf(stats);
    }
    public static PokemonAnswerBlock from(PokemonFact fact) {
        Objects.requireNonNull(fact);
        return new PokemonAnswerBlock("answer-block-v1","pokemon-"+fact.formId(),"pokemon",fact.formId(),fact.speciesNameZh(),
            fact.type2()==null||fact.type2().isBlank()?List.of(fact.type1()):List.of(fact.type1(),fact.type2()),
            List.of(fact.hp(),fact.attack(),fact.defense(),fact.spAttack(),fact.spDefense(),fact.speed()),fact.gameReleaseId(),fact.dataReleaseId(),fact.checksum());
    }
}
