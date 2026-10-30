package com.vio.vioaiagent.observability;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.SLF4JServiceProvider;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;
import static org.junit.jupiter.api.Assertions.*;

/** Load the actual Spring-aware Logback configuration, not an XML string imitation. */
class LoggingProfileTest {
    @TempDir Path directory;
    final LoggingSystem system = LoggingSystem.get(getClass().getClassLoader());
    LoggerContext context() { return (LoggerContext) LoggerFactory.getILoggerFactory(); }
    void configure(String... profiles) {
        var env = new StandardEnvironment(); env.setActiveProfiles(profiles);
        env.getPropertySources().addFirst(new MapPropertySource("logging-fixture", Map.of("vio.logging.path", directory.toString())));
        system.cleanUp(); context().reset(); context().getStatusManager().clear();
        system.beforeInitialize(); system.initialize(new LoggingInitializationContext(env), "classpath:logback-spring.xml", null);
    }
    @AfterEach void restore() { MDC.clear(); configure("test"); }
    @Test void runtimeHasOneLogbackProvider() {
        var providers = ServiceLoader.load(SLF4JServiceProvider.class).stream().map(p -> p.type().getName()).toList();
        assertEquals(java.util.List.of("ch.qos.logback.classic.spi.LogbackServiceProvider"), providers);
        assertInstanceOf(LoggerContext.class, LoggerFactory.getILoggerFactory());
    }
    @Test void nonProductionProfilesKeepConsoleWithoutUnusedFileAppenderWarnings() {
        for (String profile : new String[]{"local", "test", "demo", ""}) {
            configure(profile.isEmpty() ? new String[0] : new String[]{profile});
            var root = context().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            assertNotNull(root.getAppender("CONSOLE")); assertNull(root.getAppender("JSON_FILE"));
            assertTrue(context().getStatusManager().getCopyOfStatusList().stream()
                    .noneMatch(s -> s.getLevel() >= Status.WARN), "Unexpected Logback status for " + profile);
            assertFalse(Files.exists(directory.resolve("vio-ai-agent.jsonl")));
        }
    }
    @Test void productionStillWritesParsableJsonWithTraceContext() throws Exception {
        configure("prod");
        var root = context().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        assertNotNull(root.getAppender("CONSOLE")); assertNotNull(root.getAppender("JSON_FILE"));
        try (var ignored = MDC.putCloseable("traceId", "logging-fixture-trace")) {
            LoggerFactory.getLogger("com.vio.vioaiagent.logging.fixture").info("structured logging fixture");
        }
        var lines = Files.readAllLines(directory.resolve("vio-ai-agent.jsonl"));
        var json = new ObjectMapper().readTree(lines.getLast());
        assertEquals("structured logging fixture", json.path("message").asText());
        assertEquals("logging-fixture-trace", json.path("mdc").path("traceId").asText());
        assertTrue(context().getStatusManager().getCopyOfStatusList().stream().noneMatch(s -> s.getLevel() >= Status.WARN),
                () -> context().getStatusManager().getCopyOfStatusList().toString());
    }
}
