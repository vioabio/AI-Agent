package com.vio.vioaiagent.pokemon.gym;

import com.vio.vioaiagent.pokemon.domain.*;
import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GymTeamCoreToolTest {
    final LocalPokeApiRepository facts=mock(LocalPokeApiRepository.class);
    final LocalLearnsetReferences learn=mock(LocalLearnsetReferences.class);
    final LocalBuildOptions options=mock(LocalBuildOptions.class);
    final RunToolScope scope=mock(RunToolScope.class);
    final GymTeamCoreTool tool=new GymTeamCoreTool(facts,learn,options,scope);
    static final String RELEASE=LocalPokeApiRepository.RELEASE;
    @Test void unsupportedGymNeverFetchesOrPretendsComplete(){var result=tool.recommendGymTeamCore("katy","initial",RELEASE);assertTrue(result.candidates().isEmpty());assertEquals("PARTIAL",result.status());verifyNoInteractions(facts);}
    @Test void rejectsVersionBeforeReadingAnyCandidate(){assertThrows(IllegalArgumentException.class,()->tool.recommendGymTeamCore("ryme","initial","unrelated"));verifyNoInteractions(facts);}
    @Test void upstreamFailuresRetainExplicitPartialResult(){when(facts.detail(anyString(),anyString())).thenThrow(new IllegalStateException("offline"));var result=tool.recommendGymTeamCore("ryme","initial",RELEASE);assertTrue(result.candidates().isEmpty());assertEquals(5,result.missing().size());}
    @Test void onlyObservedMovesAndEligibleSvLearningBecomeAnExplicitTheoreticalBuild(){
        var fact=mock(PokemonFact.class);when(fact.formId()).thenReturn(197L);when(fact.gameReleaseId()).thenReturn("pokemon-scarlet-violet");when(fact.dataReleaseId()).thenReturn(RELEASE);when(fact.speciesNameZh()).thenReturn("月亮伊布");when(fact.sourceUrl()).thenReturn("https://pokeapi.co/api/v2/pokemon/197/");
        when(fact.slug()).thenReturn("umbreon");
        when(facts.detail(eq("197"),eq(RELEASE))).thenReturn(new LocalPokeApiRepository.Detail(fact,List.of("synchronize"),List.of("crunch","foul-play","protect","helping-hand"),"now","reference"));
        for(String move:List.of("crunch","foul-play","protect","helping-hand")){
            boolean status=Set.of("protect","helping-hand").contains(move);
            when(options.lookupMove(move)).thenReturn(new LocalBuildOptions.Choice("move",move,move,"dark",status?"status":"physical",status?0:80,100,"",false,List.of()));
            when(learn.read(197,move,RELEASE,"pokemon-scarlet-violet")).thenReturn(new PokemonLearnsetFacts("v",197,"umbreon",move,RELEASE,"pokemon-scarlet-violet","scarlet-violet","RECORDED",List.of(new PokemonLearnsetFacts.Entry("machine",0,null)),"https://pokeapi.co/api/v2/pokemon/197/","checksum","now","projection"));
        }
        var result=tool.recommendGymTeamCore("ryme","initial",RELEASE);assertEquals(1,result.candidates().size());var build=result.candidates().getFirst().configuration();assertEquals(4,build.moves().size());assertEquals(50,build.level());assertEquals("hardy",build.nature());assertEquals("",build.item());assertEquals(0,build.evs().total());assertEquals(186,build.ivs().total());assertEquals("PARTIAL",result.status());assertFalse(result.sources().isEmpty());
        when(learn.read(197,"crunch",RELEASE,"pokemon-scarlet-violet")).thenReturn(new PokemonLearnsetFacts("v",197,"umbreon","crunch",RELEASE,"pokemon-scarlet-violet","scarlet-violet","RECORDED",List.of(new PokemonLearnsetFacts.Entry("egg",0,null)),"source","checksum","now","projection"));
        assertTrue(tool.recommendGymTeamCore("ryme","initial",RELEASE).candidates().isEmpty());
    }
}
