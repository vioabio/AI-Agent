package com.vio.vioaiagent.pokemon.repository;

import com.vio.vioaiagent.pokemon.domain.ActivityNotice;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 活动资讯持久化边界。仓储可以返回候选记录，最终来源/时效门禁由
 * {@code ActivityNoticeService} 统一执行，避免不同实现产生不同安全语义。
 */
public interface ActivityNoticeRepository {

    /**
     * 查询指定时刻生效窗口内的候选资讯。实现不得把数据库中的 freshness
     * 字段当作最终判断，服务层会以当前时钟重新计算。
     */
    List<ActivityNotice> find(ActivityNoticeQuery query);

    Optional<ActivityNotice> findById(String id);

    /** 供显式 fixture/摄取流程使用；产品 Controller 不暴露写入口。 */
    ActivityNotice save(ActivityNotice notice);

    /** 仅供本地 fixture loader 和测试替换数据。 */
    default void replaceFixture(Collection<ActivityNotice> notices) {
        throw new UnsupportedOperationException("当前仓储不支持 fixture 替换");
    }

    /** 查询过滤条件；at 使用 UTC Instant，时间窗口采用半开区间。 */
    record ActivityNoticeQuery(String gameReleaseId, String dlcId, String language,
                               String region, Instant at, int limit) {
        public ActivityNoticeQuery {
            gameReleaseId = normalize(gameReleaseId);
            dlcId = normalize(dlcId);
            language = normalize(language);
            region = normalize(region);
            at = at == null ? Instant.now() : at;
            limit = limit <= 0 ? 50 : Math.min(limit, 200);
        }

        public static ActivityNoticeQuery active(String gameReleaseId, String dlcId,
                                                 String language, String region, int limit) {
            return new ActivityNoticeQuery(gameReleaseId, dlcId, language, region,
                    Instant.now(), limit);
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
