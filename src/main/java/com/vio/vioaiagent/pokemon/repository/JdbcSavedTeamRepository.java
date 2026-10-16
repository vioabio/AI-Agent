package com.vio.vioaiagent.pokemon.repository;

import com.vio.vioaiagent.pokemon.domain.SavedTeam;
import com.vio.vioaiagent.pokemon.domain.TeamMember;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** PostgreSQL 生产队伍仓储；SQL 层同时按 owner_id 过滤，防止越权读取。 */
@Repository
@Primary
@Profile({"prod", "demo"})
public class JdbcSavedTeamRepository implements SavedTeamRepository {
    private final JdbcTemplate jdbc;

    public JdbcSavedTeamRepository(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc 不能为空");
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public SavedTeam create(SavedTeam team) {
        if (team == null) throw new IllegalArgumentException("队伍不能为空");
        Long id = jdbc.queryForObject("""
                INSERT INTO saved_team (owner_id, name, ruleset_id, data_release_id)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, team.ownerId(), team.name(), team.rulesetId(), team.dataReleaseId());
        if (id == null) throw new IllegalStateException("数据库未返回队伍 ID");
        for (TeamMember member : team.members()) {
            jdbc.update("INSERT INTO team_member (team_id, form_id, slot) VALUES (?, ?, ?)",
                    id, member.formId(), member.slot());
        }
        return findByIdForOwner(id, team.ownerId()).orElseThrow(
                () -> new IllegalStateException("队伍写入后无法读取"));
    }

    @Override
    public Optional<SavedTeam> findByIdForOwner(long id, String ownerId) {
        if (id <= 0 || ownerId == null || ownerId.isBlank()) return Optional.empty();
        try {
            SavedTeam team = jdbc.queryForObject("""
                    SELECT id, owner_id, name, ruleset_id, data_release_id
                    FROM saved_team WHERE id = ? AND owner_id = ?
                    """, (rs, row) -> new SavedTeam(rs.getLong("id"), rs.getString("owner_id"),
                            rs.getString("name"), rs.getString("ruleset_id"), rs.getString("data_release_id"),
                            loadMembers(rs.getLong("id"))), id, ownerId.trim());
            return Optional.ofNullable(team);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public boolean updateForOwner(long id, String ownerId, SavedTeam replacement) {
        if (id <= 0 || ownerId == null || ownerId.isBlank() || replacement == null
                || replacement.id() != id || !ownerId.trim().equals(replacement.ownerId())) return false;
        int updated = jdbc.update("""
                UPDATE saved_team SET name = ?, ruleset_id = ?, data_release_id = ?
                WHERE id = ? AND owner_id = ?
                """, replacement.name(), replacement.rulesetId(), replacement.dataReleaseId(), id, ownerId.trim());
        if (updated != 1) return false;
        jdbc.update("DELETE FROM team_member WHERE team_id = ?", id);
        for (TeamMember member : replacement.members()) {
            jdbc.update("INSERT INTO team_member (team_id, form_id, slot) VALUES (?, ?, ?)",
                    id, member.formId(), member.slot());
        }
        return true;
    }

    @Override
    public List<SavedTeam> findForOwner(String ownerId, int limit) {
        if (ownerId == null || ownerId.isBlank() || limit <= 0) return List.of();
        return jdbc.query("""
                SELECT id, owner_id, name, ruleset_id, data_release_id
                FROM saved_team WHERE owner_id = ? ORDER BY id DESC LIMIT ?
                """, (rs, row) -> new SavedTeam(rs.getLong("id"), rs.getString("owner_id"),
                        rs.getString("name"), rs.getString("ruleset_id"), rs.getString("data_release_id"),
                        loadMembers(rs.getLong("id"))), ownerId.trim(), Math.min(limit, 201));
    }

    @Override
    public List<SavedTeam> findForOwner(String ownerId, int limit, int offset) {
        if (ownerId == null || ownerId.isBlank() || limit <= 0 || offset < 0) return List.of();
        return jdbc.query("""
                SELECT id, owner_id, name, ruleset_id, data_release_id
                FROM saved_team WHERE owner_id = ? ORDER BY id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new SavedTeam(rs.getLong("id"), rs.getString("owner_id"),
                        rs.getString("name"), rs.getString("ruleset_id"), rs.getString("data_release_id"),
                        loadMembers(rs.getLong("id"))), ownerId.trim(), Math.min(limit, 201), Math.min(offset, 1_000_000));
    }

    @Override
    @Transactional
    public boolean deleteForOwner(long id, String ownerId) {
        if (id <= 0 || ownerId == null || ownerId.isBlank()) return false;
        return jdbc.update("DELETE FROM saved_team WHERE id = ? AND owner_id = ?", id, ownerId.trim()) == 1;
    }

    private List<TeamMember> loadMembers(long teamId) {
        return jdbc.query("""
                SELECT id, form_id, slot FROM team_member WHERE team_id = ? ORDER BY slot
                """, (rs, row) -> new TeamMember(rs.getLong("id"), rs.getLong("form_id"),
                        rs.getInt("slot")), teamId);
    }
}
