package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.run.CreateRunCommand;
import com.vio.vioaiagent.run.PokemonAgentRunService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/** Agent Run 入口必须把兼容 alias 冻结为 canonical 版本键。 */
class AgentRunVersionContextControllerTest {

    @Test
    void createRunPassesCanonicalVersionContextToService() {
        PokemonAgentRunService service = mock(PokemonAgentRunService.class);
        AgentRunController controller = new AgentRunController(service);
        AgentRunController.CreateRunRequest request = new AgentRunController.CreateRunRequest();
        request.prompt = "如何准备单打？";
        request.gameReleaseId = "sv-1";
        request.dlcId = "zero-treasure";
        request.language = "zh";
        request.battleFormat = "singles";

        controller.createRun(request, null, null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(CreateRunCommand.class);
        verify(service).createRun(captor.capture());
        CreateRunCommand command = captor.getValue();
        assertEquals("pokemon-scarlet-violet", command.gameReleaseId());
        assertEquals("the-hidden-treasure-of-area-zero", command.dlcId());
        assertEquals("zh-CN", command.language());
        assertEquals("casual-singles", command.battleFormat());
    }

    @Test
    void unknownVersionAliasIsRejectedBeforeRunCreation() {
        PokemonAgentRunService service = mock(PokemonAgentRunService.class);
        AgentRunController controller = new AgentRunController(service);
        AgentRunController.CreateRunRequest request = new AgentRunController.CreateRunRequest();
        request.prompt = "测试";
        request.gameReleaseId = "pokemon-gen-99";
        request.language = "zh-CN";
        request.battleFormat = "singles";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createRun(request, null, null, null));
        assertTrue(error.getMessage().startsWith("VERSION_CONTEXT_INVALID:"));
        verify(service, never()).createRun(any(CreateRunCommand.class));
    }

    @Test
    void activityEndpointUsesSameCanonicalRegistry() {
        com.vio.vioaiagent.pokemon.service.ActivityNoticeService service =
                mock(com.vio.vioaiagent.pokemon.service.ActivityNoticeService.class);
        whenSearchReturnsEmpty(service);
        ActivityNoticeController controller = new ActivityNoticeController(service);

        var response = controller.list("sv", "zero-secret-treasure", "zh_cn", "CN", 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        // Capture all four scalar arguments in one invocation to prove no alias leaks.
        verify(service).search(captor.capture(), captor.capture(), captor.capture(),
                captor.capture(), org.mockito.ArgumentMatchers.anyInt());
        List<String> values = captor.getAllValues();
        assertEquals(List.of("pokemon-scarlet-violet", "the-hidden-treasure-of-area-zero", "zh-CN", "CN"),
                values);
    }

    private void whenSearchReturnsEmpty(
            com.vio.vioaiagent.pokemon.service.ActivityNoticeService service) {
        when(service.search(any(String.class), any(String.class), any(String.class),
                        any(String.class), anyInt()))
                .thenReturn(new com.vio.vioaiagent.pokemon.service.ActivityNoticeService.ActivityQueryResult(
                        "pokemon-scarlet-violet", "the-hidden-treasure-of-area-zero", "zh-CN", "CN",
                        java.time.Instant.now(), 7, List.of(), 0));
    }
}
