package com.vio.vioaiagent.pokemon.service;

import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.domain.RulesetBinding;
import com.vio.vioaiagent.pokemon.domain.SavedTeam;
import com.vio.vioaiagent.pokemon.domain.TeamMember;
import com.vio.vioaiagent.pokemon.domain.VersionedDataRelease;
import com.vio.vioaiagent.pokemon.repository.PokemonFactRepository;
import com.vio.vioaiagent.pokemon.repository.SavedTeamRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

/**
 * N0 M1 最小资源门面：版本化事实读取和用户队伍生命周期。
 * <p>该类不直接依赖 JDBC，因而可以在本地以显式 fixture 仓储运行；生产
 * profile 由 Jdbc*Repository 提供持久化实现。</p>
 */
@Service
public class PokemonResourceService {
    private final PokemonFactRepository facts;
    private final SavedTeamRepository teams;

    public PokemonResourceService(PokemonFactRepository facts, SavedTeamRepository teams) {
        this.facts = Objects.requireNonNull(facts, "facts");
        this.teams = Objects.requireNonNull(teams, "teams");
    }

    public FactQueryResult searchFacts(String dataReleaseId, String slug, Integer nationalNo, int limit) {
        String release = required(dataReleaseId, "dataReleaseId");
        if (nationalNo != null && nationalNo <= 0) throw new IllegalArgumentException("nationalNo 必须为正数");
        int bounded = boundLimit(limit);
        VersionedDataRelease metadata = facts.findDataRelease(release)
                .orElseThrow(() -> new PokemonResourceNotFoundException("找不到 data_release: " + release));
        requireQueryableRelease(metadata);
        List<PokemonFact> items = facts.search(release, normalize(slug), nationalNo, bounded);
        return new FactQueryResult(release, metadata.gameReleaseId(), metadata.sourceUrl(),
                metadata.checksum(), metadata.status(), items, items.size());
    }

    public PokemonFact findFact(String dataReleaseId, String slug) {
        String release = required(dataReleaseId, "dataReleaseId");
        String normalizedSlug = required(slug, "slug").toLowerCase(Locale.ROOT);
        VersionedDataRelease metadata = facts.findDataRelease(release)
                .orElseThrow(() -> new PokemonResourceNotFoundException("找不到 data_release: " + release));
        requireQueryableRelease(metadata);
        List<PokemonFact> values = facts.search(release, normalizedSlug, null, 2);
        if (values.isEmpty()) throw new PokemonResourceNotFoundException(
                "找不到事实: slug=" + normalizedSlug + ", dataReleaseId=" + release);
        return values.get(0);
    }

    public SavedTeam createTeam(String ownerId, CreateTeamCommand command) {
        SavedTeam draft = prepareTeam(ownerId, command, 0);
        try {
            return teams.create(draft);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new PokemonResourceConflictException("队伍数据违反版本或槽位约束", ex);
        }
    }

    /** 更新整支队伍，仍以同一版本/规则校验链为准。 */
    public SavedTeam updateTeam(String ownerId, long teamId, UpdateTeamCommand command) {
        if (teamId <= 0) throw new IllegalArgumentException("teamId 必须为正数");
        String owner = required(ownerId, "ownerId");
        findTeam(owner, teamId); // 先按 owner 检查，避免越权更新泄露数据库错误。
        CreateTeamCommand createCommand = command == null ? null
                : new CreateTeamCommand(command.name(), command.rulesetId(), command.dataReleaseId(), command.members());
        SavedTeam replacement = prepareTeam(owner, createCommand, teamId);
        try {
            if (!teams.updateForOwner(teamId, owner, replacement)) {
                throw new PokemonResourceNotFoundException("队伍不存在或不属于当前主体");
            }
            return findTeam(owner, teamId);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new PokemonResourceConflictException("队伍数据违反版本或槽位约束", ex);
        }
    }

    public List<SavedTeam> listTeams(String ownerId, int limit) {
        return teams.findForOwner(required(ownerId, "ownerId"), boundPageLimit(limit));
    }

    /** 资源列表的服务端游标切片；offset 已由 Controller 的签名游标校验。 */
    public List<SavedTeam> listTeams(String ownerId, int limit, int offset) {
        if (offset < 0) throw new IllegalArgumentException("队伍游标 offset 无效");
        return teams.findForOwner(required(ownerId, "ownerId"), boundPageLimit(limit), offset);
    }

