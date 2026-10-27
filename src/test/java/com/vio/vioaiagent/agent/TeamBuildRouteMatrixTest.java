package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.tools.governance.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Deterministic gateway integration matrix; these fixtures are not live model quality evidence. */
class TeamBuildRouteMatrixTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();

    TeamBuildCheckFacts.Request request(BuildCheckFixture f, int count, boolean missing) {
        var members = new ArrayList<TeamBuildCheckFacts.Member>();
        for (int slot = count; slot >= 1; slot--)
            members.add(new TeamBuildCheckFacts.Member(slot, 7, missing && slot % 2 == 0 ? null : f.request().build()));
        return new TeamBuildCheckFacts.Request("pokeapi-reference-v1", "pokemon-scarlet-violet", members);
    }

    String question(TeamBuildCheckFacts.Request r) { return TeamBuildRequestRoute.UI_HEADER + "\n" + json.valueToTree(r); }

    final class Harness implements AutoCloseable {
        final ChatModel model = mock(ChatModel.class);
        final HitlManager hitl = new HitlManager();
        final List<ToolGovernanceEvent> events = new ArrayList<>();
        final ToolExecutionGateway gateway;
        final VioManus agent;
        Harness(LocalBuildCheckService service, RunToolScope scope) {
            var masker = new ParameterMasker();
            gateway = new ToolExecutionGateway(hitl, new PathGuard(List.of(directory.toString())), new CommandGuard(List.of()), masker, new AuditLogger(masker));
            ToolExecutionContextHolder.set(new ToolExecutionContext("owner", "run-team-matrix", Set.of("trainer"), "trace", null, events::add));
            agent = new VioManus(gateway.wrap(ToolCallbacks.from(new PokemonTeamBuildCheckController.Tools(service, scope))), null, model);
            agent.setToolExecutionGateway(gateway);
        }
        String run(String q) {
            var deltas = new ArrayList<String>();
            agent.setCurrentUserRequest(q);
            agent.setAnswerDeltaSink(deltas::add);
            agent.run(q);
            assertEquals(agent.getFinalAnswer(), String.join("", deltas));
            return agent.getFinalAnswer();
        }
        List<ToolGovernanceEvent> finished() { return events.stream().filter(e -> e.eventType().equals("tool.finished")).toList(); }
        public void close() {
            try {
                verify(model, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
                verify(model, never()).stream(any(org.springframework.ai.chat.prompt.Prompt.class));
            }
            finally { ToolExecutionContextHolder.clear(); gateway.shutdown(); hitl.shutdown(); }
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void everySupportedTeamSizeKeepsOrderNullsAndOneBoundInvocation(int count) {
        var f = new BuildCheckFixture(directory);
        var request = request(f, count, true);
        var expected = PokemonTeamBuildCheckController.checkMembers(f.service, request);
        try (var h = new Harness(f.service, null)) {
            var answer = h.run(question(request));
            assertTrue(answer.contains("已核对 " + count + " 位"));
            assertTrue(answer.contains("有缺项：" + count / 2 + " 位"));
            assertTrue(answer.contains("联合可得性未验证"));
            assertEquals(1, h.finished().size());
            var event = h.finished().getFirst();
            assertEquals(TeamBuildRequestRoute.ORIGIN, event.attributes().get("selectionOrigin"));
            var proof = json.valueToTree(event.attributes().get("outputEvidence"));
            assertEquals("VERIFIED", proof.path("status").asText());
            assertEquals(expected.reportSha256(), proof.path("reportSha256").asText());
            int previous = -1;
            for (var member : request.members()) {
                int next = answer.indexOf("#### 第 " + member.slot() + " 位");
                assertTrue(next > previous, "Original member order must survive the complete gateway path");
                previous = next;
            }
        }
    }

    @Test void sixFullyConfiguredMembersFitTheGatewayWithoutTruncation() {
        var f = new BuildCheckFixture(directory);
        var request = request(f, 6, false);
        try (var h = new Harness(f.service, null)) {
            var answer = h.run(question(request));
            assertTrue(answer.contains("已核对 6 位"));
            assertTrue(answer.contains("逐项参考匹配：6 位"));
            assertEquals(1, h.finished().size());
            assertEquals("VERIFIED", json.valueToTree(h.finished().getFirst().attributes().get("outputEvidence")).path("status").asText());
        }
    }

    @Test void unavailableReferenceIsReportedWithoutUpgradingOtherMembers() {
        var f = new BuildCheckFixture(directory);
        var request = new TeamBuildCheckFacts.Request("pokeapi-reference-v1", "pokemon-scarlet-violet", List.of(
                new TeamBuildCheckFacts.Member(1, 7, f.request().build()), new TeamBuildCheckFacts.Member(6, 478, f.request().build())));
        try (var h = new Harness(f.service, null)) {
            var answer = h.run(question(request));
            assertTrue(answer.contains("已核对 2 位"));
            assertTrue(answer.contains("资料不足：1 位"));
            assertTrue(answer.contains("逐项参考匹配：1 位"));
        }
    }

    @Test void versionOrOwnershipRejectionDoesNotReadMembersOrPublishSuccess() {
        var f = new BuildCheckFixture(directory);
        var service = mock(LocalBuildCheckService.class);
        for (RuntimeException denied : List.of(new IllegalArgumentException("版本不一致"), new IllegalStateException("无权访问"))) {
            var scope = mock(RunToolScope.class);
            doThrow(denied).when(scope).check("pokeapi-reference-v1", "pokemon-scarlet-violet");
            try (var h = new Harness(service, scope)) {
                var answer = h.run(question(request(f, 3, true)));
                assertTrue(answer.contains("未取得与原成员配置绑定"));
                assertFalse(answer.contains("已核对 3 位"));
                verify(scope, times(2)).check("pokeapi-reference-v1", "pokemon-scarlet-violet");
                assertEquals(1, h.finished().size());
                assertEquals(1, h.finished().getFirst().attributes().get("retryCount"));
            }
        }
        verifyNoInteractions(service);
    }

    @Test void unexpectedMemberFailureCannotPublishAPartialTeamAsComplete() {
        var f = new BuildCheckFixture(directory);
        var request = request(f, 3, true);
        var service = mock(LocalBuildCheckService.class);
        when(service.check(any())).thenAnswer(call -> {
            BuildCheckFacts.Request member = call.getArgument(0);
            if (member.build() == null) throw new IllegalStateException("fixture failure");
            return f.service.check(member);
        });
        try (var h = new Harness(service, null)) {
            var answer = h.run(question(request));
            assertTrue(answer.contains("未取得与原成员配置绑定"));
            assertFalse(answer.contains("已核对 3 位"));
            var calls = org.mockito.ArgumentCaptor.forClass(BuildCheckFacts.Request.class);
            verify(service, times(4)).check(calls.capture());
            assertEquals(List.of(false, true, false, true), calls.getAllValues().stream().map(r -> r.build() == null).toList());
            assertEquals(1, h.finished().size());
            assertEquals(1, h.finished().getFirst().attributes().get("retryCount"));
        }
    }

    @Test void cancellationBetweenMembersPreventsRemainingReadsAndReportSeal() {
        var f = new BuildCheckFixture(directory);
        var service = mock(LocalBuildCheckService.class);
        when(service.check(any())).thenAnswer(call -> {
            var report = f.service.check(call.getArgument(0));
            Thread.currentThread().interrupt();
            return report;
        });
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> PokemonTeamBuildCheckController.checkMembers(service, request(f, 3, false)));
        } finally { Thread.interrupted(); }
        verify(service, times(1)).check(any());
    }

    @Test void duplicateJsonFieldsAtEveryLevelAndAmbiguousBindingsAreRejected() throws Exception {
        var f = new BuildCheckFixture(directory);
        var r = request(f, 1, false);
        String q = question(r);
        for (String ambiguous : List.of(q.replace("\"slot\":1", "\"slot\":6,\"slot\":1"),
                q.replace("\"pokemonId\":7", "\"pokemonId\":478,\"pokemonId\":7"),
                q.replace("\"level\":50", "\"level\":99,\"level\":50"),
                q.replace("\"hp\":0", "\"hp\":252,\"hp\":0"),
                q.replace("\"dataReleaseId\":", "\"dataReleaseId\":\"other\",\"dataReleaseId\":")))
            assertTrue(TeamBuildRequestRoute.select(ambiguous).isEmpty());
        var report = PokemonTeamBuildCheckController.checkMembers(f.service, r);
        String output = json.writeValueAsString(report), arguments = TeamBuildRequestRoute.arguments(r);
        assertThrows(IllegalArgumentException.class, () -> TeamBuildAnswerEvidence.bound(output,
                arguments.replace("\"slot\":1", "\"slot\":6,\"slot\":1")));
        assertThrows(IllegalArgumentException.class, () -> TeamBuildAnswerEvidence.bound(
                output.replace("\"jointStatus\":", "\"jointStatus\":\"VERIFIED\",\"jointStatus\":"), arguments));
    }

    @Test void lengthBoundaryIsConsistentBetweenSelectionAndAnswerProtection() {
        var f = new BuildCheckFixture(directory);
        String q = question(request(f, 1, true));
        String atLimit = q + " ".repeat(16000 - q.length());
        assertTrue(TeamBuildRequestRoute.select(atLimit).isPresent());
        assertTrue(TeamBuildAnswerEvidence.requested(atLimit));
        assertTrue(TeamBuildRequestRoute.select(atLimit + " ").isEmpty());
        assertFalse(TeamBuildAnswerEvidence.requested(atLimit + " "));
    }
}
