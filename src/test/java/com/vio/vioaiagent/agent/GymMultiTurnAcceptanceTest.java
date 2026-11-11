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
import com.vio.vioaiagent.observability.ModelUsageLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** User-authored acceptance chain, distinct from individual keyword/target unit tests. */
class GymMultiTurnAcceptanceTest {
    @TempDir Path directory;
    final InMemorySavedTeamRepository teams = new InMemorySavedTeamRepository();
    final InMemoryRunRepository runs = new InMemoryRunRepository();
    final PokemonFactRepository facts = mock(PokemonFactRepository.class);
    final ChatModel model = mock(ChatModel.class);
    final HitlManager hitl = new HitlManager();
    final List<Message> transcript = new ArrayList<>();
    final List<Message> selections = new ArrayList<>();
    DefaultRunExecutor executor;
    SavedTeam team;

    @BeforeEach void setup() {
        var json = new ObjectMapper();
        var options = new LocalBuildOptions(json, new DefaultResourceLoader(),
                new PokeApiClient(json, directory, url -> { throw new AssertionError("No remote facts needed"); }));
        var tool = new SavedTeamGymTool(teams, facts, runs, new GymChallengeService(options, json), options);
        when(facts.findDataRelease("saved-sv-release")).thenReturn(Optional.of(new VersionedDataRelease(
                "saved-sv-release", "pokemon-scarlet-violet", "https://example.invalid/facts", "sha256:fixture", "REVIEWED")));
        when(facts.isQueryableRelease(any())).thenReturn(true);
        team = teams.create(new SavedTeam(0, "owner-a", "test1", "casual-v1", "saved-sv-release",
                List.of(new TeamMember(0, 445, 3, new TeamBuild(List.of("aerial-ace", "bulldoze"),
                        "rough-skin", "", "hardy", 51, null, null)))));
        var workflow = new TeamWorkflowService(new PokemonResourceService(facts, teams), teams, runs, hitl);
        executor = new DefaultRunExecutor(model, ToolCallbacks.from(workflow, tool), null, null, null, null, false);
    }

    @AfterEach void close() { ToolExecutionContextHolder.clear(); hitl.shutdown(); }

    private String ask(String question, boolean clippedHistory) {
        var run = AgentRun.create("run-" + UUID.randomUUID(), "s", "conversation-a", "owner-a", question,
                "pokemon-scarlet-violet", "casual-singles", "casual-v1", "zh-CN", null,
                "pokeapi-reference-v1", null, null, "v1", "trace", null, Instant.now().plusSeconds(30));
        run.transitionTo(AgentRunStatus.RUNNING); runs.save(run);
        ToolExecutionContextHolder.set(new ToolExecutionContext(run.userId(), run.id(), Set.of("trainer"), "trace", null, e -> {}));
        var deltas = new ArrayList<String>();
        List<Message> modelHistory = clippedHistory ? List.of(new UserMessage("介绍一下宝可梦"), new AssistantMessage("你好")) : List.copyOf(transcript);
        var context = new AgentRunContext(run, deltas::add, modelHistory, RunMutationBoundary.denied(),
                new ModelUsageLedger(), ConversationConstraints.defaults(), List.copyOf(selections));
        String answer = executor.execute(context);
        assertEquals(answer, String.join("", deltas), "The reviewed result must match the published response");
        transcript.add(new UserMessage(question)); transcript.add(new AssistantMessage(answer));
        selections.add(new UserMessage(question));
        selections.add(new AssistantMessage(answer));
        return answer;
    }

