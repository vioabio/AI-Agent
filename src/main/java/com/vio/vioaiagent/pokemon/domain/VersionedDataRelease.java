package com.vio.vioaiagent.pokemon.domain;

/**
 * 宝可梦事实所属的数据发布。事实接口必须返回该血缘，避免调用方把不同
 * 版本的数值混在一起。
 */
public record VersionedDataRelease(String id, String gameReleaseId, String sourceUrl,
                                   String checksum, String status) {
    public VersionedDataRelease {
        require(id, "dataReleaseId");
        require(gameReleaseId, "gameReleaseId");
        require(sourceUrl, "sourceUrl");
        require(checksum, "checksum");
        require(status, "status");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
    }
}
