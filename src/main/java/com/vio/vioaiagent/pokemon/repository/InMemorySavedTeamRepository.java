package com.vio.vioaiagent.pokemon.repository;

import com.vio.vioaiagent.pokemon.domain.SavedTeam;
import com.vio.vioaiagent.pokemon.domain.TeamMember;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** 本地/单元测试队伍仓储；按 ownerId 做资源隔离。 */
@Repository
@Primary
@Profile("!prod & !demo")
public class InMemorySavedTeamRepository implements SavedTeamRepository {
    private final AtomicLong sequence = new AtomicLong(0);
    private final ConcurrentMap<Long, SavedTeam> teams = new ConcurrentHashMap<>();

    @Override
    public SavedTeam create(SavedTeam team) {
        if (team == null) throw new IllegalArgumentException("队伍不能为空");
        long id = team.id() > 0 ? team.id() : sequence.incrementAndGet();
        sequence.accumulateAndGet(id, Math::max);
        SavedTeam persisted = copy(id, team);
        teams.put(id, persisted);
        return persisted;
    }

    @Override
    public Optional<SavedTeam> findByIdForOwner(long id, String ownerId) {
        if (id <= 0 || ownerId == null || ownerId.isBlank()) return Optional.empty();
        return Optional.ofNullable(teams.get(id)).filter(value -> ownerId.equals(value.ownerId())).map(this::copy);
    }

    @Override
    public boolean updateForOwner(long id, String ownerId, SavedTeam replacement) {
        if (id <= 0 || ownerId == null || ownerId.isBlank() || replacement == null
                || !ownerId.equals(replacement.ownerId()) || replacement.id() != id) return false;
        SavedTeam current = teams.get(id);
        return current != null && ownerId.equals(current.ownerId())
                && teams.replace(id, current, copy(id, replacement));
    }

    @Override
    public List<SavedTeam> findForOwner(String ownerId, int limit) {
        if (ownerId == null || ownerId.isBlank() || limit <= 0) return List.of();
        return teams.values().stream().filter(value -> ownerId.equals(value.ownerId()))
                .sorted(Comparator.comparingLong(SavedTeam::id).reversed())
                .limit(Math.min(limit, 201)).map(this::copy).toList();
    }

    @Override
    public List<SavedTeam> findForOwner(String ownerId, int limit, int offset) {
        if (ownerId == null || ownerId.isBlank() || limit <= 0 || offset < 0) return List.of();
        return teams.values().stream().filter(value -> ownerId.equals(value.ownerId()))
                .sorted(Comparator.comparingLong(SavedTeam::id).reversed())
                .skip(offset).limit(Math.min(limit, 201)).map(this::copy).toList();
    }

    @Override
    public boolean deleteForOwner(long id, String ownerId) {
        if (id <= 0 || ownerId == null || ownerId.isBlank()) return false;
        SavedTeam current = teams.get(id);
        return current != null && ownerId.equals(current.ownerId()) && teams.remove(id, current);
    }

    private SavedTeam copy(SavedTeam value) {
        return copy(value.id(), value);
    }

    private SavedTeam copy(long id, SavedTeam value) {
        List<TeamMember> members = value.members().stream()
                .map(member -> new TeamMember(member.id(), member.formId(), member.slot())).toList();
        return new SavedTeam(id, value.ownerId(), value.name(), value.rulesetId(), value.dataReleaseId(), members);
    }
}
