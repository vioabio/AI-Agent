package com.vio.vioaiagent.rag.ingestion;

import java.util.regex.Pattern;

/**
 * 摄取错误写入 attempt/DLQ/outbox 前的统一脱敏器。
 *
 * <p>错误文本来自 parser、HTTP client 和数据库驱动，不能假设它不会携带
 * query token、Bearer 值或 URL userinfo。该类只保留短的诊断摘要，不承担
 * 记录完整异常堆栈的职责。</p>
 */
final class KnowledgeIngestionErrorSanitizer {

    private static final int MAX_LENGTH = 1000;
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)((?:password|passwd|secret|token|api[-_ ]?key|access[-_ ]?key|authorization)"
                    + "[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\\"'\\s,;}&]+)");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(\\bBearer\\s+)([^\\s,;]+)");
    private static final Pattern URL_USERINFO = Pattern.compile(
            "(?i)(https?://)([^/@\\s]+):([^/@\\s]+)@");

    private KnowledgeIngestionErrorSanitizer() {
    }

    static String sanitize(String value) {
        if (value == null || value.isBlank()) return "未提供错误详情";
        String normalized = value.replaceAll("[\\r\\n\\t]", " ");
        normalized = BEARER.matcher(normalized).replaceAll("$1[REDACTED]");
        normalized = SECRET_ASSIGNMENT.matcher(normalized).replaceAll("$1[REDACTED]");
        normalized = URL_USERINFO.matcher(normalized).replaceAll("$1[REDACTED]@");
        return normalized.length() > MAX_LENGTH ? normalized.substring(0, MAX_LENGTH) : normalized;
    }
}
