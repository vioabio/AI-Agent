package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TeamSuggestionAnswerBlockTest {
    public static TeamReferenceTools.TeamEvidence evidence() {
        var repository=mock(LocalPokeApiRepository.class);
        for(int id:List.of(7,25)){
            var fact=new PokemonFact(id,id,id,id==7?"squirtle":"pikachu",id==7?"杰尼龟":"皮卡丘","test","test","pokeapi-reference-v1","pokemon-scarlet-violet","https://pokeapi.co/api/v2/pokemon/"+id+"/","sha256:"+"a".repeat(64),"LOCAL_REFERENCE",id==7?"水":"电",null,44,48,65,50,64,43);
            when(repository.detail(String.valueOf(id),"pokeapi-reference-v1")).thenReturn(new LocalPokeApiRepository.Detail(fact,List.of(),List.of(),"2026-11-14T00:00:00Z","fixture"));
        }
        return new TeamReferenceTools(repository,null).lookupPokemonTeamReference(List.of("7","25"),"pokeapi-reference-v1");
    }
    public static TeamSuggestionAnswerBlock block(){return TeamSuggestionAnswerBlock.from(List.of("7","25"),"pokeapi-reference-v1",evidence());}
    @Test void completeToolBundleProducesImmutableReferenceOnlyCard(){
        var block=block();assertEquals(2,block.members().size());assertEquals(7,block.members().getFirst().formId());
        assertEquals(TeamSuggestionAnswerBlock.SCOPE,block.scope());assertTrue(block.missing().getLast().contains("未保存"));
        assertEquals(block.blockId(),block().blockId());
        assertThrows(UnsupportedOperationException.class,()->block.members().clear());
        assertThrows(UnsupportedOperationException.class,()->block.missing().clear());
    }
    @Test void partialWrongOrderDuplicateAndVersionMismatchAreRejected(){
        var output=evidence();
        for(var queries:List.of(List.of("7"),List.of("25","7"),List.of("7","7")))
            assertThrows(IllegalArgumentException.class,()->TeamSuggestionAnswerBlock.from(queries,"pokeapi-reference-v1",output));
        assertThrows(IllegalArgumentException.class,()->TeamSuggestionAnswerBlock.from(List.of("7","25"),"wrong",output));
        var duplicate=new TeamReferenceTools.TeamEvidence(output.dataReleaseId(),output.calculationVersion(),List.of(output.members().getFirst(),output.members().getFirst()),output.limitations());
        assertThrows(IllegalArgumentException.class,()->TeamSuggestionAnswerBlock.from(List.of("7","25"),"pokeapi-reference-v1",duplicate));
        var card=block();
        assertThrows(IllegalArgumentException.class,()->new TeamSuggestionAnswerBlock(card.schemaVersion(),"team-"+"0".repeat(64),card.kind(),card.gameReleaseId(),card.dataReleaseId(),card.scope(),card.members(),card.missing()));
        assertThrows(IllegalArgumentException.class,()->new TeamSuggestionAnswerBlock(card.schemaVersion(),card.blockId(),card.kind(),card.gameReleaseId(),card.dataReleaseId(),card.scope(),card.members(),List.of("已验证合法并保存")));
    }
}
