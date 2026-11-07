package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.run.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConversationConstraintsControllerTest {
    private static final String BODY="""
            {"revision":0,"responseStyle":"CONCISE","trainingBudget":"LOW","avoidLegendary":true,"notes":"新手"}
            """;
    @Test void authenticatedIdentityOverridesForeignHeaderAndSavedSettingsAreReturned() throws Exception {
        var service=mock(PokemonAgentRunService.class);var input=new ConversationConstraints(0,ConversationConstraints.ResponseStyle.CONCISE,ConversationConstraints.TrainingBudget.LOW,true,"新手");
        when(service.saveConversationConstraints("c","owner",input)).thenReturn(input.next());
        var mvc=MockMvcBuilders.standaloneSetup(new AgentRunController(service)).build();
        RequestContext.setPrincipalId("owner");
        try {
            mvc.perform(put("/chat/conversations/c/constraints").header("X-User-Id","foreign").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.notes").value("新手"));
            verify(service).saveConversationConstraints("c","owner",input);
        } finally { RequestContext.clear(); }
    }
    @Test void foreignResourceAndStaleRevisionHaveStableHttpStatuses() throws Exception {
        var service=mock(PokemonAgentRunService.class);when(service.conversationConstraints("c","anonymous")).thenThrow(new RunNotFoundException("c"));
        when(service.saveConversationConstraints(eq("c"),eq("anonymous"),any())).thenThrow(new RunConflictException("c",0));
        var mvc=MockMvcBuilders.standaloneSetup(new AgentRunController(service)).build();
        mvc.perform(get("/chat/conversations/c/constraints")).andExpect(status().isNotFound());
        mvc.perform(put("/chat/conversations/c/constraints").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isConflict());
    }
    @Test void invalidOrMissingFieldsNeverReachTheStore() throws Exception {
        var service=mock(PokemonAgentRunService.class);var mvc=MockMvcBuilders.standaloneSetup(new AgentRunController(service)).build();
        for(String body:new String[]{"{}",BODY.replace("CONCISE","ADMIN"),BODY.replace("新手","x".repeat(801)),BODY.replace("\"revision\":0,","")})
            mvc.perform(put("/chat/conversations/c/constraints").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
