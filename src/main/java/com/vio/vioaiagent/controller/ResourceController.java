package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.common.Result;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.pokemon.domain.SavedTeam;
import com.vio.vioaiagent.pokemon.service.PokemonResourceService;
import com.vio.vioaiagent.run.Conversation;
import com.vio.vioaiagent.run.ConversationRepository;
import com.vio.vioaiagent.run.Favorite;
import com.vio.vioaiagent.run.FavoriteRepository;
import com.vio.vioaiagent.run.IdempotencyConflictException;
import com.vio.vioaiagent.run.IdempotencyInProgressException;
import com.vio.vioaiagent.run.IdempotencyRecord;
import com.vio.vioaiagent.run.IdempotencyStore;
import com.vio.vioaiagent.run.RunNotFoundException;
import com.vio.vioaiagent.run.PokemonAgentRunService;
import com.vio.vioaiagent.run.InvalidResourceCursorException;
import com.vio.vioaiagent.run.ResourceCursorCodec;
import com.vio.vioaiagent.run.ResourceCursorExpiredException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * N2/N4 用户资源 API。所有资源查询先按认证主体过滤，再执行分页/读取；
 * 本地安全关闭时才允许 X-User-Id 作为离线开发兜底。
 */
@Tag(name = "User Resources", description = "会话、队伍和收藏资源")
@RestController
@RequestMapping
public class ResourceController {
    private final ConversationRepository conversations;
    private final PokemonResourceService pokemon;
    private final FavoriteRepository favorites;
    private final SecurityProperties security;
    private final IdempotencyStore idempotency;
    private final ResourceCursorCodec cursorCodec;
    /**
     * 统一会话删除生命周期。资源 API 与 /chat 兼容入口必须共享同一删除服务，
     * 否则 active Run/消息可能在一条入口删除后仍继续执行或泄露。
     */
    private final PokemonAgentRunService runService;

    @Autowired
    public ResourceController(ConversationRepository conversations, PokemonResourceService pokemon,
                              FavoriteRepository favorites, SecurityProperties security,
                              ObjectProvider<IdempotencyStore> idempotencyProvider,
                              ObjectProvider<ResourceCursorCodec> cursorProvider,
                              ObjectProvider<PokemonAgentRunService> runServiceProvider) {
        this.conversations = conversations;
        this.pokemon = pokemon;
        this.favorites = favorites;
        this.security = security == null ? SecurityProperties.defaults() : security;
        this.idempotency = idempotencyProvider == null ? null : idempotencyProvider.getIfAvailable();
        ResourceCursorCodec configuredCursor = cursorProvider == null ? null : cursorProvider.getIfAvailable();
        this.cursorCodec = configuredCursor == null ? localCursorCodec() : configuredCursor;
        this.runService = runServiceProvider == null ? null : runServiceProvider.getIfAvailable();
    }

    /** 兼容程序化测试/嵌入式调用；未注入幂等存储时仍使用资源唯一键。 */
    public ResourceController(ConversationRepository conversations, PokemonResourceService pokemon,
                              FavoriteRepository favorites, SecurityProperties security) {
        this(conversations, pokemon, favorites, security, null, null, null);
    }

    /** 保留旧的五参数程序化构造器，避免嵌入式调用方破坏性升级。 */
    public ResourceController(ConversationRepository conversations, PokemonResourceService pokemon,
                              FavoriteRepository favorites, SecurityProperties security,
                              ObjectProvider<IdempotencyStore> idempotencyProvider) {
        this(conversations, pokemon, favorites, security, idempotencyProvider, null, null);
    }

