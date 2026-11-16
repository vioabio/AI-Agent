package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.domain.*;
import com.vio.vioaiagent.pokemon.gym.*;
import com.vio.vioaiagent.pokemon.repository.*;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.pokemon.service.PokemonResourceService;
import com.vio.vioaiagent.pokemon.team.TeamWorkflowService;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.security.HitlManager;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SavedGymQuestionRouteTest {
 @Test void nonKnockoutMovesCannotBecomeCandidatesInSummaryFocusedOrPerMemberAnswers(){
  var member=new TeamMember(0,445,1,new TeamBuild(List.of("false-swipe"),"rough-skin","","hardy",51,null,null));
  teams.create(new SavedTeam(0,"owner-a","capture-only","casual-v1","saved-sv-release",List.of(member)));
  for(String question:List.of("我的capture-only队伍能应对海岱吗", "我的capture-only队伍如何出招应对海岱", "请读取我的capture-only队伍，逐个说明成员如何应对海岱")){
   var requested=AgentRun.create(run.id(),"s","conversation","owner-a",question,"pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(30));
   String answer=SavedGymQuestionRoute.reply(new AgentRunContext(requested),callbacks).orElseThrow();
   assertTrue(answer.contains("至少1HP"));
   assertFalse(answer.contains("具备属性覆盖条件"));
   assertFalse(answer.contains("可考虑点到为止"));
   assertFalse(answer.contains("可优先考虑"));
   assertFalse(answer.contains("槽位1 点到为止（"));
   assertTrue(answer.contains("缺少可用于击倒的已核对攻击候选"));
   assertFalse(answer.contains("无法评估或无法命中的对手"));
  }
 }
 @Test void explicitPerMemberReviewIncludesEverySavedSlotAndItsActualMoves(){
  var second = new TeamMember(0,149,6,new TeamBuild(List.of("aerial-ace"),"multiscale","","hardy",50,null,null));
  var expanded = teams.create(new SavedTeam(0,"owner-a","manual-review","casual-v1","saved-sv-release",List.of(team.members().getFirst(),second)));
  var before = teams.snapshotForOwner(expanded.id(),"owner-a");
  var requested=AgentRun.create(run.id(),"s","conversation","owner-a","我当前账号保存的manual-review队伍能应对这位馆主吗？请读取实际队伍，逐个说明适合出场的成员和出招思路。先给分析建议。","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(30));
  String answer=GymDialogueRouter.reply(new AgentRunContext(requested,null,List.of(new UserMessage("查看海岱的初战队伍"))),callbacks).orElseThrow();
  String perMember=answer.substring(answer.indexOf("### 逐个成员的出招候选"),answer.indexOf("### 当前配置的建议"));
  assertTrue(perMember.contains("槽位3 烈咬陆鲨"));assertTrue(perMember.contains("槽位6 快龙"));
  assertTrue(perMember.contains("燕返"));assertTrue(perMember.contains("轻身鳕"));assertTrue(perMember.contains("三海地鼠"));assertTrue(perMember.contains("好胜毛蟹"));
  assertEquals(before,teams.snapshotForOwner(expanded.id(),"owner-a"));
 }
 @TempDir Path directory;
 final InMemorySavedTeamRepository teams=new InMemorySavedTeamRepository();final InMemoryRunRepository runs=new InMemoryRunRepository();final PokemonFactRepository facts=mock(PokemonFactRepository.class);final HitlManager hitl=new HitlManager();
 SavedTeamGymTool tool;ToolCallback[] callbacks;SavedTeam team;AgentRun run;
 static final String QUESTION="你看下我当前的test1队伍能否应对这个馆主，该如何配置出招呢";
 static List<Message> history(){return List.of(new UserMessage("看下阿枫的队伍配置"),new AssistantMessage("### 朱／紫馆主资料 · 初战\n**阿枫** · 虫 · 单打\n熊宝宝15级。询问重赛可查看另一套阵容。"),new UserMessage("如何应对这个队伍，在朱版本的精灵图鉴下有没有比较合适的应对队伍"),new AssistantMessage("### 阿枫队伍的应对方向\n初战等级14–15。"));}
 @BeforeEach void setup(){
  var json=new ObjectMapper();var options=new LocalBuildOptions(json,new DefaultResourceLoader(),new PokeApiClient(json,directory,url->{throw new AssertionError("Must not fetch unrelated Pokemon facts");}));var gyms=new GymChallengeService(options,json);
  tool=new SavedTeamGymTool(teams,facts,runs,gyms,options);
  when(facts.findDataRelease("saved-sv-release")).thenReturn(Optional.of(new VersionedDataRelease("saved-sv-release","pokemon-scarlet-violet","https://example.invalid/facts","sha256:fixture","REVIEWED")));when(facts.isQueryableRelease(any())).thenReturn(true);
  team=teams.create(new SavedTeam(0,"owner-a","test1","casual-v1","saved-sv-release",List.of(new TeamMember(0,445,3,new TeamBuild(List.of("aerial-ace","bulldoze"),"rough-skin","","hardy",51,null,null)))));
  callbacks=ToolCallbacks.from(new TeamWorkflowService(new PokemonResourceService(facts,teams),teams,runs,hitl),tool);
  run=AgentRun.create("run-"+UUID.randomUUID(),"session","conversation","owner-a",QUESTION+QUESTION,"pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN",null,"pokeapi-reference-v1",null,null,"v1","trace",null,Instant.now().plusSeconds(60));run.transitionTo(AgentRunStatus.RUNNING);runs.save(run);
  ToolExecutionContextHolder.set(new ToolExecutionContext(run.userId(),run.id(),Set.of("trainer"),"trace",null,event->{}));
 }
 @AfterEach void close(){ToolExecutionContextHolder.clear();hitl.shutdown();}
 @Test void plainSavedTeamWordingCannotBecomeGeneralGymAdviceOrAnExistingTeamSubstitution(){
  String question="介绍保存队伍u0-no-team-43如何应对海岱。";
  var requested=AgentRun.create(run.id(),"s","conversation","owner-a",question,"pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","trace",null,Instant.now().plusSeconds(30));
  var model=mock(org.springframework.ai.chat.model.ChatModel.class);
  var answer=new DefaultRunExecutor(model,callbacks,null,null,null,null,false).execute(new AgentRunContext(requested));
  assertTrue(answer.contains("没有唯一匹配你指定的队伍"));assertFalse(answer.contains("已读取队伍：test1"));
  assertFalse(answer.contains("通用应对方向"));assertFalse(answer.contains("烈咬陆鲨"));verifyNoInteractions(model);
 }
 @Test void exactReportedMultiturnQuestionReadsSavedTeamAndNeverReturnsGymOverview(){
  var before=teams.snapshotForOwner(team.id(),"owner-a");var response=SavedGymQuestionRoute.reply(new AgentRunContext(run,null,history()),callbacks).orElseThrow();
  assertTrue(response.contains("已读取队伍：test1"));assertTrue(response.contains("阿枫初战"));assertTrue(response.contains("烈咬陆鲨"));assertTrue(response.contains("燕返"));assertTrue(response.contains("槽位3"));assertTrue(response.contains("具备属性覆盖条件"));assertTrue(response.contains("队伍与当前会话资料版本不同"));assertFalse(response.contains("馆主 | 擅长属性"));assertEquals(before,teams.snapshotForOwner(team.id(),"owner-a"));
  verify(facts,never()).findByFormId(anyLong(),anyString());assertEquals("pokeapi-reference-v1",run.dataReleaseId());
 }
 @Test void executorRoutesFollowupBeforeCatalogAndStreamsTheSameOwnedAssessment(){
  var model=mock(org.springframework.ai.chat.model.ChatModel.class);var deltas=new ArrayList<String>();var ledger=new com.vio.vioaiagent.observability.ModelUsageLedger();
  var executor=new DefaultRunExecutor(model,callbacks,null,null,null,null,false);
  var answer=executor.execute(new AgentRunContext(run,deltas::add,history(),RunMutationBoundary.denied(),ledger));
  assertTrue(answer.contains("已读取队伍：test1"));assertTrue(answer.contains("阿枫初战"));assertEquals(answer,String.join("",deltas));verifyNoInteractions(model);
  assertEquals(0,ledger.snapshot().get("modelCallCount"));
 }
 @Test void missingOrAmbiguousNpcDoesNotFallBackToTheEightGymCatalog(){
  for(var messages:List.of(List.<Message>of(),List.<Message>of(new UserMessage("阿枫和奇树的队伍")))){
   var answer=SavedGymQuestionRoute.reply(new AgentRunContext(run,null,messages),callbacks).orElseThrow();
   assertTrue(answer.contains("没有唯一确认的馆主"));assertFalse(answer.contains("馆主 | 擅长属性"));
  }
 }
 @Test void confirmedRematchIsRetainedButProgressIsNotInvented(){
  var target=GymConversationContext.resolve(QUESTION,List.of(new UserMessage("看下阿枫重赛"),new AssistantMessage("### 朱／紫馆主资料 · 视察重赛\n**阿枫**"))).orElseThrow();
  assertEquals("rematch",target.phase());assertFalse(target.defaultStage());
  var report=tool.analyzeSavedTeamForGym(team.id(),target.gymId(),target.phase()).report();assertNull(report.badges());assertNull(report.gameEdition());
 }
 @Test void foreignOwnerTeamsAreNotListedOrReadable(){
  var foreign=teams.create(new SavedTeam(0,"owner-b","他人的私密队伍","casual-v1","saved-sv-release",team.members()));
  assertThrows(RuntimeException.class,()->tool.analyzeSavedTeamForGym(foreign.id(),"katy","initial"));
  var response=SavedGymQuestionRoute.reply(new AgentRunContext(run,null,history()),callbacks).orElseThrow();assertFalse(response.contains("他人的私密队伍"));
 }
 @Test void duplicateNamesRequireSelectionAndDoNotPickAnArbitrarySnapshot(){teams.create(new SavedTeam(0,"owner-a","test1","casual-v1","saved-sv-release",team.members()));var response=SavedGymQuestionRoute.reply(new AgentRunContext(run,null,history()),callbacks).orElseThrow();assertTrue(response.contains("多支匹配"));assertFalse(response.contains("当前配置的建议"));}
 @Test void preservesUnknownProgressAndRejectsAnotherGame(){var report=tool.analyzeSavedTeamForGym(team.id(),"katy","initial").report();assertNull(report.badges());assertNull(report.gameEdition());when(facts.findDataRelease("saved-sv-release")).thenReturn(Optional.of(new VersionedDataRelease("saved-sv-release","pokemon-sword-shield","https://example.invalid/facts","sha256:fixture","REVIEWED")));assertThrows(IllegalArgumentException.class,()->tool.analyzeSavedTeamForGym(team.id(),"katy","initial"));}
 @Test void generalTeamAnalysisWithoutGymContextIsNotHijacked(){var other=AgentRun.create("run-other","s","c","owner-a","请分析我的test1队伍","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","t",null,Instant.now().plusSeconds(60));assertTrue(SavedGymQuestionRoute.reply(new AgentRunContext(other),callbacks).isEmpty());}
 @Test void resolvesGenericFollowupAndDoesNotInventRockResistance(){var other=AgentRun.create("run-generic","s","c","owner-a","如何应对这个队伍，在朱版本的精灵图鉴下有没有比较合适的应对队伍","pokemon-scarlet-violet","casual-singles","casual-v1","zh-CN","t",null,Instant.now().plusSeconds(60));var response=GymGeneralAdviceRoute.reply(new AgentRunContext(other,null,history().subList(0,2))).orElseThrow();assertTrue(response.contains("阿枫初战"));assertTrue(response.contains("岩石属性并不抵抗虫招式（1倍）"));assertFalse(response.contains("版本不一致，查询被拒绝"));}
}
