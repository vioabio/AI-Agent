package com.vio.vioaiagent.pokemon;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.domain.*;
import com.vio.vioaiagent.pokemon.repository.SqliteSavedTeamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class TeamTeraPersistenceTest {
 @TempDir Path directory;
 @Test void teraPersistsAcrossRepositoryReopenAndLegacyBuildsRemainReadable()throws Exception{
  var mapper=new ObjectMapper();var build=new TeamBuild(List.of("surf"),"torrent","leftovers","modest",50,null,null,"Water");
  assertEquals("water",build.teraType());assertEquals(build,mapper.readValue(mapper.writeValueAsString(build),TeamBuild.class));
  var repository=new SqliteSavedTeamRepository(mapper,directory.resolve("teams.sqlite").toString());
  var saved=repository.create(new SavedTeam(0,"owner","tera","casual-v1","pokeapi-reference-v1",List.of(new TeamMember(0,7,1,build))));
  var reopened=new SqliteSavedTeamRepository(mapper,directory.resolve("teams.sqlite").toString());
  assertEquals("water",reopened.findByIdForOwner(saved.id(),"owner").orElseThrow().members().getFirst().build().teraType());
  assertNull(mapper.readValue("{\"moves\":[\"surf\"],\"level\":50}",TeamBuild.class).teraType());
  assertThrows(IllegalArgumentException.class,()->new TeamBuild(List.of(),"","","hardy",50,null,null,"imaginary"));
 }
}