    @Operation(summary = "创建会话")
    @PostMapping("/conversations")
    public ResponseEntity<Result<Conversation>> createConversation(
            @RequestBody(required = false) ConversationBody body,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String owner = owner(userHeader);
        ConversationBody input = body == null ? new ConversationBody() : body;
        String id = textOr(input.id, "conversation-" + UUID.randomUUID());
        String title = textOr(input.title, "宝可梦训练家会话");
        String scope = "conversation:create:" + owner;
        String hash = hash(String.join("\u001f", owner, id, title));
        IdempotencyStore.Claim claim = claim(scope, idempotencyKey, hash);
        if (claim.conflict()) throw new IdempotencyConflictException(scope, idempotencyKey);
        if (claim.replay()) {
            Conversation replay = replayConversation(claim.record(), owner, scope, idempotencyKey);
            return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(replay));
        }
        Conversation value = new Conversation(id, owner, title, Instant.now(), Instant.now());
        // save 后按 owner 回读，避免并发同 ID 时把其他主体资源当成自己的。
        conversations.save(value);
        Conversation persisted = conversations.findByIdForUser(id, owner)
                .orElseThrow(() -> new IllegalStateException("会话创建冲突"));
        complete(scope, idempotencyKey, hash, Map.of("resourceId", persisted.id()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(persisted));
    }

    @Operation(summary = "查询当前主体会话")
    @GetMapping("/conversations")
    public ResponseEntity<Result<ResourcePage<Conversation>>> listConversations(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        int bounded = Math.max(1, Math.min(limit, 200));
        String owner = owner(userHeader);
        int offset = decodeOffset(cursor, "conversations", owner);
        List<Conversation> values = conversations.findForUser(owner, bounded + 1, offset);
        return ResponseEntity.ok(Result.success(page(values, bounded, "conversations", owner, offset)));
    }

    @Operation(summary = "获取会话")
    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<Result<Conversation>> getConversation(@PathVariable String conversationId,
                                                                  @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        return ResponseEntity.ok(Result.success(conversations.findByIdForUser(conversationId, owner(userHeader))
                .orElseThrow(() -> new RunNotFoundException(conversationId))));
    }

