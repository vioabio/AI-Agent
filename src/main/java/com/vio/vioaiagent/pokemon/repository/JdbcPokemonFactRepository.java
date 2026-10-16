package com.vio.vioaiagent.pokemon.repository;

import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.domain.RulesetBinding;
import com.vio.vioaiagent.pokemon.domain.VersionedDataRelease;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** PostgreSQL 生产事实仓储；查询始终显式绑定 data_release_id。 */
@Repository
@Primary
@Profile({"prod", "demo"})
public class JdbcPokemonFactRepository implements PokemonFactRepository {
    private static final String FACT_SELECT = """
            SELECT pf.id AS form_id, ps.id AS species_id, ps.national_no, ps.slug,
                   ps.name_zh AS species_name_zh, pf.form_key, pf.name_zh AS form_name_zh,
                   ff.data_release_id, gr.id AS game_release_id,
                   dr.source_url, dr.checksum, dr.status AS release_status,
                   ff.type1, ff.type2, ff.hp, ff.attack, ff.defense,
                   ff.sp_attack, ff.sp_defense, ff.speed
            FROM form_fact_version ff
            JOIN pokemon_form pf ON pf.id = ff.form_id
            JOIN pokemon_species ps ON ps.id = pf.species_id
            JOIN data_release dr ON dr.id = ff.data_release_id
            JOIN game_release gr ON gr.id = dr.game_release_id
            WHERE ff.data_release_id = ?
              AND UPPER(dr.status) IN ('ACTIVE', 'REVIEWED', 'PUBLISHED')
            """;

    private final JdbcTemplate jdbc;

    public JdbcPokemonFactRepository(JdbcTemplate jdbc) {
        this.jdbc = require(jdbc, "jdbc");
    }

    @Override
    public List<PokemonFact> search(String dataReleaseId, String slug, Integer nationalNo, int limit) {
        if (dataReleaseId == null || dataReleaseId.isBlank() || limit <= 0) return List.of();
        StringBuilder sql = new StringBuilder(FACT_SELECT);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(dataReleaseId.trim());
        if (slug != null && !slug.isBlank()) {
            sql.append(" AND ps.slug = ?");
            args.add(slug.trim());
        }
        if (nationalNo != null) {
            sql.append(" AND ps.national_no = ?");
            args.add(nationalNo);
        }
        sql.append(" ORDER BY ps.national_no, pf.form_key LIMIT ?");
        args.add(Math.min(limit, 200));
        return jdbc.query(sql.toString(), this::mapFact, args.toArray());
    }

    @Override
    public Optional<PokemonFact> findByFormId(long formId, String dataReleaseId) {
        if (formId <= 0 || dataReleaseId == null || dataReleaseId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    FACT_SELECT + " AND ff.form_id = ?", this::mapFact,
                    dataReleaseId.trim(), formId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<VersionedDataRelease> findDataRelease(String dataReleaseId) {
        if (dataReleaseId == null || dataReleaseId.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT dr.id, dr.game_release_id, dr.source_url, dr.checksum, dr.status
                    FROM data_release dr WHERE dr.id = ?
                    """, (rs, row) -> new VersionedDataRelease(rs.getString("id"),
                            rs.getString("game_release_id"), rs.getString("source_url"),
                            rs.getString("checksum"), rs.getString("status")), dataReleaseId.trim()));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<RulesetBinding> findRuleset(String rulesetId) {
        if (rulesetId == null || rulesetId.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, game_release_id, name, enabled
                    FROM ruleset_version WHERE id = ?
                    """, (rs, row) -> new RulesetBinding(rs.getString("id"),
                            rs.getString("game_release_id"), rs.getString("name"), rs.getBoolean("enabled")),
                    rulesetId.trim()));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isAvailable(long formId, String dataReleaseId, String rulesetId) {
        if (formId <= 0 || dataReleaseId == null || dataReleaseId.isBlank()
                || rulesetId == null || rulesetId.isBlank()) return false;
        Boolean value = jdbc.queryForObject("""
                SELECT COALESCE((
                    SELECT pa.available
                    FROM pokemon_availability pa
                    WHERE pa.form_id = ? AND pa.data_release_id = ?
                      AND (pa.ruleset_id = ? OR pa.ruleset_id IS NULL)
                    ORDER BY CASE WHEN pa.ruleset_id = ? THEN 0 ELSE 1 END
                    LIMIT 1
                ), FALSE)
                """, Boolean.class, formId, dataReleaseId.trim(), rulesetId.trim(), rulesetId.trim());
        return Boolean.TRUE.equals(value);
    }

    private PokemonFact mapFact(ResultSet rs, int rowNum) throws SQLException {
        return new PokemonFact(rs.getLong("form_id"), rs.getLong("species_id"), rs.getInt("national_no"),
                rs.getString("slug"), rs.getString("species_name_zh"), rs.getString("form_key"),
                rs.getString("form_name_zh"), rs.getString("data_release_id"), rs.getString("game_release_id"),
                rs.getString("source_url"), rs.getString("checksum"), rs.getString("release_status"),
                rs.getString("type1"), rs.getString("type2"), rs.getInt("hp"), rs.getInt("attack"),
                rs.getInt("defense"), rs.getInt("sp_attack"), rs.getInt("sp_defense"), rs.getInt("speed"));
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }
}
