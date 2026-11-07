package com.vio.vioaiagent.pokemon.reference;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service @Profile("local & !prod & !demo") @ConditionalOnProperty(name="vio.pokemon.reference.enabled",havingValue="true")
public class LocalMachineReferences {
    private final LocalLearnsetReferences learning;
    public LocalMachineReferences(LocalLearnsetReferences learning){this.learning=learning;MachineReferenceCatalog.current();}
    public MachineReferenceFacts.Report read(long id,String move,String data,String game){
        PokemonLearnsetFacts.requireRequest(id,move,data,game);interrupted();PokemonLearnsetFacts record=null;
        try{record=learning.read(id,move,data,game);}catch(RuntimeException unavailable){interrupted();}
        return MachineReferenceFacts.create(id,move,data,game,record);
    }
    private static void interrupted(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("学习器查询已取消");}
}
