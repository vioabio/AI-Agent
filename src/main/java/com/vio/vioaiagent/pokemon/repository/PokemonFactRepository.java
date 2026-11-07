package com.vio.vioaiagent.pokemon.repository;

import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.domain.RulesetBinding;
import com.vio.vioaiagent.pokemon.domain.VersionedDataRelease;

import java.util.List;
import java.util.Optional;

/** 版本化事实读取边界；实现按 prod/非 prod profile 切换。 */
public interface PokemonFactRepository {
    List<PokemonFact> search(String dataReleaseId, String slug, Integer nationalNo, int limit);

    Optional<PokemonFact> findByFormId(long formId, String dataReleaseId);

    Optional<VersionedDataRelease> findDataRelease(String dataReleaseId);

    Optional<RulesetBinding> findRuleset(String rulesetId);

    default boolean isQueryableRelease(VersionedDataRelease release) {
        return java.util.Set.of("ACTIVE", "REVIEWED", "PUBLISHED").contains(release.status().toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * 检查形态是否被规则集允许。最小本地仓储没有可用性表时默认允许，
     * 生产 JDBC 实现按 pokemon_availability fail-closed 查询。
     */
    default boolean isAvailable(long formId, String dataReleaseId, String rulesetId) {
        return true;
    }
}
