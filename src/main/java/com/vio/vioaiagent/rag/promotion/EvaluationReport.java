package com.vio.vioaiagent.rag.promotion;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * N6a 评测报告的最小不可变投影。
 *
 * <p>报告本身通常由离线评测流水线产生；在线发布服务只读取这份投影，
 * 不在 promotion 阶段重新运行评测或根据几个浮点字段“猜测”是否达标。所有
 * 版本键和摘要都必须由 producer 明确提供，缺失时由 {@link ReleaseQualityGate}
 * fail-closed 拒绝发布。</p>
 */
public record EvaluationReport(
        String evaluationRunId,
        String reportHash,
        String suiteVersion,
        String modelVersion,
        String dataReleaseId,
        String knowledgeReleaseId,
        String indexReleaseId,
        int sampleSize,
        boolean passed,
        boolean immutable,
        Instant generatedAt,
        Map<String, Double> metrics,
        Map<String, String> thresholds) {

    public EvaluationReport {
        evaluationRunId = required(evaluationRunId, "evaluationRunId");
        reportHash = required(reportHash, "reportHash");
        suiteVersion = required(suiteVersion, "suiteVersion");
        modelVersion = required(modelVersion, "modelVersion");
        dataReleaseId = requiredRelease(dataReleaseId, "dataReleaseId");
        knowledgeReleaseId = requiredRelease(knowledgeReleaseId, "knowledgeReleaseId");
        indexReleaseId = requiredRelease(indexReleaseId, "indexReleaseId");
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("sampleSize 必须大于 0");
        }
        if (generatedAt == null) {
            throw new IllegalArgumentException("generatedAt 不能为空");
        }
        metrics = immutableMetrics(metrics);
        thresholds = immutableThresholds(thresholds);
    }

    /**
     * 便于离线 producer 构造完整报告的短构造器。仍然要求调用方显式提供
     * 版本键和通过/不可变标记；质量门禁还会检查 metrics/thresholds。
     */
    public EvaluationReport(String evaluationRunId, String reportHash, String suiteVersion,
                            String modelVersion, String dataReleaseId, String knowledgeReleaseId,
                            String indexReleaseId, int sampleSize, boolean passed, boolean immutable,
                            Instant generatedAt) {
        this(evaluationRunId, reportHash, suiteVersion, modelVersion, dataReleaseId,
                knowledgeReleaseId, indexReleaseId, sampleSize, passed, immutable, generatedAt,
                Map.of(), Map.of());
    }

    public boolean hasMetricEvidence() {
        return !metrics.isEmpty() && metrics.values().stream().allMatch(value ->
                value != null && !value.isNaN() && !value.isInfinite());
    }

    public boolean hasThresholdEvidence() {
        return !thresholds.isEmpty() && thresholds.entrySet().stream().allMatch(entry ->
                entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null && !entry.getValue().isBlank());
    }

    public boolean hasUsableEvidence() {
        return passed && immutable && hasMetricEvidence() && hasThresholdEvidence()
                && meetsThresholds();
    }

    /**
     * 对常见的确定性阈值表达式执行程序断言，避免只相信 producer 的
     * {@code passed=true}。支持 {@code >=, >, <=, <, ==}；未知表达式直接失败。
     */
    public boolean meetsThresholds() {
        if (!hasThresholdEvidence() || !hasMetricEvidence()) return false;
        for (Map.Entry<String, String> entry : thresholds.entrySet()) {
            Double actual = metrics.get(entry.getKey());
            if (actual == null) return false;
            Matcher matcher = THRESHOLD.matcher(entry.getValue().trim());
            if (!matcher.matches()) return false;
            double expected;
            try {
                expected = Double.parseDouble(matcher.group(2));
            } catch (NumberFormatException ex) {
                return false;
            }
            String operator = matcher.group(1);
            boolean ok = switch (operator) {
                case ">=" -> actual >= expected;
                case ">" -> actual > expected;
                case "<=" -> actual <= expected;
                case "<" -> actual < expected;
                case "==" -> Double.compare(actual, expected) == 0;
                default -> false;
            };
            if (!ok) return false;
        }
        return true;
    }

    private static final Pattern THRESHOLD = Pattern.compile("(>=|<=|==|>|<)\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)");

    private static Map<String, Double> immutableMetrics(Map<String, Double> value) {
        if (value == null) return Map.of();
        Map<String, Double> copy = new LinkedHashMap<>();
        value.forEach((key, number) -> {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("metric key 不能为空");
            if (number == null || number.isNaN() || number.isInfinite()) {
                throw new IllegalArgumentException("metric 值必须是有限数字: " + key);
            }
            copy.put(key.trim(), number);
        });
        return Map.copyOf(copy);
    }

    private static Map<String, String> immutableThresholds(Map<String, String> value) {
        if (value == null) return Map.of();
        Map<String, String> copy = new LinkedHashMap<>();
        value.forEach((key, threshold) -> {
            if (key == null || key.isBlank() || threshold == null || threshold.isBlank()) {
                throw new IllegalArgumentException("threshold key/value 不能为空");
            }
            copy.put(key.trim(), threshold.trim());
        });
        return Map.copyOf(copy);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || isPlaceholder(value)) {
            throw new IllegalArgumentException(name + " 不能为空或占位值");
        }
        return value.trim();
    }

    private static String requiredRelease(String value, String name) {
        return required(value, name);
    }

    static boolean isPlaceholder(String value) {
        if (value == null) return true;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.isEmpty() || normalized.equals("unknown")
                || normalized.equals("untraceable") || normalized.equals("n/a")
                || normalized.equals("null");
    }
}
