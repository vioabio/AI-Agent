package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import java.util.function.Function;

/** Fixed-origin GET adapter with validated, atomic disk caching; never follows upstream URLs. */
public class PokeApiClient {
    private final ObjectMapper mapper;
    private final Path directory;
    private final Function<String, String> transport;
    private final boolean preferBundledNames;
    public PokeApiClient(ObjectMapper mapper, Path directory) {
        this(mapper, directory, null);
    }
    public PokeApiClient(ObjectMapper mapper, Path directory, Function<String, String> transport) {
        this(mapper, directory, transport, "");
    }
    public static PokeApiClient configured(ObjectMapper mapper, Path directory, String proxy) {
        return new PokeApiClient(mapper, directory, null, proxy);
    }
    private PokeApiClient(ObjectMapper mapper, Path directory, Function<String, String> transport, String proxy) {
        this.mapper = mapper;
        this.directory = directory.toAbsolutePath().normalize();
        this.preferBundledNames = transport == null;
        if (transport != null) this.transport = transport;
        else {
            HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                    .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER);
            ProxySelector selector = configuredProxy(this.directory, proxy);
            if (selector != null) builder.proxy(selector);
            HttpClient http = builder.build();
            this.transport = url -> fetch(http,url,8000);
        }
    }
    static ProxySelector configuredProxy(Path directory, String explicitProxy) {
        String proxy = explicitProxy == null ? "" : explicitProxy.trim();
        if (proxy.isEmpty()) {
            // Separate local network preferences from cached upstream payloads and secret model settings.
            Path preferences = directory.resolveSibling("pokeapi-network.properties");
            if (Files.exists(preferences)) {
                var properties = new java.util.Properties();
                try (var input = Files.newInputStream(preferences)) { properties.load(input); }
                catch (java.io.IOException e) { throw new IllegalStateException("本地资料网络设置无法读取", e); }
                proxy = properties.getProperty("proxy", "").trim();
            }
        }
        if (proxy.isEmpty()) return null; // Retain the JVM's configured ProxySelector.
        if (proxy.equalsIgnoreCase("direct")) return new ProxySelector() {
            public java.util.List<java.net.Proxy> select(URI uri) { return java.util.List.of(java.net.Proxy.NO_PROXY); }
            public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException error) { }
        };
        try {
            URI uri = URI.create(proxy);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1
                    || uri.getPort() > 65535 || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
            return ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("资料代理必须为 http://主机:端口，不支持凭据、路径或查询参数");
        }
    }
    static String fetch(HttpClient http,String url,long deadlineMs) {
            var pending = http.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "VioPokemonLocalReference/1.0").GET().build(), HttpResponse.BodyHandlers.ofString());
            try {
                // The deadline covers the entire response body, not only connection/headers.
                var response = pending.get(deadlineMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (response.statusCode() == 404) throw new com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException("PokéAPI 中没有该资料，请检查名称或编号");
                if (response.statusCode() != 200) throw new PokeApiUnavailableException("PokéAPI 暂时无法提供资料，请稍后重试", null);
                return response.body();
            } catch (InterruptedException e) {
                pending.cancel(true);
                Thread.currentThread().interrupt(); throw new IllegalStateException("资料请求已取消", e);
            } catch (java.util.concurrent.TimeoutException e) {
                pending.cancel(true); throw new PokeApiUnavailableException("PokéAPI 正文读取超时，请检查资料代理设置或使用已缓存资料", e);
            } catch (java.util.concurrent.ExecutionException e) { throw new PokeApiUnavailableException("PokéAPI 暂不可达，请检查资料代理设置或使用已缓存资料", e.getCause()); }
    }
    public synchronized Snapshot read(String kind, String key) {
        if (!java.util.Set.of("pokemon", "pokemon-species", "move", "ability", "item", "nature").contains(kind)
                || key == null || !key.matches("[a-z0-9][a-z0-9-]{0,79}")) throw new IllegalArgumentException("无效资料查询");
        String url = "https://pokeapi.co/api/v2/" + kind + "/" + key + "/";
        Snapshot result=cached(kind + "-" + key, url);
        if(kind.equals("pokemon")) {
            try {
                JsonNode data=mapper.readTree(result.payload());
                if(!matchesPokemonKey(key,data))throw new IllegalStateException("PokéAPI 资源身份不匹配");
                // ID and name share the original response and original source URL, so reopening a
                // saved team offline never needs a second download of the same Pokemon by ID.
                for(String alias:java.util.List.of(data.path("id").asText(),data.path("name").asText())) {
                    if(!alias.matches("[a-z0-9][a-z0-9-]{0,79}"))throw new IllegalStateException("资料别名非法");
                    Path target=directory.resolve("pokemon-"+alias+".json");
                    if(!Files.exists(target)) {
                        Path aliasTemp=Files.createTempFile(directory,"alias-",".tmp");
                        try {
                            Files.copy(directory.resolve(kind+"-"+key+".json"),aliasTemp,StandardCopyOption.REPLACE_EXISTING);
                            try{Files.move(aliasTemp,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(aliasTemp,target);}
                        }catch(FileAlreadyExistsException ignored){}finally{Files.deleteIfExists(aliasTemp);}
                    }
                }
            }catch(java.io.IOException e){throw new IllegalStateException("本地资料别名缓存失败",e);}
        }
        return result;
    }
    public synchronized Snapshot names() {
        if(preferBundledNames) {
            try(var names=PokeApiClient.class.getResourceAsStream("/pokemon-reference/species-names.csv");
                var metadata=PokeApiClient.class.getResourceAsStream("/pokemon-reference/species-names.meta.json")) {
                if(names!=null&&metadata!=null) {
                    String payload=new String(names.readNBytes(2_000_001),StandardCharsets.UTF_8);
                    JsonNode meta=mapper.readTree(metadata.readNBytes(65536));
                    String source="https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/pokemon_species_names.csv";
                    if(!source.equals(meta.path("sourceUrl").asText())||!digest(payload).equals(meta.path("sha256").asText()))throw new IllegalStateException("随包中文名称表校验失败");
                    return new Snapshot(payload,digest(payload),Instant.parse(meta.path("retrievedAt").asText()),source);
                }
            }catch(java.io.IOException e){throw new IllegalStateException("本地中文名称表无法读取",e);}
        }
        return cached("species-names", "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/pokemon_species_names.csv");
    }
    private Snapshot cached(String key, String url) {
        Path file = directory.resolve(key + ".json");
        try {
            Files.createDirectories(directory);
            if (Files.exists(file)) {
                JsonNode saved = mapper.readTree(Files.readString(file));
                String payload = saved.path("payload").asText();
                boolean matchingSource=url.equals(saved.path("url").asText());
                if(!matchingSource && key.startsWith("pokemon-")&&!key.startsWith("pokemon-species-")
                        && saved.path("url").asText().matches("https://pokeapi\\.co/api/v2/pokemon/[a-z0-9-]+/")) {
                    String originalKey=saved.path("url").asText().substring("https://pokeapi.co/api/v2/pokemon/".length()).replace("/", "");
                    matchingSource=matchesPokemonKey(key.substring("pokemon-".length()),mapper.readTree(payload)) && matchesPokemonKey(originalKey,mapper.readTree(payload));
                }
                if (saved.path("schemaVersion").asInt() != 1 || !matchingSource
                        || !digest(payload).equals(saved.path("sha256").asText())) {
                    throw new IllegalStateException("本地资料缓存校验失败，请删除损坏的单个缓存文件后重试：" + key);
                }
                return new Snapshot(payload, saved.path("sha256").asText(), Instant.parse(saved.path("retrievedAt").asText()), saved.path("url").asText());
            }
            String payload = transport.apply(url);
            if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > 2_000_000) throw new IllegalStateException("资料响应为空或超过大小限制");
            if (!key.equals("species-names")) {
                JsonNode parsed = mapper.readTree(payload);
                if (!parsed.isObject() || parsed.path("id").asLong() <= 0 || parsed.path("name").asText().isBlank()) {
                    throw new IllegalStateException("资料响应缺少资源身份");
                }
            }
            Snapshot result = new Snapshot(payload, digest(payload), Instant.now(), url);
            Path temporary = Files.createTempFile(directory, "snapshot-", ".tmp");
            try {
                var node = mapper.createObjectNode().put("schemaVersion", 1).put("url", url)
                        .put("payload", payload).put("sha256", result.sha256()).put("retrievedAt", result.retrievedAt().toString());
                Files.writeString(temporary, mapper.writeValueAsString(node));
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
            return result;
        } catch (java.io.IOException e) { throw new IllegalStateException("本地资料缓存读写失败", e); }
    }
    public static String digest(String value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private boolean matchesPokemonKey(String key,JsonNode data) {
        return key.matches("[0-9]+") ? key.replaceFirst("^0+(?!$)", "").equals(data.path("id").asText()) : key.equals(data.path("name").asText());
    }
    public record Snapshot(String payload, String sha256, Instant retrievedAt, String url) { }
}
