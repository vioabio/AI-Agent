package com.vio.vioaiagent.pokemon;

import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.pokemon.reference.RunToolScope;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunToolScopeTest {
    @Test void modelCannotOverrideRunVersionsOrOwner() {
        var runs=new InMemoryRunRepository();
        runs.save(AgentRun.create("run-reference","session","conversation","alice","test","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN",null,"pokeapi-reference-v1",null,null,"v1","trace",null,null));
        var scope=new RunToolScope(runs);
        ToolExecutionContextHolder.set(new ToolExecutionContext("alice","run-reference",java.util.Set.of("trainer"),"trace",null,null));
        try {
            assertDoesNotThrow(()->scope.check("pokeapi-reference-v1","pokemon-scarlet-violet"));
            assertThrows(IllegalArgumentException.class,()->scope.check("different-version",null));
            assertThrows(IllegalArgumentException.class,()->scope.check(null,"different-game"));
            ToolExecutionContextHolder.set(new ToolExecutionContext("bob","run-reference",java.util.Set.of("trainer"),"trace",null,null));
            assertThrows(IllegalStateException.class,()->scope.check("pokeapi-reference-v1",null));
        }finally{ToolExecutionContextHolder.clear();}
    }
}
