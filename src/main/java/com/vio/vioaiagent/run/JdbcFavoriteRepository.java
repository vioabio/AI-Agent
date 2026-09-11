package com.vio.vioaiagent.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@Profile("prod")
@Primary
public class JdbcFavoriteRepository implements FavoriteRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcFavoriteRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper.copy().findAndRegisterModules();
    }

    @Override
    @Transactional
    public Favorite create(Favorite favorite) {
        if (favorite == null) throw new IllegalArgumentException("收藏不能为空");
        Long id = jdbc.queryForObject("""
                INSERT INTO favorite (owner_id, resource_type, resource_key, label, metadata)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (owner_id, resource_type, resource_key)
                DO UPDATE SET updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """, Long.class, favorite.ownerId(), favorite.resourceType(), favorite.resourceKey(),
                favorite.label(), json(favorite.metadata()));
        return findByIdForOwner(id, favorite.ownerId()).orElseThrow();
    }

    @Override
    public Optional<Favorite> findByIdForOwner(long id, String ownerId) {
        if (id <= 0 || ownerId == null || ownerId.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT * FROM favorite WHERE id=? AND owner_id=?",
                    (rs, row) -> map(rs), id, ownerId.trim()));
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    @Override
    @Transactional
    public Optional<Favorite> updateForOwner(long id, String ownerId, String label,
                                             Map<String, Object> metadata) {
        if (id <= 0 || ownerId == null || ownerId.isBlank()) return Optional.empty();
        int changed = jdbc.update("""
                UPDATE favorite
                   SET label = COALESCE(?, label),
                       metadata = CASE WHEN ? IS NULL THEN metadata ELSE ?::jsonb END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND owner_id = ?
                """, label, metadata == null ? null : json(metadata),
                metadata == null ? null : json(metadata), id, ownerId.trim());
        return changed == 1 ? findByIdForOwner(id, ownerId) : Optional.empty();
    }

    @Override
    public List<Favorite> findForOwner(String ownerId, int limit) {
        if (ownerId == null || ownerId.isBlank() || limit <= 0) return List.of();
        return jdbc.query("SELECT * FROM favorite WHERE owner_id=? ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, row) -> map(rs), ownerId.trim(), Math.min(limit, 201));
    }

    @Override
    public List<Favorite> findForOwner(String ownerId, int limit, int offset) {
        if (ownerId == null || ownerId.isBlank() || limit <= 0 || offset < 0) return List.of();
        return jdbc.query("SELECT * FROM favorite WHERE owner_id=? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), ownerId.trim(), Math.min(limit, 201), Math.min(offset, 1_000_000));
    }

    @Override
    public boolean deleteForOwner(long id, String ownerId) {
        return id > 0 && ownerId != null && jdbc.update("DELETE FROM favorite WHERE id=? AND owner_id=?",
                id, ownerId.trim()) == 1;
    }

    private Favorite map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> metadata;
        try { String raw = rs.getString("metadata"); metadata = raw == null ? Map.of() : mapper.readValue(raw, MAP_TYPE); }
        catch (Exception ex) { throw new java.sql.SQLException("收藏 metadata 无法解析", ex); }
        Timestamp created = rs.getTimestamp("created_at"), updated = rs.getTimestamp("updated_at");
        return new Favorite(rs.getLong("id"), rs.getString("owner_id"), rs.getString("resource_type"),
                rs.getString("resource_key"), rs.getString("label"), metadata,
                created == null ? Instant.now() : created.toInstant(),
                updated == null ? (created == null ? Instant.now() : created.toInstant()) : updated.toInstant());
    }
    private String json(Map<String, Object> value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("收藏 metadata 无法序列化", ex); }
    }
}
