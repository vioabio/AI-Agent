package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.domain.*;
import com.vio.vioaiagent.pokemon.repository.InMemoryPokemonFactRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Repository;
import java.nio.file.Path;
import java.util.*;

/** Local reference data is explicitly distinct from reviewed, version-frozen competition facts. */
@Repository @Primary @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.pokemon.reference.enabled", havingValue="true")
public class LocalPokeApiRepository extends InMemoryPokemonFactRepository {
    public static final String RELEASE = "pokeapi-reference-v1";
    private final PokeApiClient client;
    private final ObjectMapper mapper;
    private final Map<Long, Detail> loaded = new java.util.concurrent.ConcurrentHashMap<>();
    private Map<String, String> chineseNames;
    @org.springframework.beans.factory.annotation.Autowired
    public LocalPokeApiRepository(ObjectMapper mapper,
            @Value("${vio.pokemon.reference.path:${user.dir}/.local-data/pokeapi}") String directory,
            @Value("${vio.pokemon.reference.proxy:}") String proxy) {
        this(mapper, PokeApiClient.configured(mapper, Path.of(directory), proxy));
    }
    public LocalPokeApiRepository(ObjectMapper mapper, PokeApiClient client) { this.mapper = mapper; this.client = client; }

    public synchronized Detail detail(String query, String release) {
        requireRelease(release);
        String key = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9][a-z0-9-]{0,79}")) {
            if (key.isBlank() || key.length() > 80) throw new IllegalArgumentException("请输入名称或全国编号");
            if (chineseNames == null) {
                chineseNames = new HashMap<>();
                // Names have no commas; split only the first three columns (the genus may contain commas).
                for (String line : client.names().payload().split("\\R")) {
                    String[] columns = line.split(",", 4);
                    if (columns.length >= 3 && (columns[1].equals("12") || columns[1].equals("4")))
                        chineseNames.put(columns[2].replace("\"", ""), columns[0]);
                }
            }
            key = chineseNames.get(key);
            if (key == null) throw new IllegalArgumentException("未识别该中文名称，请尝试英文名称或全国编号");
        }
        PokeApiClient.Snapshot raw = client.read("pokemon", key);
        JsonNode pokemon = parse(raw.payload());
        long id = pokemon.path("id").asLong();
        String slug = pokemon.path("name").asText();
        if (key.matches("[0-9]+") ? id != Long.parseLong(key) : !slug.equals(key)) throw new IllegalStateException("PokéAPI 资源身份不匹配");
        String speciesKey = pokemon.path("species").path("name").asText();
        var speciesRaw = client.read("pokemon-species", speciesKey);
        JsonNode species = parse(speciesRaw.payload());
        if (!speciesKey.equals(species.path("name").asText())) throw new IllegalStateException("物种映射不一致");
        if (!Set.of("generation-i", "generation-ii", "generation-iii", "generation-iv", "generation-v", "generation-vi", "generation-vii", "generation-viii", "generation-ix")
                .contains(species.path("generation").path("name").asText())) throw new IllegalArgumentException("当前参考模式仅支持第九世代及以前物种");
        String name = slug;
        for (JsonNode n : species.path("names")) if (n.path("language").path("name").asText().equalsIgnoreCase("zh-Hans")) name = n.path("name").asText();
        Map<String,Integer> stats = new HashMap<>();
        for (JsonNode stat : pokemon.path("stats")) stats.put(stat.path("stat").path("name").asText(), stat.path("base_stat").asInt(-1));
        List<String> types = new ArrayList<>();
        for (JsonNode type : pokemon.path("types")) types.add(type.path("type").path("name").asText());
        if (types.isEmpty() || types.size() > 2 || stats.size() != 6) throw new IllegalStateException("资料属性或种族值不完整");
        String checksum = PokeApiClient.digest(raw.sha256() + speciesRaw.sha256());
        PokemonFact fact = new PokemonFact(id, species.path("id").asLong(), species.path("id").asInt(), slug, name,
                slug, name, RELEASE, "pokemon-scarlet-violet", raw.url(), checksum, "LOCAL_REFERENCE",
                typeZh(types.get(0)), types.size() == 2 ? typeZh(types.get(1)) : null,
                stats.get("hp"), stats.get("attack"), stats.get("defense"), stats.get("special-attack"), stats.get("special-defense"), stats.get("speed"));
        List<String> abilities = new ArrayList<>(), moves = new ArrayList<>();
        for (JsonNode a : pokemon.path("abilities")) abilities.add(a.path("ability").path("name").asText());
        for (JsonNode move : pokemon.path("moves")) {
            for (JsonNode version : move.path("version_group_details")) {
                if (version.path("version_group").path("name").asText().equals("scarlet-violet")) { moves.add(move.path("move").path("name").asText()); break; }
            }
        }
        Detail result = new Detail(fact, List.copyOf(abilities), List.copyOf(moves), raw.retrievedAt().toString(),
                "PokéAPI 当前资料的本地参考快照；不是历史补丁或赛制合法性证明，不包含实时强度排名。");
        loaded.put(id, result);
        return result;
    }
    public Map<String,Object> resource(String kind, String key, String release) {
        requireRelease(release);
        if (!Set.of("move", "ability", "item", "nature").contains(kind)) throw new IllegalArgumentException("不支持的资料类别");
        var raw = client.read(kind, key);
        JsonNode data = parse(raw.payload());
        if (!key.equals(data.path("name").asText())) throw new IllegalStateException("资料名称不一致");
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("kind", kind); result.put("slug", key); result.put("dataReleaseId", RELEASE);
        result.put("sourceUrl", raw.url()); result.put("checksum", raw.sha256()); result.put("retrievedAt", raw.retrievedAt().toString());
        for (String field : List.of("names", "power", "accuracy", "pp", "type", "damage_class", "effect_entries", "flavor_text_entries"))
            if (data.has(field)) {
                JsonNode value=data.get(field);
                if(value.isArray()) {
                    var bounded=mapper.createArrayNode();
                    for(JsonNode entry:value) {
                        String language=entry.path("language").path("name").asText();
                        if(language.isEmpty()||Set.of("en","zh-hans","zh-hant").contains(language.toLowerCase(Locale.ROOT)))bounded.add(entry);
                        if(bounded.size()>=12)break;
                    }
                    value=bounded;
                }
                result.put(field,value);
            }
        return result;
    }
    private void requireRelease(String release) { if (!RELEASE.equals(release)) throw new IllegalArgumentException("真实参考资料必须选择数据版本 " + RELEASE + "，不能混入已冻结资料版本"); }
    private JsonNode parse(String raw) { try { return mapper.readTree(raw); } catch (Exception e) { throw new IllegalStateException("资料 JSON 无法解析", e); } }
    @Override public List<PokemonFact> search(String release, String slug, Integer number, int limit) {
        if (!RELEASE.equals(release)) return super.search(release, slug, number, limit);
        if ((slug == null || slug.isBlank()) && number == null) return loaded.values().stream().map(Detail::fact).sorted(Comparator.comparingLong(PokemonFact::formId)).limit(Math.max(1,limit)).toList();
        PokemonFact value = detail(slug == null || slug.isBlank() ? number.toString() : slug, release).fact();
        return number == null || value.nationalNo() == number ? List.of(value) : List.of();
    }
    @Override public Optional<PokemonFact> findByFormId(long id, String release) {
        if (!RELEASE.equals(release)) return super.findByFormId(id,release);
        try { return Optional.of(detail(Long.toString(id), release).fact()); }
        catch(com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException e){return Optional.empty();}
    }
    @Override public Optional<VersionedDataRelease> findDataRelease(String release) {
        return RELEASE.equals(release) ? Optional.of(new VersionedDataRelease(RELEASE,"pokemon-scarlet-violet","https://pokeapi.co/", "reference-schema-v1", "LOCAL_REFERENCE")) : super.findDataRelease(release);
    }
    @Override public boolean isQueryableRelease(VersionedDataRelease release) {
        return RELEASE.equals(release.id()) && "LOCAL_REFERENCE".equals(release.status()) || super.isQueryableRelease(release);
    }
    @Override public Optional<RulesetBinding> findRuleset(String id) {
        return "casual-v1".equals(id) ? Optional.of(new RulesetBinding(id,"pokemon-scarlet-violet","本地休闲参考（不作排位合法性保证）",true)) : super.findRuleset(id);
    }
    public record Detail(PokemonFact fact, List<String> abilities, List<String> moves, String retrievedAt, String limitation) { }
    private String typeZh(String type) {
        String[] keys={"normal","fire","water","electric","grass","ice","fighting","poison","ground","flying","psychic","bug","rock","ghost","dragon","dark","steel","fairy"};
        String[] values={"一般","火","水","电","草","冰","格斗","毒","地面","飞行","超能力","虫","岩石","幽灵","龙","恶","钢","妖精"};
        for(int i=0;i<keys.length;i++) if(keys[i].equals(type)) return values[i];
        throw new IllegalArgumentException("未知属性："+type);
    }
}
