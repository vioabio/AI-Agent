package com.vio.vioaiagent.rag.promotion;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the original UTF-8 metrics.json produced by run-v1.1-eval into the
 * online promotion projection. The hash identifies the exact original bytes;
 * thresholds and minimum denominators come from packaged server rules.
 * Source hashes are declarations: this decoder does not possess dataset bytes
 * and cannot attest their contents or the truth of the evaluator's observations.
 */
public final class EvaluationReportArtifactDecoder {
    public static final int MAX_ARTIFACT_BYTES = 1024 * 1024;
    private static final Pattern HASH = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern FILE_HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ABSOLUTE_THRESHOLD = Pattern.compile(
            "([<>]=?)\\s*(-?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*(?:ms)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELATIVE_THRESHOLD = Pattern.compile(
            "baseline\\s*\\+\\s*(\\d+(?:\\.\\d+)?)%", Pattern.CASE_INSENSITIVE);
    // The frozen v1.1 dictionary has no unit/range field. These N6a formulas
    // divide successful/correct observations by eligible/total observations;
    // their domain is [0, 1], independently of the configured alert boundary.
    private static final Set<String> RATIO_METRICS = Set.of(
            "fact_task_success_rate", "evidence_task_success_rate", "rules_task_success_rate",
            "no_answer_handling_rate", "deterministic_calculation_accuracy", "rag_recall_at_5",
            "citation_correctness", "groundedness", "tool_selection_parameter_accuracy");

    private final ObjectMapper mapper;
    private final Map<String, JsonNode> suites;
    private final Map<String, JsonNode> metrics;

    public EvaluationReportArtifactDecoder(ObjectMapper mapper) {
        this.mapper = strictMapper(Objects.requireNonNull(mapper, "mapper 不能为空"));
        JsonNode manifest = resource("/evaluation/v1.1/manifest.json");
        JsonNode dictionary = resource("/evaluation/v1.1/metric-dictionary.json");
        equal(manifest, "schemaVersion", "v1.1");
        equal(manifest, "suiteVersion", "v1.1");
        equal(dictionary, "schemaVersion", "v1.1");
        equal(dictionary, "dictionaryVersion", "v1.1");
        this.suites = indexed(manifest, "suites", "suiteId");
        this.metrics = indexed(dictionary, "metrics", "metricId");
        if (suites.size() != 7 || metrics.isEmpty()) throw invalid("服务端评测规则不完整");
        suites.values().forEach(rule -> {
            text(rule, "kind");
            text(rule, "file");
            if (integer(rule, "minimumCases") <= 0) throw invalid("服务端 minimumCases 无效");
        });
        metrics.values().forEach(rule -> {
            text(rule, "thresholdVersion");
            text(rule, "alertThreshold");
            if (integer(rule, "minRequests") <= 0) throw invalid("服务端 minRequests 无效");
        });
    }

    public EvaluationReport decode(byte[] artifact, String expectedHash) {
        return decode(artifact, expectedHash, null);
    }