    @Test void exactFourTurnChainSelectsFourthReadsTest1AndSwitchesToSixth() {
        var before = teams.snapshotForOwner(team.id(), "owner-a");
        String count = ask("咨询下朱紫有几位馆主", false);
        assertTrue(count.contains("8") || count.contains("八"));
        assertTrue(count.contains("海岱")); assertTrue(count.contains("莱姆"));
        assertFalse(count.contains("先确认你的冒险进度"));

        String fourth = ask("看下第四位馆主的队伍配置", false);
        assertTrue(fourth.contains("海岱")); assertTrue(fourth.contains("轻身鳕"));
        assertFalse(fourth.contains("| 馆主 |")); assertFalse(fourth.contains("豆蟋蟀"));

        String own = ask("我的test1队伍的配置可以应对这个馆主吗", false);
        assertTrue(own.contains("已读取队伍：test1")); assertTrue(own.contains("海岱初战"));
        assertTrue(own.contains("烈咬陆鲨")); assertTrue(own.contains("燕返"));
        assertFalse(own.contains("没有唯一确认的馆主"));

        String sixth = ask("基于朱紫的图鉴给我一个应对第六位馆主队伍的队伍配置", false);
        assertTrue(sixth.contains("莱姆")); assertFalse(sixth.contains("海岱初战"));
        assertFalse(sixth.contains("| 馆主 |")); assertTrue(sixth.contains("双打"));
        assertTrue(sixth.contains("缺口") || sixth.contains("尚不是完整"), "Incomplete acquisition evidence must remain visible");
        assertEquals(before, teams.snapshotForOwner(team.id(), "owner-a"));
        verifyNoInteractions(model);
    }

    @Test void targetSurvivesMoreThanEightInterveningTurnsOutsideModelHistory() {
        ask("咨询下朱紫有几位馆主", false);
        ask("看下第六位馆主的队伍配置", false);
        // These are persisted user turns intentionally omitted from the model's tiny prompt window.
        for (int i = 0; i < 10; i++) {
            selections.add(new UserMessage("介绍一下皮卡丘"));
            selections.add(new AssistantMessage("皮卡丘是电属性宝可梦。"));
        }
        String answer = ask("我的test1队伍的配置可以应对这个馆主吗", true);
        assertTrue(answer.contains("莱姆初战")); assertTrue(answer.contains("已读取队伍：test1"));
        assertFalse(answer.contains("没有唯一确认的馆主"));
        verifyNoInteractions(model);
    }

    @Test void unknownOrdinalCannotInventAnOrderOrReuseAnOldTarget() {
        String answer = ask("看下第四位馆主的队伍配置", false);
        assertFalse(answer.contains("轻身鳕")); assertFalse(answer.contains("| 馆主 |"));
        assertTrue(answer.contains("顺序") || answer.contains("名单") || answer.contains("名字"));
    }
    @Test void originalFailedTacticalFollowupsReadTheSavedTeamWithoutFreeMechanics() {
        ask("朱紫有哪些道馆馆主？请编号列出。",false);
        ask("看看你刚才列表中第四位馆主的队伍。",false);
        ask("我的 test1 队伍能应对这位馆主吗？",false);
        String initial=transcript.getLast().getText();
        assertTrue(initial.contains("未明确"));
        String first=ask("根据我实际保存的招式，建议怎么出招？",false);
        assertFalse(first.contains("| 槽位 |"));assertTrue(first.contains("按已保存招式"));assertTrue(first.length()<initial.length());
        assertTrue(first.contains("已读取队伍：test1"));assertTrue(first.contains("海岱初战"));assertTrue(first.contains("燕返 / 重踏"));
        String sixth=ask("再看第六位馆主，需要调整哪些成员？",false);
        assertTrue(sixth.contains("莱姆初战"));assertTrue(sixth.contains("不足两名"));assertFalse(sixth.contains("共有 **8"));
        var beforeStatus=teams.snapshotForOwner(team.id(),"owner-a");
        String status=ask("你刚才说的调整已经保存到我的队伍了吗？",false);
        assertTrue(status.contains("本次没有执行保存或修改"));assertFalse(status.contains("没有唯一匹配"));
        assertEquals(beforeStatus,teams.snapshotForOwner(team.id(),"owner-a"));
        String corrected=ask("我说错了，目标是海岱，不是莱姆，还是刚才那支队伍。",false);
        assertTrue(corrected.contains("海岱初战"));assertFalse(corrected.contains("莱姆初战"));verifyNoInteractions(model);
    }

}