    public SavedTeam findTeam(String ownerId, long teamId) {
        if (teamId <= 0) throw new IllegalArgumentException("teamId 必须为正数");
        return teams.findByIdForOwner(teamId, required(ownerId, "ownerId"))
                .orElseThrow(() -> new PokemonResourceNotFoundException("队伍不存在或不属于当前主体"));
    }

    public void deleteTeam(String ownerId, long teamId) {
        if (teamId <= 0) throw new IllegalArgumentException("teamId 必须为正数");
        boolean deleted = teams.deleteForOwner(teamId, required(ownerId, "ownerId"));
        if (!deleted) throw new PokemonResourceNotFoundException("队伍不存在或不属于当前主体");
    }

    private SavedTeam prepareTeam(String ownerId, CreateTeamCommand command, long id) {
        String owner = required(ownerId, "ownerId");
        if (command == null) throw new IllegalArgumentException("队伍请求不能为空");
        String releaseId = required(command.dataReleaseId(), "dataReleaseId");
        String rulesetId = required(command.rulesetId(), "rulesetId");
        VersionedDataRelease release = facts.findDataRelease(releaseId)
                .orElseThrow(() -> new PokemonResourceNotFoundException("找不到 data_release: " + releaseId));
        requireQueryableRelease(release);
        RulesetBinding ruleset = facts.findRuleset(rulesetId)
                .orElseThrow(() -> new PokemonResourceNotFoundException("找不到 ruleset: " + rulesetId));
        if (!ruleset.enabled()) throw new PokemonResourceConflictException("ruleset 未启用: " + rulesetId);
        if (!release.gameReleaseId().equals(ruleset.gameReleaseId())) {
            throw new PokemonResourceConflictException("ruleset 与 data_release 不属于同一 game_release");
        }
        List<TeamMember> members = command.members() == null ? List.of() : command.members().stream()
                .map(value -> new TeamMember(0, value.formId(), value.slot())).toList();
        if (members.isEmpty() || members.size() > 6) {
            throw new IllegalArgumentException("队伍成员数量必须为 1～6");
        }
        Set<Integer> slots = new HashSet<>();
        Set<Long> forms = new HashSet<>();
        for (TeamMember member : members) {
            if (member.slot() < 1 || member.slot() > 6) {
                throw new IllegalArgumentException("队伍槽位必须在 1～6 之间");
            }
            if (!slots.add(member.slot())) throw new IllegalArgumentException("队伍槽位不能重复");
            if (!forms.add(member.formId())) throw new IllegalArgumentException("队伍不能重复使用同一形态");
            if (facts.findByFormId(member.formId(), releaseId).isEmpty()) {
                throw new PokemonResourceNotFoundException("找不到 formId=" + member.formId()
                        + " 在 data_release=" + releaseId + " 下的事实");
            }
            if (!facts.isAvailable(member.formId(), releaseId, rulesetId)) {
                throw new PokemonResourceConflictException("形态在当前规则集不可用: formId=" + member.formId());
            }
        }
        return new SavedTeam(id, owner, required(command.name(), "name"), rulesetId, releaseId, members);
    }

    private int boundLimit(int limit) {
        return limit <= 0 ? 50 : Math.min(limit, 200);
    }

    /**
     * Internal resource-page limit: one extra row is allowed for cursor
     * look-ahead while the HTTP contract still exposes at most 200 items.
     */
    private int boundPageLimit(int limit) {
        return limit <= 0 ? 50 : Math.min(limit, 201);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return value.trim();
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private void requireQueryableRelease(VersionedDataRelease release) {
        String status = release.status();
        // 仅允许来源审核后的发布状态进入产品读取链；未知状态 fail closed，
        // 避免把尚未审核的候选 fixture 暴露给 Agent 或前端。
        if (!("ACTIVE".equalsIgnoreCase(status) || "REVIEWED".equalsIgnoreCase(status)
                || "PUBLISHED".equalsIgnoreCase(status))) {
            throw new PokemonResourceConflictException("data_release 尚未通过审核: " + release.id());
        }
    }

    public record FactQueryResult(String dataReleaseId, String gameReleaseId, String sourceUrl,
                                  String checksum, String releaseStatus, List<PokemonFact> items,
                                  int total) {
        public FactQueryResult {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record CreateTeamCommand(String name, String rulesetId, String dataReleaseId,
                                    List<MemberCommand> members) {
        public CreateTeamCommand {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    public record MemberCommand(long formId, int slot) {
    }

    public record UpdateTeamCommand(String name, String rulesetId, String dataReleaseId,
                                    List<MemberCommand> members) {
        public UpdateTeamCommand {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }
}
