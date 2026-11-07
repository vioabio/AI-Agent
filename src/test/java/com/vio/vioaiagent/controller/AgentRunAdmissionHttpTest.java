package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.run.CreateRunCommand;
import com.vio.vioaiagent.run.PokemonAgentRunService;
import com.vio.vioaiagent.run.RunAdmissionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Wire contract only: service-level tests separately prove rejection side effects. */
class AgentRunAdmissionHttpTest {
    private PokemonAgentRunService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        RequestContext.clear();
        service = mock(PokemonAgentRunService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AgentRunController(service)).build();
    }

    @AfterEach
    void clearRequestContext() {
        RequestContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUN_CAPACITY_EXCEEDED", "RUN_PRINCIPAL_LIMIT", "RUN_TENANT_LIMIT"})
    void concurrencyRejectionReturns429AndRetryDelayInsteadOfAcceptedRun(String code) throws Exception {
        when(service.createRun(any(CreateRunCommand.class)))
                .thenThrow(new RunAdmissionException(code, 7, false));

        mvc.perform(post("/api/chat/runs").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "user-a")
                        .header("Idempotency-Key", "admission-fixture")
                        .content("""
                                {"prompt":"fixture prompt","conversationId":"conversation-a"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "7"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message", not(blankOrNullString())))
                .andExpect(jsonPath("$.runId").doesNotExist())
                .andExpect(jsonPath("$.eventsUrl").doesNotExist())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));

        verify(service).createRun(any(CreateRunCommand.class));
        verifyNoMoreInteractions(service);
    }

    @Test
    void executionUnavailableReturns503AndRetryDelayWithoutFabricatingAcceptedBody() throws Exception {
        when(service.createRun(any(CreateRunCommand.class)))
                .thenThrow(new RunAdmissionException("RUN_EXECUTION_UNAVAILABLE", 11, true));

        mvc.perform(post("/api/chat/runs").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "unavailable-fixture")
                        .content("""
                                {"prompt":"fixture prompt"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "11"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RUN_EXECUTION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message", not(blankOrNullString())))
                .andExpect(jsonPath("$.runId").doesNotExist())
                .andExpect(jsonPath("$.eventsUrl").doesNotExist())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));

        verify(service).createRun(any(CreateRunCommand.class));
        verifyNoMoreInteractions(service);
    }
}
