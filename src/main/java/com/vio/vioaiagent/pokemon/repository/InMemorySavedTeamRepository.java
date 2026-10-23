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
@Profile("!prod & !demo")
public class InMemorySavedTeamRepository implements SavedTeamRepository {
    private final AtomicLong sequence = new AtomicLong(0);
    private final ConcurrentMap<Long, SavedTeam> teams = new ConcurrentHashMap<>();
    private final java.util.Map<Long,Long> revisions = new java.util.HashMap<>();

    @Override
    public synchronized SavedTeam create(SavedTeam team) {
        if (team == null) throw new IllegalArgumentException("队伍不能为空");
        long id = team.id() > 0 ? team.id() : sequence.incrementAndGet();
        sequence.accumulateAndGet(id, Math::max);
        SavedTeam persisted = copy(id, team);
        teams.put(id, persisted);
        revisions.merge(id,1L,Long::sum);
        return persisted;
    }

    @Override
    public Optional<SavedTeam> findByIdForOwner(long id, String ownerId) {
        if (id <= 0 || ownerId == null || ownerId.isBlank()) return Optional.empty();
        return Optional.ofNullable(teams.get(id)).filter(value -> ownerId.equals(value.ownerId())).map(this::copy);
    }

    @Override
    public synchronized boolean updateForOwner(long id, String ownerId, SavedTeam replacement) {
        if (id <= 0 || ownerId == null || ownerId.isBlank() || replacement == null
                || !ownerId.equals(replacement.ownerId()) || replacement.id() != id) return false;
        SavedTeam current = teams.get(id);
        if (current == null || !ownerId.equals(current.ownerId())) return false;
        teams.put(id,copy(id,replacement)); revisions.merge(id,1L,Long::sum); return true;
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
    public synchronized boolean deleteForOwner(long id, String ownerId) {
        if (id <= 0 || ownerId == null || ownerId.isBlank()) return false;
        SavedTeam current = teams.get(id);
        return current != null && ownerId.equals(current.ownerId()) && teams.remove(id, current);
    }

    @Override public synchronized Snapshot snapshotForOwner(long id,String owner) {
        return new Snapshot(findByIdForOwner(id,owner).orElseThrow(()->new com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException("队伍不存在或不属于当前主体")),revisions.get(id));
    }
    @Override public synchronized Optional<SavedTeam> compareAndSet(Snapshot expected,SavedTeam replacement,Runnable beforeCommit) {
        beforeCommit.run();
        if (!replacement.ownerId().equals(expected.team().ownerId()) || replacement.id()!=expected.team().id()) throw new IllegalArgumentException("队伍归属冲突");
        if (!java.util.Objects.equals(revisions.get(replacement.id()),expected.revision()) || !teams.containsKey(replacement.id())) return Optional.empty();
        beforeCommit.run();
        updateForOwner(replacement.id(),replacement.ownerId(),replacement);
        return findByIdForOwner(replacement.id(),replacement.ownerId());
    }

    private SavedTeam copy(SavedTeam value) {
        return copy(value.id(), value);
    }

    private SavedTeam copy(long id, SavedTeam value) {
        List<TeamMember> members = value.members().stream()
                .map(member -> new TeamMember(member.id(), member.formId(), member.slot(), member.build())).toList();
        return new SavedTeam(id, value.ownerId(), value.name(), value.rulesetId(), value.dataReleaseId(), members);
    }
}