    /** Relative budgets must refer to a separately persisted immutable report. */
    public EvaluationReport decode(byte[] artifact, String expectedHash, EvaluationReport trustedBaseline) {
        if (expectedHash == null || !HASH.matcher(expectedHash).matches()) {
            throw invalid("expectedHash 必须为 sha256:64 位小写十六进制");
        }
        if (!hash(artifact).equals(expectedHash)) throw invalid("评测原文 SHA-256 不一致");
        JsonNode root = parse(mapper, artifact);
        equal(root, "schemaVersion", "v1.1");
        equal(root, "suiteVersion", "v1.1");
        equal(root, "producer", "run-v1.1-eval");
        if (!root.path("redacted").isBoolean() || !root.path("redacted").booleanValue()) {
            throw invalid("评测原文必须声明 redacted=true");
        }
        String runId = metadata(root, "evaluationRunId");
        if (!runId.matches("[A-Za-z0-9._-]{3,128}")) throw invalid("evaluationRunId 格式无效");
        String model = metadata(root, "modelVersion");
        metadata(root, "promptVersion");
        String commit = metadata(root, "codeCommit");
        if (!commit.matches("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})")) throw invalid("codeCommit 必须为完整提交摘要");
        String dataRelease = metadata(root, "dataReleaseId");
        String knowledgeRelease = metadata(root, "knowledgeReleaseId");
        String indexRelease = metadata(root, "indexReleaseId");
        metadata(root, "environment");
        metadata(root, "manifest");
        metadata(root, "dictionary");
        fileHash(root, "manifestSha256");
        fileHash(root, "dictionarySha256");
        Instant generatedAt;
        try { generatedAt = Instant.parse(text(root, "generatedAt")); }
        catch (DateTimeParseException ex) { throw invalid("generatedAt 无效"); }
        String status = status(root);
        boolean declaredReady = status.equals("ready");
        boolean noErrors = emptyErrors(root);
        boolean usable = declaredReady && noErrors;
        if (declaredReady && !usable) throw invalid("ready 报告仍包含 errors");

        Map<String, JsonNode> suppliedSuites = indexed(root, "suites", "suiteId");
        if (!suppliedSuites.keySet().equals(suites.keySet())) throw invalid("评测必须包含服务端完整七类 suite");
        long samples = 0;
        long datasetCases = 0;
        for (Map.Entry<String, JsonNode> entry : suites.entrySet()) {
            JsonNode rule = entry.getValue();
            JsonNode suite = suppliedSuites.get(entry.getKey());
            equal(suite, "kind", text(rule, "kind"));
            equal(suite, "datasetFile", text(rule, "file"));
            fileHash(suite, "datasetSha256");
            int sampleCount = integer(suite, "sampleCount");
            int datasetCount = integer(suite, "datasetCaseCount");
            int passedCount = integer(suite, "passedCount");
            if (sampleCount > datasetCount || passedCount > sampleCount) throw invalid("suite 样本计数矛盾");
            samples += sampleCount;
            datasetCases += datasetCount;
            String suiteStatus = status(suite);
            boolean suiteErrorsEmpty = emptyErrors(suite);
            boolean suiteReady = suiteStatus.equals("ready") && suiteErrorsEmpty
                    && sampleCount == datasetCount && sampleCount >= integer(rule, "minimumCases");
            if ((suiteStatus.equals("ready") && !suiteReady) || (declaredReady && !suiteReady)) {
                throw invalid("ready suite 不满足服务端样本或错误约束: " + entry.getKey());
            }
            usable &= suiteReady;
        }
        int sampleSize = integer(root, "sampleCount");
        if (sampleSize <= 0 || samples != sampleSize || datasetCases != integer(root, "datasetCaseCount")) {
            throw invalid("aggregate 样本计数与 suite 不一致或没有观测样本");
        }
        JsonNode securitySuite = suppliedSuites.get("security-redteam-v1");
        int securitySamples = integer(securitySuite, "sampleCount");
        Integer securityFailures = root.hasNonNull("securityRedTeamFailures")
                ? integer(root, "securityRedTeamFailures") : null;
        if (securityFailures == null) {
            // The real runner emits null before it observes any security case.
            // Preserve unknown evidence instead of manufacturing a zero/pass.
            if (!root.has("securityRedTeamFailures") || declaredReady || securitySamples != 0) {
                throw invalid("存在安全样本或 ready 声明时必须给出安全失败数");
            }
            usable = false;
        } else {
            int failedCases = securitySamples - integer(securitySuite, "passedCount");
            if (securityFailures > securitySamples || securityFailures < failedCases) {
                throw invalid("安全失败数与安全样本/通过计数矛盾");
            }
            if (declaredReady && securityFailures != 0) throw invalid("ready 报告仍有安全失败");
            usable &= securityFailures == 0;
        }

        String baselineId = optionalText(root, "baselineEvaluationRunId");
        String baselineHash = optionalText(root, "baselineReportHash");
        if ((baselineId == null) != (baselineHash == null)) throw invalid("baseline 引用必须同时提供 ID 和 hash");
        if (baselineId != null) {
            if (baselineId.equals(runId)) throw invalid("评测不能引用自身作为 baseline");
            if (!HASH.matcher(baselineHash).matches()) throw invalid("baselineReportHash 格式无效");
        }
        boolean baselineVerified = trustedBaseline != null && baselineId != null
                && trustedBaseline.immutable() && baselineId.equals(trustedBaseline.evaluationRunId())
                && baselineHash.equals(trustedBaseline.reportHash())
                && model.equals(trustedBaseline.modelVersion())
                && "v1.1".equals(trustedBaseline.suiteVersion());
        if (trustedBaseline != null && !baselineVerified) throw invalid("baseline 内容、版本或摘要不一致");

        Map<String, JsonNode> suppliedMetrics = indexed(root, "results", "metricId");
        if (!suppliedMetrics.keySet().equals(metrics.keySet())) throw invalid("评测指标集合与服务端字典不一致");
        Map<String, Double> values = new LinkedHashMap<>();
        Map<String, String> thresholds = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : metrics.entrySet()) {
            String id = entry.getKey();
            JsonNode rule = entry.getValue();
            JsonNode metric = suppliedMetrics.get(id);
            String alert = text(rule, "alertThreshold");
            equal(metric, "threshold", alert);
            equal(metric, "thresholdVersion", text(rule, "thresholdVersion"));
            String phase = rule.path("evaluationPhase").asText("n6a");
            equal(metric, "evaluationPhase", phase);
            boolean blocking = !rule.has("blocking") || rule.path("blocking").booleanValue();
            if (!metric.path("blocking").isBoolean() || metric.path("blocking").booleanValue() != blocking) {
                throw invalid("metric blocking 声明与服务端字典不一致");
            }
            metadata(metric, "source");
            int metricSamples = integer(metric, "sampleCount");
            String metricStatus = text(metric, "status");
            if (phase.equals("n6b")) {
                if (!metricStatus.equals("not_applicable")) throw invalid("N6b 指标不能作为 N6a 发布证据");
                continue;
            }
            if (!metricStatus.equals("ready") && !metricStatus.equals("pending") && !metricStatus.equals("blocked")) {
                throw invalid("metric status 无效");
            }
            Double value = optionalNumber(metric, "value");
            if (value != null && RATIO_METRICS.contains(id) && value > 1) {
                throw invalid("比率指标必须在 [0, 1] 范围内: " + id);
            }
            boolean enough = value != null && metricSamples >= integer(rule, "minRequests");
            if ((metricStatus.equals("ready") || declaredReady) && !enough) {
                throw invalid("ready metric 数值或样本不足: " + id);
            }
            if (value != null) values.put(id, value);
            boolean metricUsable = metricStatus.equals("ready") && enough;
            String positiveThreshold = null;
            if (blocking && !alert.equals("observation_only")) {
                positiveThreshold = positiveThreshold(alert, trustedBaseline, baselineVerified, id);
                if (positiveThreshold == null) {
                    metricUsable = false; // no independent baseline: archive, never self-certify
                } else {
                    thresholds.put(id, positiveThreshold);
                    boolean satisfied = value != null && satisfies(value, positiveThreshold);
                    if (metricStatus.equals("ready") && !satisfied) throw invalid("ready metric 未达到服务端阈值: " + id);
                    metricUsable &= satisfied;
                }
            }
            if (declaredReady && !metricStatus.equals("ready")) throw invalid("ready 报告包含未完成 metric");
            usable &= metricUsable;
        }
        if (securityFailures != null) values.put("securityRedTeamFailures", securityFailures.doubleValue());
        thresholds.put("securityRedTeamFailures", "==0");
        return new EvaluationReport(runId, expectedHash, "v1.1", model, dataRelease,
                knowledgeRelease, indexRelease, sampleSize, usable, true, generatedAt, values, thresholds);
    }

    public static String hash(byte[] artifact) {
        checkSize(artifact);
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("JDK 不支持 SHA-256", ex); }
    }

    public static String baselineEvaluationRunId(byte[] artifact) {
        return optionalText(parse(strictMapper(new ObjectMapper()), artifact), "baselineEvaluationRunId");
    }

    private JsonNode resource(String name) {
        try (InputStream stream = EvaluationReportArtifactDecoder.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("缺少服务端冻结评测规则: " + name);
            return mapper.readTree(stream);
        } catch (IOException ex) { throw new IllegalStateException("无法读取服务端冻结评测规则", ex); }
    }

    private static ObjectMapper strictMapper(ObjectMapper source) {
        return source.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static JsonNode parse(ObjectMapper mapper, byte[] artifact) {
        checkSize(artifact);
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(artifact)).toString();
            JsonNode root = mapper.readTree(utf8);
            if (root == null || !root.isObject()) throw invalid("评测原文必须是 JSON 对象");
            return root;
        } catch (CharacterCodingException ex) { throw invalid("评测原文必须是合法 UTF-8"); }
        catch (IOException ex) { throw invalid("评测 JSON 无效、字段重复或包含尾随内容"); }
    }

    private static void checkSize(byte[] artifact) {
        if (artifact == null || artifact.length == 0 || artifact.length > MAX_ARTIFACT_BYTES) {
            throw invalid("评测原文必须为 1 字节至 1 MiB");
        }
    }

    private static Map<String, JsonNode> indexed(JsonNode root, String field, String key) {
        JsonNode array = root.path(field);
        if (!array.isArray()) throw invalid(field + " 必须是数组");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode item : array) {
            if (!item.isObject() || result.putIfAbsent(text(item, key), item) != null) {
                throw invalid(field + " 存在重复或无效键");
            }
        }
        return result;
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 2048) {
            throw invalid(field + " 必须是非空字符串");
        }
        return value.textValue().trim();
    }

    private static String optionalText(JsonNode root, String field) {
        return !root.hasNonNull(field) ? null : metadata(root, field);
    }

    private static String metadata(JsonNode root, String field) {
        String value = text(root, field);
        String normalized = value.toLowerCase(Locale.ROOT);
        if (EvaluationReport.isPlaceholder(value) || normalized.startsWith("set-")
                || normalized.contains("placeholder") || normalized.equals("todo") || normalized.equals("pending")) {
            throw invalid(field + " 不能是占位值");
        }
        return value;
    }

    private static void equal(JsonNode root, String field, String expected) {
        if (!expected.equals(text(root, field))) throw invalid(field + " 与服务端契约不一致");
    }

    private static void fileHash(JsonNode root, String field) {
        if (!FILE_HASH.matcher(text(root, field)).matches()) throw invalid(field + " 必须为 64 位小写 SHA-256");
    }

    private static int integer(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalid(field + " 必须为非负整数");
        }
        return value.intValue();
    }

    private static Double optionalNumber(JsonNode root, String field) {
        if (!root.hasNonNull(field)) return null;
        JsonNode number = root.path(field);
        double value = number.doubleValue();
        if (!number.isNumber() || !Double.isFinite(value) || value < 0) throw invalid(field + " 必须为非负有限数字");
        return value;
    }

    private static boolean emptyErrors(JsonNode root) {
        JsonNode errors = root.path("errors");
        if (!errors.isArray()) throw invalid("errors 必须是数组");
        for (JsonNode error : errors) if (!error.isTextual()) throw invalid("errors 必须包含字符串");
        return errors.isEmpty();
    }

    private static String status(JsonNode root) {
        String status = text(root, "status");
        if (!status.equals("ready") && !status.equals("pending") && !status.equals("blocked")) throw invalid("status 无效");
        return status;
    }

    private static String positiveThreshold(String alert, EvaluationReport baseline, boolean verified, String id) {
        Matcher absolute = ABSOLUTE_THRESHOLD.matcher(alert);
        if (absolute.matches()) {
            String positive = switch (absolute.group(1)) {
                case "<" -> ">=";
                case "<=" -> ">";
                case ">" -> "<=";
                case ">=" -> "<";
                default -> throw invalid("服务端阈值操作符无效");
            };
            return positive + absolute.group(2);
        }
        Matcher relative = RELATIVE_THRESHOLD.matcher(alert);
        if (!relative.matches()) throw invalid("服务端阈值表达式不支持");
        if (!verified) return null;
        Double value = baseline.metrics().get(id);
        if (value == null || !Double.isFinite(value) || value < 0) throw invalid("baseline 缺少预算观测: " + id);
        double bound = value * (1 + Double.parseDouble(relative.group(1)) / 100);
        if (!Double.isFinite(bound)) throw invalid("baseline 预算边界溢出");
        return "<=" + bound;
    }

    private static boolean satisfies(double value, String threshold) {
        if (threshold.startsWith(">=")) return value >= Double.parseDouble(threshold.substring(2));
        if (threshold.startsWith("<=")) return value <= Double.parseDouble(threshold.substring(2));
        if (threshold.startsWith(">")) return value > Double.parseDouble(threshold.substring(1));
        if (threshold.startsWith("<")) return value < Double.parseDouble(threshold.substring(1));
        throw invalid("通过阈值表达式无效");
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
