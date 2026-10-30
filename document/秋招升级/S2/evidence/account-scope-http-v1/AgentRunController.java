package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.run.AgentEvent;
import com.vio.vioaiagent.run.CancelResult;
import com.vio.vioaiagent.run.CreateRunCommand;
import com.vio.vioaiagent.run.EventCursorExpiredException;
import com.vio.vioaiagent.run.EventPage;
import com.vio.vioaiagent.run.PokemonAgentRunService;
import com.vio.vioaiagent.run.RunNotFoundException;
import com.vio.vioaiagent.run.RunConflictException;
import com.vio.vioaiagent.run.RunView;
import com.vio.vioaiagent.run.RunAdmissionException;
import com.vio.vioaiagent.run.ChatMessage;
import com.vio.vioaiagent.run.InvalidEventCursorException;
import com.vio.vioaiagent.run.IdempotencyConflictException;
import com.vio.vioaiagent.run.IdempotencyInProgressException;
import com.vio.vioaiagent.pokemon.service.PokemonVersionContextValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.util.List;

/** M3 Run API：入口适配，不编排模型、工具或数据库。 */
@Tag(name = "Agent Run", description = "版本化宝可梦智能体 Run、事件和会话接口")
@RestController
@RequestMapping("/chat")
public class AgentRunController {

    private final PokemonAgentRunService runService;
    private final SecurityProperties securityProperties;
    private final PokemonVersionContextValidator versionContextValidator;

    /** Spring 构造器；安全属性用于控制本地测试兜底是否可用。 */
    @Autowired
    public AgentRunController(PokemonAgentRunService runService, SecurityProperties securityProperties) {
        this(runService, securityProperties, new PokemonVersionContextValidator());
    }

    /** 可注入 registry 快照的构造器；旧 Spring/测试构造器保持不变。 */
    public AgentRunController(PokemonAgentRunService runService, SecurityProperties securityProperties,
                              PokemonVersionContextValidator versionContextValidator) {
        this.runService = runService;
        this.securityProperties = securityProperties;
        this.versionContextValidator = versionContextValidator == null
                ? new PokemonVersionContextValidator() : versionContextValidator;
    }

    /** 兼容程序化/单元测试构造；默认采用本地安全关闭语义。 */
    public AgentRunController(PokemonAgentRunService runService) {
        this(runService, SecurityProperties.defaults());
    }

    @Operation(summary = "创建 Agent Run")
    @PostMapping(value = "/runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunView> createRun(
            @RequestBody(required = false) CreateRunRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceHeader) {
        CreateRunRequest body = request == null ? new CreateRunRequest() : request;
        // 已认证请求以 RequestContext 为唯一身份来源；X-User-Id/body.userId 只用于本地安全关闭时的测试。
        String userId = authenticatedUserOrFallback(userHeader, body.userId);
        String idempotencyKey = firstNonBlankOrNull(idempotencyHeader, body.idempotencyKey);
        String traceId = firstNonBlank(traceHeader, body.traceId, RequestContext.getTraceId());
        Duration timeout = Duration.ofSeconds(Math.max(1, Math.min(body.timeoutSeconds, 3600)));
        // 入口允许已登记的旧 UI 别名，但 Run 只冻结 canonical 版本键；缺失
        // 字段保留给后续 context.card 澄清，不在此处猜测默认游戏或赛制。
        PokemonVersionContextValidator.VersionedContext versioned =
                versionContextValidator.normalize(body.gameReleaseId, body.dlcId,
                        body.language, body.battleFormat);
        CreateRunCommand command = new CreateRunCommand(userId, body.sessionId, body.conversationId,
                body.prompt, versioned.gameReleaseId(), versioned.battleFormatId(), body.rulesetVersionId,
                versioned.language(), versioned.dlcId(), body.dataReleaseId, body.knowledgeReleaseId,
                body.indexReleaseId, body.contractVersion, traceId, idempotencyKey, timeout);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(runService.createRun(command));
    }

