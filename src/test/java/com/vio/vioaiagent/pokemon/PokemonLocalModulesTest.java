package com.vio.vioaiagent.pokemon;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.domain.*;
import com.vio.vioaiagent.pokemon.repository.SqliteSavedTeamRepository;
import com.vio.vioaiagent.pokemon.guides.LocalGuideSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PokemonLocalModulesTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper();
    private TeamBuild build(){return new TeamBuild(List.of("surf","ice-beam"),"torrent","leftovers","modest",50,new TeamBuild.Stats(4,0,0,252,0,252),null);}
    @Test void teamBuildRoundTripsAcrossRestartAndRemainsOwnerScoped() {
        String file=directory.resolve("teams.sqlite").toString();
        var repository=new SqliteSavedTeamRepository(mapper,file);
        var saved=repository.create(new SavedTeam(0,"alice","Squirtle","casual-v1","pokeapi-reference-v1",List.of(new TeamMember(0,7,1,build()))));
        var reopened=new SqliteSavedTeamRepository(mapper,file);
        assertEquals(build(),reopened.findByIdForOwner(saved.id(),"alice").orElseThrow().members().get(0).build());
        assertTrue(reopened.findByIdForOwner(saved.id(),"bob").isEmpty());
        assertFalse(reopened.deleteForOwner(saved.id(),"bob"));
        assertFalse(reopened.updateForOwner(saved.id(),"bob",new SavedTeam(saved.id(),"bob","Other","casual-v1","pokeapi-reference-v1",saved.members())));
        assertEquals(1,reopened.findForOwner("alice",50).size());
        assertTrue(reopened.deleteForOwner(saved.id(),"alice"));
        assertTrue(repository.findForOwner("alice",50).isEmpty());
    }
    @Test void invalidSetsAndEvsAreRejectedBeforePersistence() {
        assertThrows(IllegalArgumentException.class,()->new TeamBuild(List.of(),"","","hardy",0,null,null));
        assertThrows(IllegalArgumentException.class,()->new TeamBuild(List.of("surf","surf"),"","","hardy",50,null,null));
        assertThrows(IllegalArgumentException.class,()->new TeamBuild(List.of(),"","","not-a-nature",50,null,null));
        assertThrows(IllegalArgumentException.class,()->new TeamBuild(List.of(),"","","hardy",50,new TeamBuild.Stats(252,252,252,0,0,0),null));
        assertThrows(IllegalArgumentException.class,()->new TeamBuild(List.of(),"","","hardy",50,null,TeamBuild.Stats.filled(32)));
    }
    @Test void localGuidesReturnEvidenceNotFabricatedResultsAndFilterVersions() {
        var index=new LocalGuideSearch(mapper,new DefaultResourceLoader(),"classpath:pokemon-guides/index.json");
        var result=index.search("努力值和性格如何选择","pokemon-scarlet-violet");
        assertFalse(result.isEmpty());assertEquals("training-configuration-v1",result.get(0).document().id());
        assertTrue(result.get(0).checksum().startsWith("sha256:"));
        assertTrue(index.search("火星种菜完全无关问题","pokemon-scarlet-violet").isEmpty());
        assertThrows(IllegalArgumentException.class,()->index.search("努力值","unknown-game"));
    }
}
