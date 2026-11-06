package com.vio.vioaiagent.run;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresMigrationCatalogTest {
    @Test
    void packagedMigrationVersionsMatchTheReviewedInventory() throws Exception {
        var resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");
        var versions = Arrays.stream(resources)
                .map(resource -> resource.getFilename().split("__", 2)[0].substring(1))
                .sorted((left, right) -> MigrationVersion.fromVersion(left)
                        .compareTo(MigrationVersion.fromVersion(right)))
                .toList();
        assertEquals(PostgresMigrationCatalog.VERSIONS, versions,
                "迁移脚本新增、缺失或版本重复时，必须重新核对迁移清单与真实数据库测试");
    }
}
