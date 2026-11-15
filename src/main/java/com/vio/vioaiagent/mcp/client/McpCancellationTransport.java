package com.vio.vioaiagent.mcp.client;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.spec.*;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** Cancellation bridge for the pinned SDK's blocking tool callbacks. */
public final class McpCancellationTransport implements McpClientTransport {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final McpClientTransport delegate;
    private final Semaphore notifications = new Semaphore(64);
    private final AtomicLong requested = new AtomicLong(), written = new AtomicLong(), failed = new AtomicLong();

    public McpCancellationTransport(McpClientTransport delegate) { this.delegate = Objects.requireNonNull(delegate); }

    @Override public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>,Mono<McpSchema.JSONRPCMessage>> handler) {
        return delegate.connect(handler);
    }
    @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        Scope scope = CURRENT.get();
        if (scope != null && message instanceof McpSchema.JSONRPCRequest request && "tools/call".equals(request.method()))
            scope.bind(this, request.id());
        return delegate.sendMessage(message);
    }
    @Override public <T> T unmarshalFrom(Object data, TypeReference<T> type) { return delegate.unmarshalFrom(data,type); }
    @Override public Mono<Void> closeGracefully() { return delegate.closeGracefully(); }

    private void cancel(Object requestId) {
        requested.incrementAndGet();
        if (!notifications.tryAcquire()) { failed.incrementAndGet(); return; }
        try {
            var message = new McpSchema.JSONRPCNotification("2.0", "notifications/cancelled", Map.of("requestId",requestId));
            delegate.sendMessage(message).timeout(Duration.ofSeconds(2))
                    .doFinally(signal -> notifications.release())
                    .subscribe(ignored -> {}, error -> failed.incrementAndGet(), written::incrementAndGet);
        } catch (RuntimeException failure) { notifications.release(); failed.incrementAndGet(); }
    }
    public Snapshot snapshot() { return new Snapshot(requested.get(),written.get(),failed.get()); }
    public record Snapshot(long requested, long written, long failed) { }

    static String call(Supplier<String> action) {
        Scope previous = CURRENT.get(), scope = new Scope();
        CURRENT.set(scope);
        try {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("MCP_CALL_INTERRUPTED");
            String result = action.get();
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("MCP_CALL_INTERRUPTED");
            return result;
        } catch (RuntimeException failure) {
            if (Thread.currentThread().isInterrupted() || cancelled(failure)) scope.cancel();
            throw failure;
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
    private static boolean cancelled(Throwable failure) {
        for (int depth=0;failure!=null && depth<16;depth++,failure=failure.getCause())
            if (failure instanceof InterruptedException || failure instanceof CancellationException || failure instanceof TimeoutException) return true;
        return false;
    }
    private static final class Scope {
        private final List<Invocation> calls = new ArrayList<>();
        void bind(McpCancellationTransport transport,Object id) {
            if (id==null || calls.size()>=16) throw new IllegalStateException("MCP_CALL_SCOPE_LIMIT");
            calls.add(new Invocation(transport,id));
        }
        void cancel() { calls.forEach(call -> call.transport.cancel(call.id)); }
    }
    private record Invocation(McpCancellationTransport transport,Object id) { }
}
