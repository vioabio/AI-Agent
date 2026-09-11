package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.common.Result;
import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.domain.SavedTeam;
import com.vio.vioaiagent.pokemon.calculator.TeamLegalityChecker;
import com.vio.vioaiagent.pokemon.service.PokemonResourceConflictException;
import com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException;
import com.vio.vioaiagent.pokemon.service.PokemonResourceService;
import com.vio.vioaiagent.pokemon.service.BattleCalculationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * N0 M1 资源 API：版本化事实查询及用户队伍 CRUD。
 * <p>生产安全开启时 owner 只能来自 RequestContext；X-User-Id 仅用于本地
 * 安全关闭的离线调试，绝不参与已认证请求的资源授权。</p>
 */
@Tag(name = "Pokemon Resources", description = "版本化宝可梦事实与用户队伍")
@RestController
@RequestMapping("/pokemon")
public class PokemonResourceController {
    private final PokemonResourceService service;
    private final SecurityProperties securityProperties;
    private final BattleCalculationService calculations;

    @Autowired
    public PokemonResourceController(PokemonResourceService service, SecurityProperties securityProperties,
                                     BattleCalculationService calculations) {
        this.service = service;
        this.securityProperties = securityProperties == null ? SecurityProperties.defaults() : securityProperties;
        this.calculations = calculations == null ? new BattleCalculationService() : calculations;
    }

    /** 程序化测试构造器；默认允许 local header fallback。 */
    public PokemonResourceController(PokemonResourceService service) {
        this(service, SecurityProperties.defaults(), new BattleCalculationService());
    }

    /** 兼容未显式提供计算服务的程序化调用方。 */
    public PokemonResourceController(PokemonResourceService service, SecurityProperties securityProperties) {
        this(service, securityProperties, new BattleCalculationService());
    }

    @Operation(summary = "查询版本化宝可梦事实")
    @GetMapping("/facts")
    public ResponseEntity<Result<PokemonResourceService.FactQueryResult>> facts(
            @RequestParam("dataReleaseId") String dataReleaseId,
            @RequestParam(value = "slug", required = false) String slug,
            @RequestParam(value = "nationalNo", required = false) Integer nationalNo,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(Result.success(service.searchFacts(dataReleaseId, slug, nationalNo, limit)));
    }

    @Operation(summary = "按 slug 获取单个版本化事实")
    @GetMapping("/facts/{slug}")
    public ResponseEntity<Result<PokemonFact>> fact(@PathVariable("slug") String slug,
                                                    @RequestParam("dataReleaseId") String dataReleaseId) {
        return ResponseEntity.ok(Result.success(service.findFact(dataReleaseId, slug)));
    }

    @Operation(summary = "计算属性克制倍率")
    @PostMapping("/calculations/type-effectiveness")
    public ResponseEntity<Result<BattleCalculationService.CalculationResult>> typeEffectiveness(
            @RequestBody TypeEffectivenessBody body) {
        if (body == null) throw new IllegalArgumentException("请求不能为空");
        List<String> defend = body.defendTypes == null ? List.of() : body.defendTypes;
        return ResponseEntity.ok(Result.success(calculations.typeEffectiveness(
                body.dataReleaseId, body.attackType, defend.toArray(String[]::new))));
    }

    @Operation(summary = "计算速度线")
    @PostMapping("/calculations/speed")
    public ResponseEntity<Result<BattleCalculationService.CalculationResult>> speed(
            @RequestBody SpeedBody body) {
        if (body == null) throw new IllegalArgumentException("请求不能为空");
        return ResponseEntity.ok(Result.success(calculations.speed(body.dataReleaseId, body.base,
                body.level, body.iv, body.ev, body.natureMultiplier)));
    }

    @Operation(summary = "计算基础伤害区间")
    @PostMapping("/calculations/damage")
    public ResponseEntity<Result<BattleCalculationService.DamageResult>> damage(
            @RequestBody DamageBody body) {
        if (body == null) throw new IllegalArgumentException("请求不能为空");
        return ResponseEntity.ok(Result.success(calculations.damage(body.dataReleaseId, body.level,
                body.power, body.attack, body.defense, body.modifier)));
    }

    @Operation(summary = "校验队伍合法性")
    @PostMapping("/calculations/legality")
    public ResponseEntity<Result<BattleCalculationService.LegalityResult>> legality(
            @RequestBody LegalityBody body) {
        if (body == null) throw new IllegalArgumentException("请求不能为空");
        List<TeamLegalityChecker.Member> members = body.members == null ? List.of() : body.members.stream()
                .map(member -> new TeamLegalityChecker.Member(member.formKey,
                        member.moves == null ? List.of() : member.moves)).toList();
        return ResponseEntity.ok(Result.success(calculations.legality(body.dataReleaseId,
                body.rulesetVersionId, members)));
    }

