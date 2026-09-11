package com.vio.vioaiagent.pokemon.repository;

import com.vio.vioaiagent.pokemon.domain.ActivityNotice;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 本地/测试活动资讯仓储；默认空库，必须显式注入脱敏 fixture。 */
@Repository
@Primary
@Profile("!prod")
public class InMemoryActivityNoticeRepository implements ActivityNoticeRepository {
    private final ConcurrentMap<String, ActivityNotice> notices = new ConcurrentHashMap<>();

    public InMemoryActivityNoticeRepository() {
    }

    public InMemoryActivityNoticeRepository(Collection<ActivityNotice> initial) {
        replaceFixture(initial);
    }

    @Override
    public ActivityNotice save(ActivityNotice notice) {
        if (notice == null) throw new IllegalArgumentException("活动资讯不能为空");
        // 版本 ID 是不可变快照：相同 id 只能保存内容完全一致的重放。
        ActivityNotice previous = notices.putIfAbsent(notice.id(), notice);
        if (previous != null && !previous.equals(notice)) {
            throw new IllegalStateException("活动资讯版本不可覆盖: " + notice.id());
        }
        return previous == null ? notice : previous;
    }

    @Override
    public void replaceFixture(Collection<ActivityNotice> values) {
        notices.clear();
        if (values != null) values.forEach(this::save);
    }

    @Override
    public Optional<ActivityNotice> findById(String id) {
        return id == null || id.isBlank() ? Optional.empty() : Optional.ofNullable(notices.get(id.trim()));
    }

    @Override
    public List<ActivityNotice> find(ActivityNoticeQuery query) {
        if (query == null) return List.of();
        return notices.values().stream()
                .filter(value -> query.gameReleaseId() == null
                        || query.gameReleaseId().equals(value.gameReleaseId()))
                .filter(value -> query.dlcId() == null || query.dlcId().equals(value.dlcId()))
                .filter(value -> query.language() == null || query.language().equalsIgnoreCase(value.language()))
                .filter(value -> query.region() == null || query.region().equalsIgnoreCase(value.region()))
                .filter(value -> value.isEffectiveAt(query.at()))
                .sorted(Comparator.comparing(ActivityNotice::effectiveFrom).reversed()
                        .thenComparing(ActivityNotice::noticeKey)
                        .thenComparing(Comparator.comparingInt(ActivityNotice::versionNo).reversed())
                        .thenComparing(ActivityNotice::id))
                .limit(query.limit())
                .toList();
    }
}
