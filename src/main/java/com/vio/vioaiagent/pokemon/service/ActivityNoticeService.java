package com.vio.vioaiagent.pokemon.service;

import com.vio.vioaiagent.pokemon.domain.ActivityNotice;
import com.vio.vioaiagent.pokemon.repository.ActivityNoticeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 活动资讯只读门面。
 *
 * <p>仓储返回的是候选版本；本服务以统一 Clock 重算 freshness，并在来源、
 * 发布状态、有效期任一不满足时隐藏/拒答。这样 InMemory 与 JDBC 实现不会
 * 因数据库字段陈旧而把过期活动展示给 Agent 或前端。</p>
 */
@Service
public class ActivityNoticeService {
    private static final int MAX_LIMIT = 200;

    private final ActivityNoticeRepository repository;
    private final Clock clock;
    private final Duration maxFreshnessAge;

    /** Spring 生产构造器；窗口由非敏感配置控制。 */
    @Autowired
    public ActivityNoticeService(ActivityNoticeRepository repository,
                                 @Value("${vio.pokemon.activity.max-freshness-days:7}") int maxFreshnessDays) {
        this(repository, Clock.systemUTC(), Duration.ofDays(clampDays(maxFreshnessDays)));
    }

    /** 本地/单元测试兼容构造器。 */
    public ActivityNoticeService(ActivityNoticeRepository repository) {
        this(repository, Clock.systemUTC(), Duration.ofDays(7));
    }

    /** 可注入 Clock 的确定性构造器。 */
    public ActivityNoticeService(ActivityNoticeRepository repository, Clock clock, Duration maxFreshnessAge) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxFreshnessAge = maxFreshnessAge == null || maxFreshnessAge.isNegative()
                || maxFreshnessAge.isZero() ? Duration.ofDays(7) : maxFreshnessAge;
    }

    /** 查询当前或指定时刻的活动；过期、未发布和不可靠来源会被过滤。 */
    public ActivityQueryResult search(String gameReleaseId, String dlcId, String language,
                                      String region, Instant at, int limit) {
        String release = required(gameReleaseId, "gameReleaseId");
        String normalizedLanguage = required(language, "language");
        Instant asOf = at == null ? clock.instant() : at;
        int bounded = boundLimit(limit);
        // 先多取候选再做 freshness 过滤，避免 stale/candidate 占满数据库 LIMIT。
        ActivityNoticeRepository.ActivityNoticeQuery query = new ActivityNoticeRepository.ActivityNoticeQuery(
                release, dlcId, normalizedLanguage, region, asOf, MAX_LIMIT);
        List<ActivityNotice> visible = repository.find(query).stream()
                .map(notice -> visibleCopy(notice, asOf))
                .filter(Objects::nonNull)
                .limit(bounded)
                .toList();
        return new ActivityQueryResult(release, dlcId, normalizedLanguage, region, asOf,
                maxFreshnessAge.toDays(), visible, visible.size());
    }

    public ActivityQueryResult search(String gameReleaseId, String dlcId, String language,
                                      String region, int limit) {
        return search(gameReleaseId, dlcId, language, region, null, limit);
    }

    /** 便于领域调用方直接取得活动列表。 */
    public List<ActivityNotice> listActive(String gameReleaseId, String dlcId, String language,
                                           String region, int limit) {
        return search(gameReleaseId, dlcId, language, region, null, limit).items();
    }

    /** 查询单个活动；不可展示时统一拒答，不返回候选内容。 */
    public ActivityNotice find(String id) {
        return find(id, null);
    }

    public ActivityNotice find(String id, Instant at) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("noticeId 不能为空");
        ActivityNotice notice = repository.findById(id.trim())
                .orElseThrow(() -> new ActivityNoticeNotFoundException("活动资讯不存在"));
        Instant asOf = at == null ? clock.instant() : at;
        ActivityNotice.Freshness freshness = notice.evaluateFreshness(asOf, maxFreshnessAge);
        if (!notice.hasReliableSource() || !notice.hasPublishableStatus()) {
            throw new ActivityNoticeUnavailableException("SOURCE_OR_RELEASE_UNVERIFIED", "活动资讯暂不可用");
        }
        if (freshness != ActivityNotice.Freshness.FRESH) {
            // 不把标题/来源等候选字段带回客户端；reasonCode 只用于稳定错误契约。
            throw new ActivityNoticeUnavailableException(freshness.name(), "活动资讯已过期或尚未生效");
        }
        return notice.withFreshness(freshness);
    }

    public Duration maxFreshnessAge() {
        return maxFreshnessAge;
    }

    private ActivityNotice visibleCopy(ActivityNotice notice, Instant asOf) {
        if (notice == null || !notice.hasReliableSource() || !notice.hasPublishableStatus()) return null;
        ActivityNotice.Freshness freshness = notice.evaluateFreshness(asOf, maxFreshnessAge);
        return freshness == ActivityNotice.Freshness.FRESH ? notice.withFreshness(freshness) : null;
    }

    private int boundLimit(int limit) {
        return limit <= 0 ? 50 : Math.min(limit, MAX_LIMIT);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return value.trim();
    }

    private static int clampDays(int days) {
        return days <= 0 ? 7 : Math.min(days, 3650);
    }

    public record ActivityQueryResult(String gameReleaseId, String dlcId, String language,
                                      String region, Instant asOf, long freshnessWindowDays,
                                      List<ActivityNotice> items, int total) {
        public ActivityQueryResult {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
