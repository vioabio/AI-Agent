package com.vio.vioaiagent.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 普通工具生产审计 sink：脱敏、摘要格式和幂等冲突回归。 */
class JdbcToolAuditSinkTest {

    @Test
    void writesMaskedParameterDigestAndNullApproval() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicReference<Object[]> values = new AtomicReference<>();
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            values.set(varargValues(invocation));
            return 1;
        });
        JdbcToolAuditSink sink = new JdbcToolAuditSink(jdbc, new ParameterMasker());

        sink.record(new AuditEntry("trace-1", "run-1", "user-1", "search",
                Map.of("query", "pikachu", "apiKey", "sk-do-not-store"),
                "allow", "gateway", 12, Instant.parse("2026-01-01T00:00:00Z")));

        Object[] actual = values.get();
        assertTrue(actual != null && actual.length == 9);
        assertTrue(((String) actual[0]).matches("[0-9a-f]{64}"));
        assertEquals("TOOL_ALLOW", actual[1]);
        assertEquals("user-1", actual[2]);
        assertEquals("run-1", actual[3]);
        assertEquals("search", actual[4]);
        assertTrue(((String) actual[5]).matches("[0-9a-f]{64}"));
        assertTrue(((String) actual[8]).matches("[0-9a-f]{64}"));
        assertFalse(java.util.Arrays.stream(actual)
                .map(String::valueOf).anyMatch(value -> value.contains("sk-do-not-store")));
        assertTrue(sink.durable());
        assertTrue(sink.failClosedOnError());
    }

    @Test
    void identicalEventRetryIsIdempotentAndTamperIsRejected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicInteger updates = new AtomicInteger();
        AtomicReference<Object[]> firstValues = new AtomicReference<>();
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            Object[] args = varargValues(invocation);
            firstValues.compareAndSet(null, args);
            return updates.incrementAndGet() == 1 ? 1 : 0;
        });
        when(jdbc.query(anyString(),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any(),
                any(Object[].class))).thenAnswer(invocation -> {
            ResultSetExtractor<String> extractor = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("event_hash")).thenReturn((String) firstValues.get()[8]);
            return extractor.extractData(rs);
        });
        JdbcToolAuditSink sink = new JdbcToolAuditSink(jdbc, new ParameterMasker());
        AuditEntry entry = new AuditEntry("trace-2", "run-2", "user-2", "search", Map.of(),
                "allow", "gateway", 3, Instant.parse("2026-01-02T00:00:00Z"));

        sink.record(entry);
        sink.record(entry);
        assertEquals(2, updates.get());

        when(jdbc.query(anyString(),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any(),
                any(Object[].class))).thenAnswer(invocation -> {
            ResultSetExtractor<String> extractor = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("event_hash")).thenReturn("bad-hash");
            return extractor.extractData(rs);
        });
        assertThrows(AuditWriteException.class, () -> sink.record(entry));
    }

    /** Mockito may expose a varargs invocation as flattened arguments on JDK 25. */
    private static Object[] varargValues(org.mockito.invocation.InvocationOnMock invocation) {
        Object[] raw = invocation.getRawArguments();
        if (raw.length == 2 && raw[1] instanceof Object[] values) {
            return values;
        }
        return Arrays.copyOfRange(raw, 1, raw.length);
    }
}
