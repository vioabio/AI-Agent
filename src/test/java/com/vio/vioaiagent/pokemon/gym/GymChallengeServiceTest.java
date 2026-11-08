package com.vio.vioaiagent.pokemon.gym;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.pokemon.domain.TeamBuild;
import org.springframework.core.io.DefaultResourceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GymChallengeServiceTest {
 @TempDir Path directory;
 GymChallengeService service(){var mapper=new ObjectMapper();return new GymChallengeService(new LocalBuildOptions(mapper,new DefaultResourceLoader(),new PokeApiClient(mapper,directory,url->{throw new AssertionError("Gym planning must work offline");})),mapper);}
 GymChallengeService.Request request(String gym,String phase,List<String> moves){return new GymChallengeService.Request("scarlet","pokemon-scarlet-violet",phase,gym,2,false,"仅使用当前队伍",List.of(new GymChallengeService.Member(445,new TeamBuild(moves,"rough-skin","","hardy",23,null,null))));}
 @Test void initialCatalogHasEightGymsAndDoesNotMixRematchLevels(){var catalog=service().catalog();assertEquals(8,catalog.path("gyms").size());assertEquals(15,catalog.path("gyms").get(0).path("opponents").get(2).path("level").asInt());assertEquals("Doubles",catalog.path("gyms").get(5).path("battleMode").asText());}
 @Test void levitateRemainsImmuneAfterElectricTeraAndFlyingWattrelIsNotHitByGround(){var report=service().analyze(request("iono","initial",List.of("earthquake")));var boss=report.matchups().stream().filter(row->row.opponent().equals("Mismagius")).findFirst().orElseThrow();assertEquals(0,boss.beforeTera());assertEquals(0,boss.afterTera());assertEquals(0,report.matchups().stream().filter(row->row.opponent().equals("Wattrel")).findFirst().orElseThrow().afterTera());assertTrue(report.plan().stream().anyMatch(line->line.contains("飘浮")));}
 @Test void comparesOnlySuppliedMovesAndFlagsMissingMovesUnknownResourcesAndLevelGap(){var report=service().analyze(request("iono","initial",List.of()));assertTrue(report.matchups().isEmpty());assertTrue(report.missing().stream().anyMatch(line->line.contains("未填写当前招式")));assertTrue(report.plan().stream().anyMatch(line->line.contains("不假定可以练级")));var unknown=service().analyze(request("iono","initial",List.of("not-a-real-move")));assertTrue(unknown.missing().stream().anyMatch(line->line.contains("参数未取得")));}
 @Test void unsupportedRematchesAndUnknownGymsFailClosed(){assertThrows(IllegalArgumentException.class,()->service().analyze(request("iono","rematch",List.of("earthquake"))));assertThrows(IllegalArgumentException.class,()->service().analyze(request("unknown","initial",List.of("earthquake"))));}
 @Test void respectsTeraDefensiveChangeAndDoubleBattleWarnings(){var report=service().analyze(request("brassius","initial",List.of("flamethrower")));var boss=report.matchups().stream().filter(row->row.opponent().equals("Sudowoodo")).findFirst().orElseThrow();assertEquals(.5,boss.beforeTera());assertEquals(2,boss.afterTera());assertTrue(service().analyze(request("ryme","initial",List.of("earthquake"))).plan().stream().anyMatch(line->line.contains("双打")));}
 @Test void rematchHasIndependentRosterAndRequiresActualStoryUnlock(){
  var old=request("katy","initial",List.of("flamethrower"));var progress=new GymChallengeService.Progress(List.of(),true,true,List.of(),List.of());
  var report=service().analyze(new GymChallengeService.Request(old.gameEdition(),old.gameReleaseId(),"rematch","katy",8,false,"",old.members(),progress));
  assertEquals(5,report.gym().path("opponents").size());assertEquals("Ursaring",report.gym().path("opponents").get(4).path("species").asText());assertEquals(66,report.gym().path("opponents").get(4).path("level").asInt());
  assertEquals(4,report.gym().path("opponents").get(0).path("moves").size());
  assertThrows(IllegalArgumentException.class,()->service().analyze(new GymChallengeService.Request(old.gameEdition(),old.gameReleaseId(),"rematch","katy",8,false,"",old.members(),new GymChallengeService.Progress(null,true,false,null,null))));
 }
 @Test void resourcesDistinguishInventorySpecificGymRewardAndUnknownRoutes(){
  var progress=new GymChallengeService.Progress(List.of("katy"),false,false,List.of("tm021","tm048","unknown","tm114"),List.of("tm114"));
  var checks=GymResourcePolicy.check(progress);
  assertEquals(List.of("ROUTE_UNLOCKED","ROUTE_NOT_CONFIRMED","UNKNOWN","OWNED_DECLARED"),checks.stream().map(GymResourcePolicy.Check::status).toList());
  assertThrows(IllegalArgumentException.class,()->GymResourcePolicy.validateProgress(progress,0));
  assertThrows(IllegalArgumentException.class,()->GymResourcePolicy.validateProgress(new GymChallengeService.Progress(List.of("unknown"),false,false,null,null),8));
 }
}
