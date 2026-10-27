package com.vio.vioaiagent.observability;

import com.vio.vioaiagent.config.TaskBudgetProperties;
import java.util.*;
import static com.vio.vioaiagent.observability.TaskBudgetExceededException.Reason.*;

/** Shared across physical attempts of one Run. Admission is atomic and reservations are never refunded. */
public final class TaskModelBudget {
    public static final String SCHEMA = "task-budget-v1";
    public static final String ACCOUNTING = "MAX_RESERVATION_OR_PROVIDER_V1";
    private final String budgetId;
    private final int maxCalls;
    private final long tokenLimit;
    private int calls;
    private long reserved, charged;
    private boolean closed;
    private TaskBudgetExceededException failure;

    public TaskModelBudget(TaskBudgetProperties limits) {
        this(UUID.randomUUID().toString(), limits.maxModelCalls(), limits.tokenLimit(), 0, 0, 0);
    }

    private TaskModelBudget(String id, int maxCalls, long limit, int calls, long reserved, long charged) {
        this.budgetId = id; this.maxCalls = maxCalls; this.tokenLimit = limit;
        this.calls = calls; this.reserved = reserved; this.charged = charged;
    }

    public static TaskModelBudget unavailable(TaskBudgetProperties limits) {
        var budget = new TaskModelBudget(limits);
        budget.failure = new TaskBudgetExceededException(STATE_UNAVAILABLE);
        return budget;
    }

    /** Only accept a server-owned, frozen post-approval attempt snapshot; caller verifies event ordering. */
    public static TaskModelBudget restore(Map<?, ?> state, TaskBudgetProperties current) {
        try {
            if (!SCHEMA.equals(state.get("schemaVersion")) || !"RUN".equals(state.get("scope"))
                    || !ACCOUNTING.equals(state.get("accounting")) || !"ACTIVE".equals(state.get("status"))
                    || !Boolean.TRUE.equals(state.get("stateKnown"))
                    || !Boolean.FALSE.equals(state.get("closed")) || state.get("blockReason") != null) {
                return unavailable(current);
            }
            String id = (String) state.get("budgetId");
            if (id == null || !UUID.fromString(id).toString().equals(id)) return unavailable(current);
            int max = Math.toIntExact(integer(state, "maxModelCalls"));
            long limit = integer(state, "tokenLimit");
            new TaskBudgetProperties(max, limit);
            int calls = Math.toIntExact(integer(state, "modelCalls"));
            long reserved = integer(state, "reservedTokens"), charged = integer(state, "chargedTokens");
            if (calls < 0 || calls > max || reserved < 0 || charged < reserved || charged > limit
                    || calls == 0 && (reserved != 0 || charged != 0)
                    || calls > 0 && reserved < calls
                    || integer(state, "remainingModelCalls") != max - calls
                    || integer(state, "remainingTokens") != limit - charged) return unavailable(current);
            var budget = new TaskModelBudget(id, Math.min(max, current.maxModelCalls()),
                    Math.min(limit, current.tokenLimit()), calls, reserved, charged);
            if (calls > budget.maxCalls) budget.failure = new TaskBudgetExceededException(CALL_LIMIT);
            else if (charged > budget.tokenLimit) budget.failure = new TaskBudgetExceededException(TOKEN_LIMIT);
            return budget;
        } catch (RuntimeException invalid) { return unavailable(current); }
    }

    private static long integer(Map<?, ?> state, String key) {
        Object value = state.get(key);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException("Invalid task budget counter");
        }
        return ((Number) value).longValue();
    }

    public synchronized Ticket reserve(long inputEstimate, int outputCap) {
        if (closed) throw new java.util.concurrent.CancellationException("Task model scope closed");
        throwIfBlocked();
        if (inputEstimate < 0 || outputCap < 1) throw block(ESTIMATE_UNAVAILABLE);
        if (calls >= maxCalls) throw block(CALL_LIMIT);
        long amount;
        try { amount = Math.addExact(inputEstimate, outputCap); }
        catch (ArithmeticException invalid) { throw block(ESTIMATE_UNAVAILABLE); }
        if (amount > tokenLimit - charged) throw block(TOKEN_LIMIT);
        calls++; reserved += amount; charged += amount;
        return new Ticket(this, amount);
    }

    public synchronized Ticket missingEstimate() { throw block(ESTIMATE_UNAVAILABLE); }

    /** Cumulative streaming usage replaces earlier usage; only positive differences affect the reservation. */
    public synchronized void reconcile(Ticket ticket, ProviderTokenUsage usage) {
        if (closed || ticket == null || ticket.owner != this || usage == null) return;
        long next = Math.max(ticket.charged, usage.totalTokens());
        charged = Math.addExact(charged, next - ticket.charged);
        ticket.charged = next;
        if (charged > tokenLimit && failure == null) failure = new TaskBudgetExceededException(TOKEN_LIMIT);
    }

    private TaskBudgetExceededException block(TaskBudgetExceededException.Reason reason) {
        if (failure == null) failure = new TaskBudgetExceededException(reason);
        return failure;
    }

    public synchronized void throwIfBlocked() { if (failure != null) throw failure; }
    public synchronized void close() { closed = true; }
    public synchronized Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", SCHEMA); result.put("scope", "RUN"); result.put("budgetId", budgetId);
        result.put("accounting", ACCOUNTING); result.put("maxModelCalls", maxCalls); result.put("tokenLimit", tokenLimit);
        boolean known=failure==null || failure.reason()!=STATE_UNAVAILABLE;
        result.put("stateKnown",known);
        result.put("modelCalls", known?calls:null); result.put("reservedTokens", known?reserved:null); result.put("chargedTokens", known?charged:null);
        result.put("remainingModelCalls", known?Math.max(0, maxCalls - calls):null); result.put("remainingTokens", known?Math.max(0, tokenLimit - charged):null);
        result.put("status", failure == null ? "ACTIVE" : "BLOCKED"); result.put("closed", closed);
        result.put("blockReason", failure == null ? null : failure.reason().name());
        return Collections.unmodifiableMap(result);
    }

    public static final class Ticket {
        private final TaskModelBudget owner;
        private long charged;
        private Ticket(TaskModelBudget owner, long reserved) { this.owner = owner; this.charged = reserved; }
    }
}