    @Operation(summary = "修改会话标题")
    @PatchMapping("/conversations/{conversationId}")
    public ResponseEntity<Result<Conversation>> updateConversation(@PathVariable String conversationId,
                                                                     @RequestBody ConversationBody body,
                                                                     @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        String owner = owner(userHeader);
        if (body == null || body.title == null || body.title.isBlank()) throw new IllegalArgumentException("title 不能为空");
        if (!conversations.updateTitleForUser(conversationId, owner, body.title)) {
            throw new RunNotFoundException(conversationId);
        }
        return ResponseEntity.ok(Result.success(conversations.findByIdForUser(conversationId, owner).orElseThrow()));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Result<Void>> deleteConversation(@PathVariable String conversationId,
                                                            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        String owner = owner(userHeader);
        if (runService != null) {
            // PokemonAgentRunService 会在同一生命周期锁内校验 owner、取消 active
            // Run、删除消息并软删除会话，避免与 createRun 交错。
            runService.deleteConversation(conversationId, owner);
        } else {
            // 兼容没有 Spring service 的轻量程序化调用；正式 Spring 上下文始终
            // 注入 runService，因此不会走这个缺少 active-run 清理的回退分支。
            if (conversations.findByIdForUser(conversationId, owner).isEmpty()) {
                throw new RunNotFoundException(conversationId);
            }
            conversations.deleteForUser(conversationId, owner);
        }
        return ResponseEntity.ok(Result.success());
    }

    @Operation(summary = "创建队伍")
    @PostMapping("/teams")
    public ResponseEntity<Result<SavedTeam>> createTeam(@RequestBody TeamBody body,
                                                         @RequestHeader(value = "X-User-Id", required = false) String userHeader,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (body == null) throw new IllegalArgumentException("请求不能为空");
        String owner = owner(userHeader);
        List<PokemonResourceService.MemberCommand> members = body.members == null ? List.of() : body.members.stream()
                .map(member -> new PokemonResourceService.MemberCommand(member.formId, member.slot)).toList();
        String scope = "team:create:" + owner;
        String hash = hash(teamFingerprint(owner, body, members));
        IdempotencyStore.Claim claim = claim(scope, idempotencyKey, hash);
        if (claim.conflict()) throw new IdempotencyConflictException(scope, idempotencyKey);
        if (claim.replay()) {
            SavedTeam replay = replayTeam(claim.record(), owner, scope, idempotencyKey);
            return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(replay));
        }
        SavedTeam value = pokemon.createTeam(owner, new PokemonResourceService.CreateTeamCommand(
                body.name, body.rulesetId, body.dataReleaseId, members));
        complete(scope, idempotencyKey, hash, Map.of("resourceId", String.valueOf(value.id())));
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(value));
    }

    @Operation(summary = "查询当前主体队伍")
    @GetMapping("/teams")
    public ResponseEntity<Result<ResourcePage<SavedTeam>>> listTeams(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        int bounded = Math.max(1, Math.min(limit, 200));
        String owner = owner(userHeader);
        int offset = decodeOffset(cursor, "teams", owner);
        List<SavedTeam> values = pokemon.listTeams(owner, bounded + 1, offset);
        return ResponseEntity.ok(Result.success(page(values, bounded, "teams", owner, offset)));
    }

    @Operation(summary = "获取当前主体队伍")
    @GetMapping("/teams/{teamId}")
    public ResponseEntity<Result<SavedTeam>> getTeam(@PathVariable long teamId,
                                                      @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        return ResponseEntity.ok(Result.success(pokemon.findTeam(owner(userHeader), teamId)));
    }

    @Operation(summary = "修改当前主体队伍")
    @PatchMapping("/teams/{teamId}")
    public ResponseEntity<Result<SavedTeam>> updateTeam(@PathVariable long teamId,
                                                         @RequestBody TeamBody body,
                                                         @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        if (body == null) throw new IllegalArgumentException("请求不能为空");
        String owner = owner(userHeader);
        List<PokemonResourceService.MemberCommand> members = body.members == null ? List.of() : body.members.stream()
                .map(member -> new PokemonResourceService.MemberCommand(member.formId, member.slot)).toList();
        SavedTeam updated = pokemon.updateTeam(owner, teamId,
                new PokemonResourceService.UpdateTeamCommand(body.name, body.rulesetId, body.dataReleaseId, members));
        return ResponseEntity.ok(Result.success(updated));
    }

    @Operation(summary = "删除当前主体队伍")
    @DeleteMapping("/teams/{teamId}")
    public ResponseEntity<Result<Void>> deleteTeam(@PathVariable long teamId,
                                                    @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        pokemon.deleteTeam(owner(userHeader), teamId);
        return ResponseEntity.ok(Result.success());
    }

    @Operation(summary = "创建收藏")
    @PostMapping("/favorites")
    public ResponseEntity<Result<Favorite>> createFavorite(@RequestBody FavoriteBody body,
                                                            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
                                                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (body == null) throw new IllegalArgumentException("请求不能为空");
        String owner = owner(userHeader);
        String scope = "favorite:create:" + owner;
        String hash = hash(String.join("\u001f", owner, textOr(body.resourceType, ""),
                textOr(body.resourceKey, ""), textOr(body.label, ""), String.valueOf(body.metadata)));
        IdempotencyStore.Claim claim = claim(scope, idempotencyKey, hash);
        if (claim.conflict()) throw new IdempotencyConflictException(scope, idempotencyKey);
        if (claim.replay()) {
            Favorite replay = replayFavorite(claim.record(), owner, scope, idempotencyKey);
            return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(replay));
        }
        Favorite value = favorites.create(new Favorite(0, owner, body.resourceType,
                body.resourceKey, body.label, body.metadata, Instant.now(), Instant.now()));
        complete(scope, idempotencyKey, hash, Map.of("resourceId", String.valueOf(value.id())));
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(value));
    }

    @Operation(summary = "查询当前主体收藏")
    @GetMapping("/favorites")
    public ResponseEntity<Result<ResourcePage<Favorite>>> listFavorites(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        int bounded = Math.max(1, Math.min(limit, 200));
        String owner = owner(userHeader);
        int offset = decodeOffset(cursor, "favorites", owner);
        List<Favorite> values = favorites.findForOwner(owner, bounded + 1, offset);
        return ResponseEntity.ok(Result.success(page(values, bounded, "favorites", owner, offset)));
    }

    @Operation(summary = "获取当前主体收藏")
    @GetMapping("/favorites/{favoriteId}")
    public ResponseEntity<Result<Favorite>> getFavorite(@PathVariable long favoriteId,
                                                         @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        return ResponseEntity.ok(Result.success(favorites.findByIdForOwner(favoriteId, owner(userHeader))
                .orElseThrow(() -> new RunNotFoundException(String.valueOf(favoriteId)))));
    }

    @Operation(summary = "修改当前主体收藏的展示信息")
    @PatchMapping("/favorites/{favoriteId}")
    public ResponseEntity<Result<Favorite>> updateFavorite(@PathVariable long favoriteId,
                                                             @RequestBody FavoritePatchBody body,
                                                             @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        if (body == null) throw new IllegalArgumentException("请求不能为空");
        if (body.label == null && body.metadata == null) {
            throw new IllegalArgumentException("至少提供 label 或 metadata");
        }
        String owner = owner(userHeader);
        Favorite current = favorites.findByIdForOwner(favoriteId, owner)
                .orElseThrow(() -> new RunNotFoundException(String.valueOf(favoriteId)));
        String label = body.label == null ? current.label() : body.label;
        if (label != null && label.length() > 256) throw new IllegalArgumentException("label 过长");
        java.util.Map<String, Object> metadata = body.metadata == null ? current.metadata() : body.metadata;
        Favorite updated = favorites.updateForOwner(favoriteId, owner, label, metadata)
                .orElseThrow(() -> new RunNotFoundException(String.valueOf(favoriteId)));
        return ResponseEntity.ok(Result.success(updated));
    }

    @Operation(summary = "删除当前主体收藏")
    @DeleteMapping("/favorites/{favoriteId}")
    public ResponseEntity<Result<Void>> deleteFavorite(@PathVariable long favoriteId,
                                                        @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        if (!favorites.deleteForOwner(favoriteId, owner(userHeader))) throw new RunNotFoundException(String.valueOf(favoriteId));
        return ResponseEntity.ok(Result.success());
    }

    private String owner(String header) {
        String principal = RequestContext.getPrincipalId();
        if (principal != null && !principal.isBlank()) return principal;
        Long numeric = RequestContext.getUserId();
        if (numeric != null) return String.valueOf(numeric);
        if (security.isEnabled()) throw new UnauthenticatedResourceException();
        return textOr(header, "anonymous");
    }

    private IdempotencyStore.Claim claim(String scope, String key, String requestHash) {
        if (idempotency == null || key == null || key.isBlank()) return IdempotencyStore.Claim.acceptedClaim();
        if (key.trim().length() > 256) throw new IllegalArgumentException("Idempotency-Key 长度不能超过 256");
        return idempotency.claim(scope, key.trim(), requestHash, Duration.ofHours(24), Instant.now());
    }

    private void complete(String scope, String key, String requestHash, Map<String, Object> body) {
        if (idempotency == null || key == null || key.isBlank()) return;
        idempotency.complete(scope, key.trim(), requestHash, 201, body, Instant.now());
    }

    private Conversation replayConversation(java.util.Optional<IdempotencyRecord> record, String owner,
                                             String scope, String key) {
        String id = resourceId(record, scope, key);
        return conversations.findByIdForUser(id, owner)
                .orElseThrow(() -> new RunNotFoundException(id));
    }

    private SavedTeam replayTeam(java.util.Optional<IdempotencyRecord> record, String owner,
                                 String scope, String key) {
        long id = parseResourceId(record, scope, key);
        return pokemon.findTeam(owner, id);
    }

    private Favorite replayFavorite(java.util.Optional<IdempotencyRecord> record, String owner,
                                    String scope, String key) {
        long id = parseResourceId(record, scope, key);
        return favorites.findByIdForOwner(id, owner)
                .orElseThrow(() -> new RunNotFoundException(String.valueOf(id)));
    }

    private String resourceId(java.util.Optional<IdempotencyRecord> record, String scope, String key) {
        if (record == null || record.isEmpty()) throw new IdempotencyInProgressException(scope, key);
        Object value = record.get().responseBody().get("resourceId");
        if (value == null || value.toString().isBlank()) throw new IdempotencyInProgressException(scope, key);
        return value.toString();
    }

    private long parseResourceId(java.util.Optional<IdempotencyRecord> record, String scope, String key) {
        try { return Long.parseLong(resourceId(record, scope, key)); }
        catch (NumberFormatException ex) { throw new IllegalStateException("幂等资源 ID 无效"); }
    }

    private String teamFingerprint(String owner, TeamBody body,
                                   List<PokemonResourceService.MemberCommand> members) {
        StringBuilder value = new StringBuilder(owner).append('\u001f')
                .append(textOr(body.name, "")).append('\u001f')
                .append(textOr(body.rulesetId, "")).append('\u001f')
                .append(textOr(body.dataReleaseId, ""));
        members.stream().sorted(java.util.Comparator.comparingInt(PokemonResourceService.MemberCommand::slot))
                .forEach(member -> value.append('\u001f').append(member.formId()).append(':').append(member.slot()));
        return value.toString();
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(textOr(value, "").getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    private static String textOr(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }

    private int decodeOffset(String rawCursor, String resource, String owner) {
        if (rawCursor == null || rawCursor.isBlank()) return 0;
        return cursorCodec.decode(rawCursor.trim(), resource, owner).offset();
    }

    private <T> ResourcePage<T> page(List<T> loaded, int limit, String resource, String owner, int offset) {
        List<T> values = loaded == null ? List.of() : loaded;
        boolean hasMore = values.size() > limit;
        List<T> visible = hasMore ? values.subList(0, limit) : values;
        String next = hasMore ? cursorCodec.encode(resource, owner, offset + limit) : null;
        return ResourcePage.of(visible, next);
    }

    private ResourceCursorCodec localCursorCodec() {
        return new ResourceCursorCodec("local-resource-cursor-" + System.getProperty("user.dir", "local"),
                Duration.ofMinutes(15));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(UnauthenticatedResourceException.class)
    public ResponseEntity<Result<Void>> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error(401, "缺少认证身份"));
    }

    /**
     * 资源不存在**或不属于当前主体**统一返回 404。
     *
     * <p>{@code @ExceptionHandler} 只作用于声明它的控制器：{@code AgentRunController}
     * 上针对 {@code RunNotFoundException} 的 404 映射不会覆盖本控制器。缺少这个处理器时，
     * {@code GET /conversations/{id}} 在跨主体访问或资源不存在时会冒泡到
     * {@code GlobalExceptionHandler} 变成 500，既不符合接口契约，也让“无权访问”
     * 与“服务端故障”无法区分（F02 端到端测试发现）。
     *
     * <p>刻意不区分「不存在」与「无权访问」：两者都返回 404，
     * 避免通过状态码差异探测其他主体的资源是否存在。
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(RunNotFoundException.class)
    public ResponseEntity<Result<Void>> resourceNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "资源不存在或无权访问"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Result<Void>> idempotencyConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.error(409, "Idempotency-Key 对应的请求内容不一致"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<Result<Void>> idempotencyInProgress() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.error(409, "相同 Idempotency-Key 的请求仍在处理中，请稍后重试"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ResourceCursorExpiredException.class)
    public ResponseEntity<Result<Void>> cursorExpired() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Result.error(HttpStatus.GONE.value(), "资源游标已过期，请重新加载列表"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(InvalidResourceCursorException.class)
    public ResponseEntity<Result<Void>> invalidCursor() {
        return ResponseEntity.badRequest()
                .body(Result.error(HttpStatus.BAD_REQUEST.value(), "资源游标无效"));
    }

    public static class ConversationBody { public String id; public String title; }
    public static class TeamBody { public String name; public String rulesetId; public String dataReleaseId; public List<MemberBody> members; }
    public static class MemberBody { public long formId; public int slot; }
    public static class FavoriteBody { public String resourceType; public String resourceKey; public String label; public Map<String, Object> metadata; }
    /** PATCH 只修改用户可编辑字段；resourceType/resourceKey 保持 immutable。 */
    public static class FavoritePatchBody { public String label; public Map<String, Object> metadata; }
    public record ResourcePage<T>(List<T> items, String nextCursor) {
        public ResourcePage { items = items == null ? List.of() : List.copyOf(items); }
        static <T> ResourcePage<T> of(List<T> items, String cursor) { return new ResourcePage<>(items, cursor); }
    }
    private static final class UnauthenticatedResourceException extends RuntimeException { }
}
