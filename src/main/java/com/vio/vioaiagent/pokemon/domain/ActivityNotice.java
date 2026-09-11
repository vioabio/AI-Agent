package com.vio.vioaiagent.pokemon.domain;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * 宝可梦活动资讯的不可变版本快照。
 *
 * <p>活动资讯与静态图鉴事实不同：它必须同时记录来源、抓取时间和生效
 * 窗口。调用方不能只根据标题或模型常识推断活动仍然有效；{@link
 * #isVisibleAt(Instant, Duration)} 是对外展示前的统一 fail-closed 门禁。</p>
 */
public record ActivityNotice(
        String id,
        String noticeKey,
        int versionNo,
        String title,
        String summary,
        String sourceId,
        String sourceUrl,
        String license,
        String trustLevel,
        String gameReleaseId,
        String dlcId,
        String language,
        String region,
        String eventType,
        Instant retrievedAt,
        Instant effectiveFrom,
        Instant effectiveTo,
        String freshness,
        String status,
        String checksum,
        String schemaVersion,
        String contentVersion,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static final String FRESH = "FRESH";
    public static final String STALE = "STALE";
    public static final String EXPIRED = "EXPIRED";
    public static final String NOT_YET_ACTIVE = "NOT_YET_ACTIVE";
    public static final String UNKNOWN = "UNKNOWN";

    public static final String STATUS_CANDIDATE = "CANDIDATE";
    public static final String STATUS_REVIEWED = "REVIEWED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ACTIVE = "ACTIVE";

    /**
     * 完整构造器。时间窗口使用半开区间 [effectiveFrom, effectiveTo)，
     * effectiveTo 为空表示持续有效但仍受 freshness/发布状态约束。
     */
    public ActivityNotice {
        id = required(id, "id", 128);
        noticeKey = required(noticeKey, "noticeKey", 128);
        if (versionNo <= 0) throw new IllegalArgumentException("versionNo 必须为正数");
        title = required(title, "title", 256);
        summary = summary == null ? "" : bounded(summary, "summary", 4000);
        sourceId = required(sourceId, "sourceId", 128);
        sourceUrl = normalizeHttpUrl(sourceUrl);
        license = required(license, "license", 128);
        trustLevel = required(trustLevel, "trustLevel", 64).toUpperCase(Locale.ROOT);
        gameReleaseId = required(gameReleaseId, "gameReleaseId", 64);
        dlcId = optional(dlcId, 128);
        language = required(language, "language", 16);
        region = optional(region, 64);
        eventType = required(eventType, "eventType", 64);
        retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt 不能为空");
        effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom 不能为空");
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo 必须晚于 effectiveFrom");
        }
        freshness = normalizeEnum(freshness, UNKNOWN);
        status = normalizeEnum(status, STATUS_CANDIDATE);
        checksum = required(checksum, "checksum", 128);
        schemaVersion = required(schemaVersion, "schemaVersion", 32);
        contentVersion = required(contentVersion, "contentVersion", 64);
        if (publishedAt != null && publishedAt.isBefore(retrievedAt)) {
            throw new IllegalArgumentException("publishedAt 不能早于 retrievedAt");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt 不能早于 createdAt");
        }
    }

    /** 常用的测试/fixture 构造器，仍要求完整来源与版本时效元数据。 */
    public ActivityNotice(String id, String noticeKey, int versionNo, String title, String summary,
                          String sourceId, String sourceUrl, String license, String trustLevel,
                          String gameReleaseId, String dlcId, String language, String region,
                          String eventType, Instant retrievedAt, Instant effectiveFrom,
                          Instant effectiveTo, String freshness, String status, String checksum,
                          String schemaVersion, String contentVersion, Instant publishedAt) {
        this(id, noticeKey, versionNo, title, summary, sourceId, sourceUrl, license, trustLevel,
                gameReleaseId, dlcId, language, region, eventType, retrievedAt, effectiveFrom,
                effectiveTo, freshness, status, checksum, schemaVersion, contentVersion,
                publishedAt, null, null);
    }

    /** 便于单元测试和本地 fixture 的最小安全构造器。 */
    public ActivityNotice(String id, String title, String summary, String sourceId, String sourceUrl,
                          String license, String trustLevel, String gameReleaseId, String language,
                          Instant retrievedAt, Instant effectiveFrom, Instant effectiveTo,
                          String freshness, String status, String checksum) {
        this(id, id, 1, title, summary, sourceId, sourceUrl, license, trustLevel, gameReleaseId,
                null, language, null, "GENERAL", retrievedAt, effectiveFrom, effectiveTo,
                freshness, status, checksum, "v1", "v1", retrievedAt, null, null);
    }

    /**
     * 计算相对于指定时刻的 freshness。持久化字段是输入快照，展示时必须以
     * 当前时钟重新计算，防止数据库中的 FRESH 标记过期后仍被使用。
     */
    public Freshness evaluateFreshness(Instant at, Duration maxAge) {
        Instant now = Objects.requireNonNull(at, "at 不能为空");
        Duration allowed = maxAge == null || maxAge.isNegative() || maxAge.isZero()
                ? Duration.ofDays(7) : maxAge;
        if (!hasReliableSource()) return Freshness.UNKNOWN;
        if (now.isBefore(effectiveFrom)) return Freshness.NOT_YET_ACTIVE;
        if (effectiveTo != null && !now.isBefore(effectiveTo)) return Freshness.EXPIRED;
        // A source timestamp in the future is not trustworthy; allow a small clock skew.
        if (retrievedAt.isAfter(now.plusSeconds(60))) return Freshness.UNKNOWN;
        return retrievedAt.plus(allowed).isAfter(now) ? Freshness.FRESH : Freshness.STALE;
    }

    public boolean hasReliableSource() {
        return !isPlaceholder(sourceId) && !isPlaceholder(license) && !isPlaceholder(trustLevel)
                && isHttpUrl(sourceUrl);
    }

    public boolean isEffectiveAt(Instant at) {
        if (at == null || at.isBefore(effectiveFrom)) return false;
        return effectiveTo == null || at.isBefore(effectiveTo);
    }

    /** 对外展示门禁：来源完整、已审核发布、在生效窗口内且抓取仍新鲜。 */
    public boolean isVisibleAt(Instant at, Duration maxAge) {
        Freshness computed = evaluateFreshness(at, maxAge);
        return hasPublishableStatus() && computed == Freshness.FRESH;
    }

    public boolean hasPublishableStatus() {
        return publishedAt != null && (STATUS_REVIEWED.equals(status) || STATUS_PUBLISHED.equals(status)
                || STATUS_ACTIVE.equals(status));
    }

    /** 返回带有当前计算 freshness 的展示副本，不改变原始快照。 */
    public ActivityNotice withFreshness(Freshness value) {
        Freshness safe = value == null ? Freshness.UNKNOWN : value;
        return new ActivityNotice(id, noticeKey, versionNo, title, summary, sourceId, sourceUrl,
                license, trustLevel, gameReleaseId, dlcId, language, region, eventType,
                retrievedAt, effectiveFrom, effectiveTo, safe.name(), status, checksum,
                schemaVersion, contentVersion, publishedAt, createdAt, updatedAt);
    }

    public enum Freshness {
        FRESH, STALE, EXPIRED, NOT_YET_ACTIVE, UNKNOWN
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return bounded(value.trim(), field, max);
    }

    private static String bounded(String value, String field, int max) {
        if (value.length() > max) throw new IllegalArgumentException(field + " 过长");
        return value;
    }

    private static String optional(String value, int max) {
        return value == null || value.isBlank() ? null : bounded(value.trim(), "字段", max);
    }

    private static String normalizeEnum(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeHttpUrl(String value) {
        String url = required(value, "sourceUrl", 2048);
        if (!isHttpUrl(url)) throw new IllegalArgumentException("sourceUrl 仅允许 HTTP(S)");
        return url;
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean isPlaceholder(String value) {
        if (value == null || value.isBlank()) return true;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("replace-with") || normalized.contains("待审核")
                || normalized.equals("pending") || normalized.equals("unknown")
                || normalized.equals("unverified") || normalized.equals("fixture");
    }
}
