package com.vio.vioaiagent.pokemon.repository;

import com.vio.vioaiagent.pokemon.domain.ActivityNotice;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** PostgreSQL 活动资讯仓储；生产 profile 才启用。 */
@Repository
@Primary
@Profile("prod")
public class JdbcActivityNoticeRepository implements ActivityNoticeRepository {
    private static final String COLUMNS = """
            id, notice_key, version_no, title, summary, source_id, source_url,
            license, trust_level, game_release_id, dlc_id, language, region,
            event_type, retrieved_at, effective_from, effective_to, freshness,
            status, checksum, schema_version, content_version, published_at,
            created_at, updated_at
            """;

    private final JdbcTemplate jdbc;

    public JdbcActivityNoticeRepository(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc 不能为空");
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ActivityNotice save(ActivityNotice notice) {
        if (notice == null) throw new IllegalArgumentException("活动资讯不能为空");
        int inserted = jdbc.update("""
                INSERT INTO activity_notice
                    (id, notice_key, version_no, title, summary, source_id, source_url,
                     license, trust_level, game_release_id, dlc_id, language, region,
                     event_type, retrieved_at, effective_from, effective_to, freshness,
                     status, checksum, schema_version, content_version, published_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, notice.id(), notice.noticeKey(), notice.versionNo(), notice.title(), notice.summary(),
                notice.sourceId(), notice.sourceUrl(), notice.license(), notice.trustLevel(),
                notice.gameReleaseId(), notice.dlcId(), notice.language(), notice.region(), notice.eventType(),
                timestamp(notice.retrievedAt()), timestamp(notice.effectiveFrom()), timestamp(notice.effectiveTo()),
                notice.freshness(), notice.status(), notice.checksum(), notice.schemaVersion(), notice.contentVersion(),
                timestamp(notice.publishedAt()), timestamp(notice.createdAt()), timestamp(notice.updatedAt()));
        if (inserted == 0) {
            ActivityNotice existing = findById(notice.id()).orElseThrow(
                    () -> new IllegalStateException("活动资讯冲突但无法读取: " + notice.id()));
            if (!existing.equals(notice)) throw new IllegalStateException("活动资讯版本不可覆盖: " + notice.id());
            return existing;
        }
        return notice;
    }

    @Override
    public Optional<ActivityNotice> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM activity_notice WHERE id = ?", this::map, id.trim()));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<ActivityNotice> find(ActivityNoticeQuery query) {
        if (query == null) return List.of();
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                .append(" FROM activity_notice WHERE effective_from <= ?")
                .append(" AND (effective_to IS NULL OR effective_to > ?)");
        List<Object> args = new ArrayList<>();
        args.add(timestamp(query.at()));
        args.add(timestamp(query.at()));
        if (query.gameReleaseId() != null) {
            sql.append(" AND game_release_id = ?");
            args.add(query.gameReleaseId());
        }
        if (query.dlcId() != null) {
            sql.append(" AND dlc_id = ?");
            args.add(query.dlcId());
        }
        if (query.language() != null) {
            sql.append(" AND lower(language) = lower(?)");
            args.add(query.language());
        }
        if (query.region() != null) {
            sql.append(" AND lower(region) = lower(?)");
            args.add(query.region());
        }
        sql.append(" ORDER BY effective_from DESC, notice_key ASC, version_no DESC, id ASC LIMIT ?");
        args.add(query.limit());
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    @Override
    public void replaceFixture(Collection<ActivityNotice> notices) {
        if (notices == null) return;
        notices.forEach(this::save);
    }

    private ActivityNotice map(ResultSet rs, int rowNum) throws SQLException {
        return new ActivityNotice(rs.getString("id"), rs.getString("notice_key"), rs.getInt("version_no"),
                rs.getString("title"), rs.getString("summary"), rs.getString("source_id"),
                rs.getString("source_url"), rs.getString("license"), rs.getString("trust_level"),
                rs.getString("game_release_id"), rs.getString("dlc_id"), rs.getString("language"),
                rs.getString("region"), rs.getString("event_type"), instant(rs, "retrieved_at"),
                instant(rs, "effective_from"), instant(rs, "effective_to"), rs.getString("freshness"),
                rs.getString("status"), rs.getString("checksum"), rs.getString("schema_version"),
                rs.getString("content_version"), instant(rs, "published_at"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.sql.Date date) return date.toInstant();
        if (value instanceof Instant instant) return instant;
        return Instant.parse(value.toString());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
