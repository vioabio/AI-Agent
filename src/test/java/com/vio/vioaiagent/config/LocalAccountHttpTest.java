package com.vio.vioaiagent.config;

import com.vio.vioaiagent.controller.*;
import com.vio.vioaiagent.security.*;
import com.vio.vioaiagent.common.RequestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LocalAccountHttpTest {
    @TempDir Path directory;
    private AnnotationConfigWebApplicationContext context; private MockMvc mvc;
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({LocalAccountSecurityConfiguration.class,LocalAccountController.class,AuthSessionController.class,Probe.class})
    static class Config implements WebMvcConfigurer {
        @Bean LocalAccountService accounts(org.springframework.core.env.Environment env){return new LocalAccountService(env.getRequiredProperty("test.account-path"));}
        @Bean LocalDataClaimService claims(LocalAccountService accounts,org.springframework.core.env.Environment env){return new LocalDataClaimService(accounts,env.getRequiredProperty("test.history-path"),env.getRequiredProperty("test.teams-path"));}
        @Bean OidcProperties oidc(){return mock(OidcProperties.class);}
        @Bean SecurityProperties security(){return new SecurityProperties(false,Map.of(),List.of(),List.of());}
        @Bean OidcPrincipalPolicyAdapter adapter(){return mock(OidcPrincipalPolicyAdapter.class);}
        @Bean CorsProperties cors(){return new CorsProperties(List.of("http://localhost:3000"));}
        @Bean ApiKeyAuthInterceptor identity(){return new ApiKeyAuthInterceptor(new TokenStore(security()),security());}
        @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(identity()).addPathPatterns("/conversations/**");}
    }
    @RestController static class Probe {
        @GetMapping("/conversations/probe") Map<String,Object> identity(){return Map.of("owner",RequestContext.getPrincipalId(),"roles",RequestContext.getRoles(),"tools",RequestContext.getPrincipalPolicy().allowedTools());}
    }
    @BeforeEach void start(){
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("local");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of("vio.local-accounts.enabled","true","test.account-path",directory.resolve("accounts.sqlite").toString(),"test.history-path",directory.resolve("history.sqlite").toString(),"test.teams-path",directory.resolve("teams.sqlite").toString())));
        context.register(Config.class);context.refresh();mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close(){context.close();RequestContext.clear();}
    private String csrf() throws Exception {var result=mvc.perform(get("/auth/session")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("local")).andReturn();return result.getResponse().getCookie("CSRF-TOKEN").getValue();}
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(String path,String json,String token){return post(path).cookie(new jakarta.servlet.http.Cookie("CSRF-TOKEN",token)).header("X-CSRF-TOKEN",token).header("Origin","http://localhost:3000").contentType(MediaType.APPLICATION_JSON).content(json);}
    private MockHttpSession login(String token) throws Exception {
        mvc.perform(write("/auth/local/register","{\"username\":\"trainer\",\"nickname\":\"小智\",\"password\":\"fixture-password\"}",token)).andExpect(status().isCreated()).andExpect(jsonPath("$.recoveryCode").isString());
        return (MockHttpSession)mvc.perform(write("/auth/local/login","{\"username\":\"trainer\",\"password\":\"fixture-password\"}",token)).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    @Test void csrfOriginAndAnonymousAccessAreEnforced() throws Exception {
        mvc.perform(get("/auth/local/profile")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/local/login").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        String token=csrf();mvc.perform(write("/auth/local/login","{}",token).with(request->{request.removeHeader("Origin");request.addHeader("Origin","https://evil.example");return request;})).andExpect(status().isForbidden());
    }
    @Test void invalidRegistrationCannotReserveUsernameOrCreateSession() throws Exception {
        String token=csrf();
        for (String json : List.of("{}", "{\"username\":\"trainer\",\"password\":\"short\"}",
                "{\"username\":\"trainer\",\"password\":\""+"密".repeat(25)+"\"}")) {
            var result=mvc.perform(write("/auth/local/register",json,token)).andExpect(status().isBadRequest()).andReturn();
            assertNull(result.getRequest().getSession(false));
            assertFalse(result.getResponse().getContentAsString().contains("short"));
        }
        // The same username is still available after rejected registrations.
        login(token);
    }
    @Test void sessionBindsTrainerIdentityAndRejectsSpoofedOwner() throws Exception {
        String token=csrf();var session=login(token);
        mvc.perform(get("/auth/session").session(session)).andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.tenantId").value("local"));
        var result=mvc.perform(get("/conversations/probe").session(session).header("X-User-Id","victim")).andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("trainer")).andReturn();
        var json=new ObjectMapper().readTree(result.getResponse().getContentAsString());assertTrue(json.path("owner").asText().startsWith("local-"));assertFalse(json.path("tools").toString().contains("terminal"));
        mvc.perform(get("/knowledge/probe").session(session).header("X-Role","admin")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/corrections").session(session).header("X-Role","operator")).andExpect(status().isForbidden());
        mvc.perform(write("/logout","{}",token).session(session)).andExpect(status().isNoContent());assertTrue(session.isInvalid());
    }
    @Test void passwordRotationRevokesOtherSessionsAndExpiredSessionCannotRead() throws Exception {
        String token=csrf();var first=login(token);
        var second=(MockHttpSession)mvc.perform(write("/auth/local/login","{\"username\":\"trainer\",\"password\":\"fixture-password\"}",token)).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc.perform(write("/auth/local/password","{\"currentPassword\":\"fixture-password\",\"newPassword\":\"changed-password\"}",token).session(first)).andExpect(status().isOk()).andExpect(jsonPath("$.recoveryCode").isString());
        mvc.perform(get("/auth/local/profile").session(second)).andExpect(status().isUnauthorized());
    }
}
