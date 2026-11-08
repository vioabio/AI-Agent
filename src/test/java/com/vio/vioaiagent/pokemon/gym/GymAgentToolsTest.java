package com.vio.vioaiagent.pokemon.gym;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.core.io.DefaultResourceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class GymAgentToolsTest {
 @TempDir Path directory;
 @Test void compactModelCallbackConvertsDocumentedRequestAndChineseIdentityAliases() throws Exception {
  var mapper=new ObjectMapper();var gyms=new GymChallengeService(new LocalBuildOptions(mapper,new DefaultResourceLoader(),new PokeApiClient(mapper,directory,url->{throw new AssertionError("offline");})),mapper);
  var tools=ToolCallbacks.from(new GymAgentTools(gyms,null,mapper));assertEquals(2,tools.length);
  var plan=java.util.Arrays.stream(tools).filter(t->t.getToolDefinition().name().equals("planGymChallenge")).findFirst().orElseThrow();assertTrue(plan.getToolDefinition().inputSchema().length()<1500);
  var result=mapper.readTree(plan.call("""
  {"request":{"gameEdition":"scarlet","gameReleaseId":"pokemon-scarlet-violet","phase":"initial","gymId":"奇树","badges":2,"allowLeveling":false,"members":[{"formId":445,"build":{"level":24,"moves":["earthquake"],"ability":"rough-skin","nature":"hardy"}}]}}
  """));assertEquals("iono",result.path("gym").path("id").asText());assertTrue(result.path("plan").toString().contains("飘浮"));
 }
}
