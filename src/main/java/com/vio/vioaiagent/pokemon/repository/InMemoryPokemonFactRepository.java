package com.vio.vioaiagent.pokemon.repository;

import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.domain.PokemonAvailability;
import com.vio.vioaiagent.pokemon.domain.RulesetBinding;
import com.vio.vioaiagent.pokemon.domain.VersionedDataRelease;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地/单元测试事实仓储。默认不加载数据，避免把未经来源审核的样例误当成
 * active release；fixture loader 可以通过带集合的构造器显式注入脱敏数据。
 */
@Repository
@Primary
@Profile("!prod")
public class InMemoryPokemonFactRepository implements PokemonFactRepository {
    private final Map<String, PokemonFact> facts = new ConcurrentHashMap<>();
    private final Map<String, VersionedDataRelease> releases = new ConcurrentHashMap<>();
    private final Map<String, RulesetBinding> rulesets = new ConcurrentHashMap<>();
    private final Map<String, Boolean> availability = new ConcurrentHashMap<>();
    private volatile boolean availabilityConfigured;

    public InMemoryPokemonFactRepository() {
    }

    public InMemoryPokemonFactRepository(Collection<PokemonFact> facts,
                                         Collection<VersionedDataRelease> releases,
                                         Collection<RulesetBinding> rulesets) {
        if (releases != null) releases.forEach(value -> this.releases.put(value.id(), value));
        if (rulesets != null) rulesets.forEach(value -> this.rulesets.put(value.id(), value));
        if (facts != null) facts.forEach(value -> this.facts.put(key(value.formId(), value.dataReleaseId()), value));
    }

    /** 仅供本地 fixture loader/测试使用；不会修改生产 JDBC 数据。 */
    public void replaceFixture(Collection<PokemonFact> values,
                               Collection<VersionedDataRelease> releaseValues,
                               Collection<RulesetBinding> rulesetValues) {
        facts.clear(); releases.clear(); rulesets.clear(); availability.clear(); availabilityConfigured = false;
        if (releaseValues != null) releaseValues.forEach(value -> releases.put(value.id(), value));
        if (rulesetValues != null) rulesetValues.forEach(value -> rulesets.put(value.id(), value));
        if (values != null) values.forEach(value -> facts.put(key(value.formId(), value.dataReleaseId()), value));
    }

    @Override
    public List<PokemonFact> search(String dataReleaseId, String slug, Integer nationalNo, int limit) {
        if (dataReleaseId == null || dataReleaseId.isBlank() || limit <= 0) return List.of();
        String normalizedSlug = slug == null ? null : slug.trim().toLowerCase(Locale.ROOT);
        return facts.values().stream()
                .filter(value -> dataReleaseId.equals(value.dataReleaseId()))
                .filter(value -> normalizedSlug == null || normalizedSlug.isBlank()
                        || normalizedSlug.equals(value.slug().toLowerCase(Locale.ROOT)))
                .filter(value -> nationalNo == null || nationalNo.equals(value.nationalNo()))
                .sorted(Comparator.comparingInt(PokemonFact::nationalNo).thenComparing(PokemonFact::formKey))
                .limit(Math.min(limit, 200))
                .toList();
    }

    @Override
    public Optional<PokemonFact> findByFormId(long formId, String dataReleaseId) {
        if (formId <= 0 || dataReleaseId == null || dataReleaseId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(facts.get(key(formId, dataReleaseId.trim())));
    }

    @Override
    public Optional<VersionedDataRelease> findDataRelease(String dataReleaseId) {
        return dataReleaseId == null ? Optional.empty() : Optional.ofNullable(releases.get(dataReleaseId.trim()));
    }

    @Override
    public Optional<RulesetBinding> findRuleset(String rulesetId) {
        return rulesetId == null ? Optional.empty() : Optional.ofNullable(rulesets.get(rulesetId.trim()));
    }

    /** 注入 fixture 的规则可用性；空集合表示未提供该维度，保持 local 兼容。 */
    public void setAvailability(Collection<PokemonAvailability> values) {
        availability.clear();
        availabilityConfigured = values != null && !values.isEmpty();
        if (values != null) values.forEach(value -> availability.put(
                availabilityKey(value.formId(), value.dataReleaseId(), value.rulesetId()), value.available()));
    }

    @Override
    public boolean isAvailable(long formId, String dataReleaseId, String rulesetId) {
        if (!availabilityConfigured) return true;
        return Boolean.TRUE.equals(availability.get(availabilityKey(formId, dataReleaseId, rulesetId)));
    }

    private static String key(long formId, String releaseId) {
        return formId + "@" + releaseId;
    }

    private static String availabilityKey(long formId, String releaseId, String rulesetId) {
        return formId + "@" + releaseId + "@" + rulesetId;
    }
}
