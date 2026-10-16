package com.vio.vioaiagent.pokemon.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.domain.PokemonAvailability;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.domain.RulesetBinding;
import com.vio.vioaiagent.pokemon.domain.VersionedDataRelease;
import com.vio.vioaiagent.pokemon.repository.InMemoryPokemonFactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 可选的本地 fixture 加载器。只有显式设置
 * {@code VIO_POKEMON_FIXTURE_ENABLED=true}（或对应 Spring 属性）且非 prod
 * profile 才会执行；不会把 candidate 数据注入生产 JDBC，也不会自动将其
 * 标记为 active。
 */
@Configuration
@Profile("!prod & !demo")
@ConditionalOnProperty(prefix = "vio.pokemon.fixture", name = "enabled", havingValue = "true")
public class PokemonFixtureInitializer {
    private static final Logger log = LoggerFactory.getLogger(PokemonFixtureInitializer.class);

    private final InMemoryPokemonFactRepository repository;
    private final ObjectMapper mapper;
    private final ResourceLoader resourceLoader;
    private final String location;

    public PokemonFixtureInitializer(InMemoryPokemonFactRepository repository,
                                     ObjectMapper mapper,
                                     ResourceLoader resourceLoader,
                                     @Value("${vio.pokemon.fixture.location:file:data/v1.1-fixture/fixture.json}") String location) {
        this.repository = repository;
        this.mapper = mapper.copy().findAndRegisterModules();
        this.resourceLoader = resourceLoader;
        this.location = location;
    }

    @PostConstruct
    public void load() {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) throw new IllegalStateException("Pokemon fixture 不存在: " + location);
        try (InputStream input = resource.getInputStream()) {
            JsonNode root = mapper.readTree(input);
            List<VersionedDataRelease> releases = parseReleases(root);
            List<RulesetBinding> rulesets = parseRulesets(root);
            List<PokemonFact> facts = parseFacts(root, releases);
            repository.replaceFixture(facts, releases, rulesets);
            repository.setAvailability(parseAvailability(root));
            log.info("已加载本地 Pokemon fixture: location={}, facts={}, releases={}, rulesets={}, status=candidate-only-unless-reviewed",
                    location, facts.size(), releases.size(), rulesets.size());
        } catch (Exception ex) {
            throw new IllegalStateException("Pokemon fixture 解析失败: " + location, ex);
        }
    }

    private List<VersionedDataRelease> parseReleases(JsonNode root) {
        List<VersionedDataRelease> result = new ArrayList<>();
        for (JsonNode node : array(root, "dataReleases")) {
            result.add(new VersionedDataRelease(text(node, "id"), text(node, "gameReleaseId"),
                    text(node, "sourceUrl"), text(node, "checksum"), text(node, "status")));
        }
        return List.copyOf(result);
    }

    private List<RulesetBinding> parseRulesets(JsonNode root) {
        List<RulesetBinding> result = new ArrayList<>();
        for (JsonNode node : array(root, "rulesets")) {
            result.add(new RulesetBinding(text(node, "id"), text(node, "gameReleaseId"),
                    text(node, "name"), node.path("enabled").asBoolean(false)));
        }
        return List.copyOf(result);
    }

    private List<PokemonFact> parseFacts(JsonNode root, List<VersionedDataRelease> releases) {
        Map<Long, JsonNode> species = byLong(root, "species", "id");
        Map<Long, JsonNode> forms = byLong(root, "forms", "id");
        Map<String, VersionedDataRelease> releaseById = new HashMap<>();
        releases.forEach(value -> releaseById.put(value.id(), value));
        List<PokemonFact> result = new ArrayList<>();
        for (JsonNode node : array(root, "formFacts")) {
            long formId = node.path("formId").asLong(0);
            JsonNode form = forms.get(formId);
            if (form == null) throw new IllegalArgumentException("fixture form 不存在: " + formId);
            long speciesId = form.path("speciesId").asLong(0);
            JsonNode specie = species.get(speciesId);
            if (specie == null) throw new IllegalArgumentException("fixture species 不存在: " + speciesId);
            String releaseId = text(node, "dataReleaseId");
            VersionedDataRelease release = releaseById.get(releaseId);
            if (release == null) throw new IllegalArgumentException("fixture data release 不存在: " + releaseId);
            result.add(new PokemonFact(formId, speciesId, specie.path("nationalNo").asInt(0),
                    text(specie, "slug"), text(specie, "nameZh"), text(form, "formKey"), text(form, "nameZh"),
                    releaseId, release.gameReleaseId(), release.sourceUrl(), release.checksum(), release.status(),
                    text(node, "type1"), nullableText(node, "type2"), node.path("hp").asInt(-1),
                    node.path("attack").asInt(-1), node.path("defense").asInt(-1), node.path("spAttack").asInt(-1),
                    node.path("spDefense").asInt(-1), node.path("speed").asInt(-1)));
        }
        return List.copyOf(result);
    }

    private List<PokemonAvailability> parseAvailability(JsonNode root) {
        List<PokemonAvailability> result = new ArrayList<>();
        for (JsonNode node : array(root, "availability")) {
            result.add(new PokemonAvailability(node.path("formId").asLong(0), text(node, "dataReleaseId"),
                    text(node, "rulesetId"), node.path("available").asBoolean(false)));
        }
        return List.copyOf(result);
    }

    private Map<Long, JsonNode> byLong(JsonNode root, String field, String idField) {
        Map<Long, JsonNode> result = new HashMap<>();
        for (JsonNode node : array(root, field)) result.put(node.path(idField).asLong(0), node);
        return result;
    }

    private List<JsonNode> array(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isArray()) throw new IllegalArgumentException("fixture 字段不是数组: " + field);
        List<JsonNode> result = new ArrayList<>();
        value.forEach(result::add);
        return result;
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("fixture 缺少字段: " + field);
        return value.trim();
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text.trim();
    }
}