    /**
     * 获取 Run 的持久化快照。
     *
     * <p>事件流通常包含终态事件，但代理/保留策略可能返回一个没有新增事件的
     * {@code terminal=true} 页面。客户端可用该快照确认具体终态，避免把空页面
     * 当成网络断线而无限重连。</p>
     */
    @Operation(summary = "获取 Agent Run 快照")
    @GetMapping(value = "/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public RunView run(@PathVariable String runId,
                       @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        return runService.findRun(runId, scopedUser(userHeader))
                .orElseThrow(() -> new RunNotFoundException(runId));
    }

    @Operation(summary = "按游标读取 Run 事件")
    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public EventPage events(@PathVariable String runId,
                            @RequestParam(value = "afterEventId", defaultValue = "0") long afterEventId,
                            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        long cursor = parseCursor(lastEventId, afterEventId);
        return runService.events(runId, scopedUser(userHeader), cursor);
    }

    /**
     * 一次性 SSE 快照；断线只结束订阅，客户端继续用 afterEventId 重连，不会取消 Run。
     */
    @Operation(summary = "SSE 事件快照")
    @GetMapping(value = "/runs/{runId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventStream(@PathVariable String runId,
                                  @RequestParam(value = "afterEventId", defaultValue = "0") long afterEventId,
                                  @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                                  @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        // 先读取并校验游标，再创建/返回 emitter。若游标已经超出保留期，
        // 必须让 @ExceptionHandler 返回 HTTP 410；先创建 emitter 会把响应
        // 提前视为 200，客户端就无法区分“游标过期”和“暂时没有事件”。
        long cursor = parseCursor(lastEventId, afterEventId);
        EventPage page = runService.events(runId, scopedUser(userHeader), cursor);
        SseEmitter emitter = new SseEmitter(30_000L);
        try {
            for (AgentEvent event : page.events()) {
                emitter.send(SseEmitter.event().id(String.valueOf(event.eventId()))
                        .name(event.type().wireName()).data(event));
            }
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    @Operation(summary = "取消 Agent Run")
    @PostMapping(value = "/runs/{runId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public CancelResult cancel(@PathVariable String runId,
                               @RequestHeader(value = "X-User-Id", required = false) String userHeader,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                               @RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.reason;
        return runService.cancel(runId, scopedUser(userHeader), idempotencyKey, reason);
    }

    /** 保留旧程序化调用方的三参数入口；HTTP 无 body 同样走该默认语义。 */
    public CancelResult cancel(String runId, String userHeader, String idempotencyKey) {
        return cancel(runId, userHeader, idempotencyKey, null);
    }

    @Operation(summary = "查询会话消息")
    @GetMapping(value = "/conversations/{conversationId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ChatMessage> messages(@PathVariable String conversationId,
                                      @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        return runService.messages(conversationId, scopedUser(userHeader));
    }

    @GetMapping("/conversations/{conversationId}/constraints")
    public com.vio.vioaiagent.run.ConversationConstraints constraints(@PathVariable String conversationId,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        return runService.conversationConstraints(conversationId, scopedUser(userHeader));
    }

    @org.springframework.web.bind.annotation.PutMapping("/conversations/{conversationId}/constraints")
    public com.vio.vioaiagent.run.ConversationConstraints saveConstraints(@PathVariable String conversationId,
            @RequestBody ConstraintsRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        if (request == null || request.revision == null || request.avoidLegendary == null) throw new IllegalArgumentException("缺少偏好字段");
        var value = new com.vio.vioaiagent.run.ConversationConstraints(request.revision, request.responseStyle,
                request.trainingBudget, request.avoidLegendary, request.notes);
        return runService.saveConversationConstraints(conversationId, scopedUser(userHeader), value);
    }
    public static class ConstraintsRequest {
        public Long revision;
        public com.vio.vioaiagent.run.ConversationConstraints.ResponseStyle responseStyle;
        public com.vio.vioaiagent.run.ConversationConstraints.TrainingBudget trainingBudget;
        public Boolean avoidLegendary;
        public String notes;
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String conversationId,
                                                   @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        runService.deleteConversation(conversationId, scopedUser(userHeader));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(RunNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(EventCursorExpiredException.class)
    public ResponseEntity<EventCursorError> handleExpired(EventCursorExpiredException ex,
                                                           HttpServletRequest request) {
        long retainedFrom = Math.max(1, ex == null ? 1 : ex.retainedFrom());
        return ResponseEntity.status(HttpStatus.GONE).body(new EventCursorError(
                "EVENT_CURSOR_EXPIRED",
                "事件游标已过期，请先获取 Run 最新摘要，再以 afterEventId=retainedFromEventId-1 重新读取事件",
                retainedFrom,
                latestRunUrl(request)));
    }

    /** 旧快照提交失败时让客户端重新拉取 Run，而不是误认为执行失败。 */
    @ExceptionHandler(RunConflictException.class)
    public ResponseEntity<Void> handleConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorBody> handleIdempotencyConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorBody("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key 对应的请求内容不一致"));
    }

    @ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<ErrorBody> handleIdempotencyInProgress() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorBody("IDEMPOTENCY_IN_PROGRESS", "相同 Idempotency-Key 的请求仍在处理中，请稍后重试"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> handleBadRequest() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(RunAdmissionException.class)
    public ResponseEntity<ErrorBody> handleAdmission(RunAdmissionException ex) {
        return ResponseEntity.status(ex.unavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
                .body(new ErrorBody(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(InvalidEventCursorException.class)
    public ResponseEntity<EventCursorError> handleInvalidCursor(InvalidEventCursorException ex) {
        return ResponseEntity.badRequest().body(new EventCursorError(
                "INVALID_EVENT_CURSOR", "Last-Event-ID/afterEventId 必须是非负整数"));
    }

    private String scopedUser(String userHeader) {
        String principal = RequestContext.getPrincipalId();
        if (principal != null && !principal.isBlank()) return principal;
        Long authenticated = RequestContext.getUserId();
        if (authenticated != null) {
            return String.valueOf(authenticated);
        }
        if (securityProperties.isEnabled()) throw new UnauthenticatedException();
        return firstNonBlank(userHeader, "anonymous");
    }

    private String authenticatedUserOrFallback(String userHeader, String bodyUser) {
        String principal = RequestContext.getPrincipalId();
        if (principal != null && !principal.isBlank()) return principal;
        Long authenticated = RequestContext.getUserId();
        if (authenticated != null) {
            return String.valueOf(authenticated);
        }
        if (securityProperties.isEnabled()) throw new UnauthenticatedException();
        return firstNonBlank(userHeader, bodyUser, "anonymous");
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<Void> handleUnauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private static final class UnauthenticatedException extends RuntimeException {
    }

    private long parseCursor(String header, long fallback) {
        if (fallback < 0) throw new InvalidEventCursorException(String.valueOf(fallback));
        if (header == null || header.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(header.trim());
            if (parsed < 0) throw new InvalidEventCursorException(header);
            return parsed;
        } catch (NumberFormatException ex) {
            throw new InvalidEventCursorException(header);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "anonymous";
    }

    private String firstNonBlankOrNull(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    /** HTTP 输入 DTO，字段公开便于 Jackson 在没有额外参数名配置时反序列化。 */
    public static class CreateRunRequest {
        public String userId;
        public String sessionId;
        public String conversationId;
        public String prompt;
        public String gameReleaseId;
        public String battleFormat;
        public String rulesetVersionId;
        public String language = "zh-CN";
        /** N4 版本血缘；缺失时服务显式标记为 partial，不填 unknown。 */
        public String dlcId;
        public String dataReleaseId;
        public String knowledgeReleaseId;
        public String indexReleaseId;
        public String contractVersion = "v1";
        public String traceId;
        public String idempotencyKey;
        public long timeoutSeconds = 120;
    }

    /** 取消请求体；为空时由服务层使用兼容默认原因 user_requested。 */
    public static class CancelRequest {
        public String reason;
    }

    /**
     * 事件游标错误。过期响应携带保留起点和 Run 快照入口，客户端可在不猜测游标的情况下恢复。
     * 两参数构造保留给非法游标等旧调用方，避免改变已有程序化 API。
     */
    public record EventCursorError(String code, String message,
                                   Long retainedFromEventId, String latestRunUrl) {
        public EventCursorError(String code, String message) {
            this(code, message, null, null);
        }
    }
    public record ErrorBody(String code, String message) { }

    private String latestRunUrl(HttpServletRequest request) {
        if (request == null) return null;
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) return null;
        int eventsSegment = uri.indexOf("/events");
        return eventsSegment > 0 ? uri.substring(0, eventsSegment) : uri;
    }
}
