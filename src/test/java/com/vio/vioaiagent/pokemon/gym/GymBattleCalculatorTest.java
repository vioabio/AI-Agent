package com.vio.vioaiagent.pokemon.gym;
import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.domain.TeamBuild;
import com.vio.vioaiagent.pokemon.reference.*;
import org.springframework.core.io.DefaultResourceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GymBattleCalculatorTest {
 @TempDir Path directory;
 private final ObjectMapper mapper=new ObjectMapper();
 private GymBattleCalculator calculator(){
  var client=new PokeApiClient(mapper,directory,url->{
   if(url.contains("pokemon-species"))return "{\"id\":7,\"name\":\"squirtle\",\"generation\":{\"name\":\"generation-i\"},\"names\":[]}";
   var p=mapper.createObjectNode().put("id",7).put("name","squirtle");p.putObject("species").put("name","squirtle");p.putArray("types").addObject().putObject("type").put("name","water");p.putArray("abilities").addObject().putObject("ability").put("name","torrent");
   String[] names={"hp","attack","defense","special-attack","special-defense","speed"};int[] values={44,48,65,50,64,43};var stats=p.putArray("stats");for(int i=0;i<6;i++)stats.addObject().put("base_stat",values[i]).putObject("stat").put("name",names[i]);return p.toString();
  });
  var gyms=new GymChallengeService(new LocalBuildOptions(mapper,new DefaultResourceLoader(),client),mapper);
  return new GymBattleCalculator(gyms,new LocalPokeApiRepository(mapper,client),mapper,"node",Path.of("tools/pokemon-engine/gym-battle.cjs").toAbsolutePath().toString());
 }
 private GymBattleCalculator.Request request(String move,String direction,JsonNode field){
  var challenge=new GymChallengeService.Request("scarlet","pokemon-scarlet-violet","initial","iono",2,false,"",List.of(new GymChallengeService.Member(7,new TeamBuild(List.of("surf"),"torrent","","hardy",24,null,null))));
  return new GymBattleCalculator.Request(challenge,1,4,direction,move,null,null,mapper.createObjectNode().put("teraActive",true),field,false,null);
 }
 @Test void callsRealNodeBothDirectionsAndKeepsHiddenStatsAsAssumptions(){
  org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(Path.of("tools/pokemon-engine/node_modules/@smogon/calc/package.json")));
  var r=calculator().calculate(request("surf","player",null));assertTrue(r.path("range").path("maximum").asInt()>0);assertEquals("Electric",r.path("assumptions").path("defender").path("state").path("activeTeraType").asText());assertEquals("USER_ASSUMPTIONS_NOT_VERIFIED_NPC_STATS",r.path("npcStatsStatus").asText());
  var evidence=new com.vio.vioaiagent.agent.DamageAnswerEvidence();evidence.begin("请计算道馆伤害");evidence.observe("calculateGymDamage",r.toString());assertTrue(evidence.complete());assertTrue(evidence.verifiedSummary().contains("不是已核实的隐藏数值"));assertTrue(evidence.verifiedSummary().contains("Electric"));assertFalse(evidence.verifiedSummary().contains("假设满HP；未模拟"));
  var incoming=calculator().calculate(request("Charge Beam","npc",null));assertTrue(incoming.path("range").path("maximum").asInt()>0);assertEquals("squirtle",incoming.path("assumptions").path("defender").path("species").asText().toLowerCase());
 }
 @Test void rejectsForeignMovesAndWrongBattleMode(){
  assertThrows(IllegalArgumentException.class,()->calculator().calculate(request("surf","npc",null)));
  assertThrows(IllegalArgumentException.class,()->calculator().calculate(request("surf","player",mapper.createObjectNode().put("gameType","Doubles"))));
 }
 @Test void rejectsUnknownStateFields(){
  org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(Path.of("tools/pokemon-engine/node_modules/@smogon/calc/package.json")));
  var r=request("surf","player",null);var bad=new GymBattleCalculator.Request(r.challenge(),1,4,"player","surf",null,mapper.createObjectNode().put("mysteryCondition",true),null,null,false,null);
  assertThrows(IllegalArgumentException.class,()->calculator().calculate(bad));
 }
}
