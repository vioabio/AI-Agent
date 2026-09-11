package com.vio.vioaiagent.pokemon.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.domain.ActivityNotice;
import com.vio.vioaiagent.pokemon.repository.InMemoryActivityNoticeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 可选的本地活动 fixture loader。默认关闭；仅在非 prod profile 且显式开启
 * 时加载脱敏文件，不会把候选活动注入生产 JDBC 或绕过发布门禁。
 */
@Configuration
@Profile("!prod")
@ConditionalOnProperty(prefix = "vio.pokemon.activity.fixture", name = "enabled", havingValue = "true")
public class ActivityNoticeFixtureInitializer {
    private static final Logger log = LoggerFactory.getLogger(ActivityNoticeFixtureInitializer.class);

    private final InMemoryActivityNoticeRepository repository;
    private final ObjectMapper mapper;
    private final ResourceLoader resourceLoader;
    private final String location;

    public ActivityNoticeFixtureInitializer(InMemoryActivityNoticeRepository repository,
                                            ObjectMapper mapper,
                                            ResourceLoader resourceLoader,
                                            @Value("${vio.pokemon.activity.fixture.location:file:data/v1.1-fixture/activity-notices.json}") String location) {
        this.repository = repository;
        this.mapper = mapper.copy().findAndRegisterModules();
        this.resourceLoader = resourceLoader;
        this.location = location;
    }

    @PostConstruct
    public void load() {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) throw new IllegalStateException("Activity fixture 不存在: " + location);
        try (InputStream input = resource.getInputStream()) {
            JsonNode root = mapper.readTree(input);
            List<ActivityNotice> notices = parseNotices(root);
            repository.replaceFixture(notices);
            log.info("已加载本地 Activity fixture: location={}, notices={}, rootStatus={}",
                    location, notices.size(), root.path("status").asText("unknown"));
        } catch (Exception ex) {
            throw new IllegalStateException("Activity fixture 解析失败: " + location, ex);
        }
    }

    private List<ActivityNotice> parseNotices(JsonNode root) {
        JsonNode array = root.path("notices");
        if (!array.isArray()) throw new IllegalArgumentException("fixture 字段不是数组: notices");
        List<ActivityNotice> result = new ArrayList<>();
        for (JsonNode node : array) {
            result.add(new ActivityNotice(text(node, "id"), text(node, "noticeKey"),
                    node.path("versionNo").asInt(0), text(node, "title"), nullable(node, "summary"),
                    text(node, "sourceId"), text(node, "sourceUrl"), text(node, "license"),
                    text(node, "trustLevel"), text(node, "gameReleaseId"), nullable(node, "dlcId"),
                    text(node, "language"), nullable(node, "region"), text(node, "eventType"),
                    instant(node, "retrievedAt"), instant(node, "effectiveFrom"),
                    nullableInstant(node, "effectiveTo"), text(node, "freshness"), text(node, "status"),
                    text(node, "checksum"), text(node, "schemaVersion"), text(node, "contentVersion"),
                    nullableInstant(node, "publishedAt"), nullableInstant(node, "createdAt"),
                    nullableInstant(node, "updatedAt")));
        }
        return List.copyOf(result);
    }

    private Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("fixture 时间非法: " + field);
        }
    }

    private Instant nullableInstant(JsonNode node, String field) {
        String value = nullable(node, field);
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("fixture 时间非法: " + field);
        }
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("fixture 缺少字段: " + field);
        return value.trim();
    }

    private String nullable(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text.trim();
    }
}
