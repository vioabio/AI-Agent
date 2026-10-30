package com.vio.vioaiagent.exception;

import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Isolated MVC fixture: proves the real provider and exception advice without model/config access. */
class RequestValidationHttpTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    @Configuration @EnableWebMvc
    @Import({ValidationAutoConfiguration.class, GlobalExceptionHandler.class, Probe.class})
    static class Config {}
    public record Member(@NotBlank String name) {}
    public record Payload(@NotNull @Valid Member member) {}
    public static class Form {
        @NotBlank private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
    @RestController
    static class Probe {
        final AtomicInteger writes = new AtomicInteger();
        final Validator validator;
        Probe(Validator validator) { this.validator = validator; }
        @PostMapping("/validation/body") Map<String, String> body(@Valid @RequestBody Payload payload) {
            writes.incrementAndGet(); return Map.of("name", payload.member().name());
        }
        @PostMapping("/validation/form") void form(@Valid @ModelAttribute Form form) { writes.incrementAndGet(); }
        @GetMapping("/validation/query") int query(@RequestParam("level") @Min(1) int level) { return level; }
        @GetMapping("/validation/return") @Min(1) int invalidReturn() { return 0; }
        @GetMapping("/validation/constraint") void constraint() {
            throw new ConstraintViolationException(validator.validate(new Member("")));
        }
    }
    @BeforeEach void start() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Config.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }
    @AfterEach void close() { if (context != null) context.close(); }
    @Test void providerReallyEvaluatesJakartaConstraints() {
        var validator = context.getBean(Validator.class);
        assertEquals(1, validator.validate(new Member("")).size());
        assertTrue(validator.validate(new Member("杰尼龟")).isEmpty());
    }
    @Test void nestedInvalidAndMissingBodyFieldsAreRejectedBeforeWrites() throws Exception {
        for (String json : new String[]{"{}", "{\"member\":{\"name\":\"\"}}"}) {
            mvc.perform(post("/validation/body").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        }
        assertEquals(0, context.getBean(Probe.class).writes.get());
    }
    @Test void validNestedBodyReachesControllerOnce() throws Exception {
        mvc.perform(post("/validation/body").contentType(MediaType.APPLICATION_JSON).content("{\"member\":{\"name\":\"Squirtle\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Squirtle"));
        assertEquals(1, context.getBean(Probe.class).writes.get());
    }
    @Test void formErrorsReturnBadRequestBeforeWrites() throws Exception {
        mvc.perform(post("/validation/form").param("name", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        assertEquals(0, context.getBean(Probe.class).writes.get());
    }
    @Test void constraintViolationsReturnBadRequest() throws Exception {
        mvc.perform(get("/validation/constraint")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
    }
    @Test void nativeMvcParameterValidationReturnsBadRequest() throws Exception {
        mvc.perform(get("/validation/query").param("level", "0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        mvc.perform(get("/validation/query").param("level", "50")).andExpect(status().isOk()).andExpect(content().string("50"));
    }
    @Test void invalidReturnRemainsServerError() throws Exception {
        mvc.perform(get("/validation/return")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500));
    }
}
