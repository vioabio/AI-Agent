package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.pokemon.service.*;
import com.vio.vioaiagent.pokemon.team.*;
import com.vio.vioaiagent.run.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TeamWorkflowHttpErrorsTest {
    final PokemonResourceService resources=mock(PokemonResourceService.class);
    final ResourceController controller=new ResourceController(new InMemoryConversationRepository(),resources,
            new InMemoryFavoriteRepository(),SecurityProperties.defaults(),null,null,null);
    @AfterEach void clear() { RequestContext.clear(); }
    @Test void unknownOrForeignTeamIs404OnTheActualRootResourceRoute() throws Exception {
        when(resources.findTeam("trainer",1)).thenThrow(new PokemonResourceNotFoundException("private owner detail"));
        MockMvcBuilders.standaloneSetup(controller).build().perform(get("/teams/1").header("X-User-Id","trainer"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("资源不存在或无权访问"));
    }
    @Test void invalidTeamInputIs400RatherThan500() throws Exception {
        when(resources.createTeam(eq("trainer"),any())).thenThrow(new IllegalArgumentException("invalid EV"));
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/teams").header("X-User-Id","trainer")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"test\",\"rulesetId\":\"casual-v1\",\"dataReleaseId\":\"pokeapi-reference-v1\",\"members\":[]}"))
                .andExpect(status().isBadRequest());
    }
    @Test void typedTeamConflictIs409RatherThan500() throws Exception {
        when(resources.updateTeam(eq("trainer"),eq(1L),any())).thenThrow(new PokemonResourceConflictException("version conflict"));
        MockMvcBuilders.standaloneSetup(controller).build().perform(patch("/teams/1").header("X-User-Id","trainer")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"test\",\"rulesetId\":\"casual-v1\",\"dataReleaseId\":\"pokeapi-reference-v1\",\"members\":[]}"))
                .andExpect(status().isConflict());
    }
    @Test void teamProposalOwnershipFailureReturns404WithoutPrivateDetail() throws Exception {
        var workflow=mock(TeamWorkflowService.class);
        RequestContext.setPrincipalId("local:other");
        when(workflow.outcome("run-test","local:other")).thenThrow(new PokemonResourceNotFoundException("private proposal"));
        MockMvcBuilders.standaloneSetup(new TeamWorkflowController(workflow)).build().perform(get("/chat/runs/run-test/team-change"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("队伍预览不存在"));
    }
    @Test void trainerDecisionCanOnlyForwardItsOwnBoundTeamProposal() throws Exception {
        var workflow=mock(TeamWorkflowService.class);var approvals=mock(HitlController.class);
        RequestContext.setPrincipalId("local:trainer");
        var preview=new TeamWorkflowService.Preview("approval-team",1,"队伍",java.util.List.of(),"策略建议",java.time.Instant.now().plusSeconds(60),"未计算",java.util.List.of());
        when(workflow.outcome("run-test","local:trainer")).thenReturn(new TeamWorkflowService.Outcome(preview,"PENDING",null,null));
        when(approvals.decideRunApproval(eq("approval-team"),eq("run-test"),eq(true),anyString())).thenReturn(com.vio.vioaiagent.common.Result.success(com.vio.vioaiagent.security.HitlManager.ApprovalDecision.allow()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"审批已处理"));
        var mvc=MockMvcBuilders.standaloneSetup(new TeamWorkflowController(workflow,approvals))
                .setControllerAdvice(new com.vio.vioaiagent.exception.GlobalExceptionHandler()).build();
        mvc.perform(post("/chat/runs/run-test/team-change/decision").contentType(MediaType.APPLICATION_JSON).content("{\"approvalId\":\"another-tool-approval\",\"approved\":true}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(approvals);
        mvc.perform(post("/chat/runs/run-test/team-change/decision").contentType(MediaType.APPLICATION_JSON).content("{\"approvalId\":\"approval-team\",\"approved\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.allowed").value(true));
        verify(approvals).decideRunApproval(eq("approval-team"),eq("run-test"),eq(true),anyString());
        mvc.perform(post("/chat/runs/run-test/team-change/decision").contentType(MediaType.APPLICATION_JSON).content("{\"approvalId\":\"approval-team\",\"approved\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
    }
}
