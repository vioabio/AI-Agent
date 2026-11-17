import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.guides.*;
import com.vio.vioaiagent.rag.retrieval.KnowledgeRetrievalQuery;
import com.vio.vioaiagent.tools.governance.*;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Runs the application's actual retrieval pipeline against the frozen public development queries. */
public class LiveGuideRerankBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing configuration " + name);
        return value;
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Path artifacts = Path.of("artifacts").toRealPath();
        if (!output.startsWith(artifacts) || Files.exists(output)) throw new IllegalArgumentException("Use a new artifacts directory");
        Files.createDirectory(output);
        byte[] datasetBytes = Files.readString(Path.of("evaluation/upgrade-v1.1/u6/stage5-retrieval-dev.json")).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        var dataset = JSON.readTree(datasetBytes);
        byte[] corpus = Files.readString(Path.of(dataset.path("corpusFile").asText())).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        if (!sha(corpus).equals(dataset.path("corpusSha256").asText())) throw new IllegalStateException("Corpus drift");
        Path vectors = Path.of(args[1]).toRealPath();
        if (!vectors.startsWith(artifacts)) throw new IllegalArgumentException("Use an isolated vector backup");
        var properties = new GuideSemanticProperties(env("VIO_GUIDE_EMBEDDING_ENDPOINT"), env("VIO_GUIDE_EMBEDDING_API_KEY"),
                env("VIO_GUIDE_EMBEDDING_MODEL"), Integer.parseInt(env("VIO_GUIDE_EMBEDDING_DIMENSIONS")),
                vectors.toString(), Integer.parseInt(env("VIO_GUIDE_EMBEDDING_TIMEOUT_SECONDS")), 0.55);
        var search = new LocalGuideSearch(JSON, new DefaultResourceLoader(), "classpath:pokemon-strategy/overview-v1.json", output.resolve("guides.sqlite").toString());
        var semantic = new LocalGuideSemanticSearch(properties);
        var rerank = new GuideRerankConfiguration().guideRerankPipeline(env("VIO_GUIDE_RERANK_ENDPOINT"), env("VIO_GUIDE_RERANK_MODEL"),
                env("VIO_GUIDE_RERANK_API_KEY"), Integer.parseInt(env("VIO_GUIDE_RERANK_TIMEOUT_MS")), 12, 5, 12000);
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "real-guide-rerank-comparison-v1");
        report.put("scope", "FROZEN_DEVELOPMENT_RETRIEVAL_NOT_INDEPENDENT_ANSWER_ACCEPTANCE");
        report.put("datasetSha256", sha(datasetBytes)); report.put("corpusSha256", sha(corpus));
        report.put("candidateK", 12); report.put("answerTopK", 5); report.put("rounds", 3);
        report.put("embeddingModel", properties.model()); report.put("rerankModel", env("VIO_GUIDE_RERANK_MODEL"));
        report.put("providerBillingUsage", null);
        Map<String,String> classes = new TreeMap<>();
        for (Class<?> type : List.of(LocalGuideSearch.class, LocalGuideSemanticSearch.class, GuideRerankPipeline.class, GuideRerankConfiguration.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                classes.put(type.getName(), sha(bytes.readAllBytes()));
            }
        }
        report.put("implementationSha256", classes);
        var records = new ArrayList<Map<String,Object>>(); report.put("records", records);
        // Alternate the paired modes by round to reduce systematic warm-up/order effects.
        for (int round = 0; round < 3; round++) {
            var modes = round % 2 == 0 ? List.of("BM25", "HYBRID", "HYBRID_RERANK") : List.of("HYBRID_RERANK", "HYBRID", "BM25");
            for (String mode : modes) {
                search.setSemantic(mode.equals("BM25") ? null : semantic);
                search.setReranking(mode.equals("HYBRID_RERANK") ? rerank : null);
                for (var item : dataset.path("cases")) {
                    var events = new ArrayList<ToolGovernanceEvent>();
                    var row = new LinkedHashMap<String,Object>();
                    row.put("round", round); row.put("mode", mode); row.put("caseId", item.path("id").asText());
                    row.put("query", item.path("query").asText());
                    ToolExecutionContextHolder.set(new ToolExecutionContext("benchmark", "benchmark-read-only", Set.of("trainer"), "benchmark", null, events::add));
                    long started = System.nanoTime();
                    try {
                        int limit = mode.equals("HYBRID_RERANK") ? 5 : 12;
                        var query = new KnowledgeRetrievalQuery(item.path("query").asText(), "zh-CN", "pokemon-scarlet-violet", "casual-singles", "casual-v1", limit);
                        var retrieved = search.retrieve(query);
                        row.put("retrievalMs", (System.nanoTime() - started) / 1_000_000d);
                        if (!mode.equals("HYBRID_RERANK")) row.put("candidateIds", retrieved.stream().map(hit -> hit.view().chunk().id()).toList());
                        var hits = retrieved.stream().limit(5).toList();
                        for (var hit : hits) {
                            var source = search.locate(hit.view().chunk().id()).orElseThrow();
                            if (!GuideChunks.validCitation(source.document(), source.citation()) || !source.citation().text().equals(hit.view().chunk().content()))
                                throw new IllegalStateException("Invalid canonical source");
                        }
                        row.put("hits", hits); row.put("sourcesVerified", true);
                        row.put("semantic", search.semanticStatus());
                        for (var event : events) if (event.attributes().containsKey("rerankObservation")) {
                            var observation = (GuideRerankPipeline.Diagnostics) event.attributes().get("rerankObservation");
                            row.put("rerankObservation", observation);
                            row.put("candidateIds", observation.ranks().stream().map(GuideRerankPipeline.Rank::chunkId).toList());
                        }
                    } finally { ToolExecutionContextHolder.clear(); }
                    records.add(row);
                    Files.writeString(output.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                    System.out.println(mode + " " + round + " " + item.path("id").asText());
                }
            }
        }
        report.put("status", "COLLECTED_PENDING_REVIEW");
        Files.writeString(output.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