    @Operation(summary = "创建用户队伍")
    @PostMapping("/teams")
    public ResponseEntity<Result<SavedTeam>> createTeam(
            @RequestBody(required = false) CreateTeamBody body,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        if (body == null) throw new IllegalArgumentException("请求不能为空");
        String owner = scopedOwner(userHeader);
        List<PokemonResourceService.MemberCommand> members = body.members == null ? List.of()
                : body.members.stream().map(value -> new PokemonResourceService.MemberCommand(
                        value.formId, value.slot)).toList();
        PokemonResourceService.CreateTeamCommand command = new PokemonResourceService.CreateTeamCommand(
                body.name, body.rulesetId, body.dataReleaseId, members);
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(service.createTeam(owner, command)));
    }

    @Operation(summary = "查询当前主体的队伍列表")
    @GetMapping("/teams")
    public ResponseEntity<Result<List<SavedTeam>>> teams(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        return ResponseEntity.ok(Result.success(service.listTeams(scopedOwner(userHeader), limit)));
    }

    @Operation(summary = "查询当前主体的队伍")
    @GetMapping("/teams/{teamId}")
    public ResponseEntity<Result<SavedTeam>> team(@PathVariable("teamId") long teamId,
                                                  @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        return ResponseEntity.ok(Result.success(service.findTeam(scopedOwner(userHeader), teamId)));
    }

    @Operation(summary = "修改当前主体的队伍")
    @PatchMapping("/teams/{teamId}")
    public ResponseEntity<Result<SavedTeam>> updateTeam(@PathVariable("teamId") long teamId,
                                                        @RequestBody CreateTeamBody body,
                                                        @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        if (body == null) throw new IllegalArgumentException("请求不能为空");
        List<PokemonResourceService.MemberCommand> members = body.members == null ? List.of()
                : body.members.stream().map(value -> new PokemonResourceService.MemberCommand(value.formId, value.slot)).toList();
        return ResponseEntity.ok(Result.success(service.updateTeam(scopedOwner(userHeader), teamId,
                new PokemonResourceService.UpdateTeamCommand(body.name, body.rulesetId, body.dataReleaseId, members))));
    }

    @Operation(summary = "删除当前主体的队伍")
    @DeleteMapping("/teams/{teamId}")
    public ResponseEntity<Result<Void>> deleteTeam(@PathVariable("teamId") long teamId,
                                                   @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        service.deleteTeam(scopedOwner(userHeader), teamId);
        return ResponseEntity.ok(Result.success());
    }

    private String scopedOwner(String userHeader) {
        String principal = RequestContext.getPrincipalId();
        if (principal != null && !principal.isBlank()) return principal;
        Long authenticated = RequestContext.getUserId();
        if (authenticated != null) return String.valueOf(authenticated);
        if (securityProperties.isEnabled()) throw new PokemonUnauthenticatedException();
        if (userHeader == null || userHeader.isBlank()) return "anonymous";
        return userHeader.trim();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(PokemonResourceNotFoundException.class)
    public ResponseEntity<Result<Void>> notFound(PokemonResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(PokemonResourceConflictException.class)
    public ResponseEntity<Result<Void>> conflict(PokemonResourceConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.error(HttpStatus.CONFLICT.value(), ex.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Result.error(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(PokemonUnauthenticatedException.class)
    public ResponseEntity<Result<Void>> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.error(HttpStatus.UNAUTHORIZED.value(), "缺少认证身份"));
    }

    private static final class PokemonUnauthenticatedException extends RuntimeException {
    }

    public static class CreateTeamBody {
        public String name;
        public String rulesetId;
        public String dataReleaseId;
        public List<MemberBody> members;
    }

    public static class MemberBody {
        public long formId;
        public int slot;
    }

    public static class TypeEffectivenessBody {
        public String dataReleaseId;
        public String attackType;
        public List<String> defendTypes;
    }

    public static class SpeedBody {
        public String dataReleaseId;
        public int base;
        public int level;
        public int iv;
        public int ev;
        public double natureMultiplier = 1.0d;
    }

    public static class DamageBody {
        public String dataReleaseId;
        public int level;
        public int power;
        public int attack;
        public int defense;
        public double modifier = 1.0d;
    }

    public static class LegalityBody {
        public String dataReleaseId;
        public String rulesetVersionId;
        public List<LegalityMemberBody> members;
    }

    public static class LegalityMemberBody {
        public String formKey;
        public List<String> moves;
    }
}
