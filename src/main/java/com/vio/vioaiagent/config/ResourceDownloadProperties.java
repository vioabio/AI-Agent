package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 资源 artifact 下载的非敏感运行参数。配额是进程/挂载点级别的最后一道
 * 防线；多实例部署仍应在对象存储或出口网关配置租户级配额。
 */
@ConfigurationProperties(prefix = "vio.tools.resource-download")
public class ResourceDownloadProperties {
    private String directory = "${user.dir}/tmp/download";
    private int maxBytes = 20 * 1024 * 1024;
    private long maxTotalBytes = 100 * 1024 * 1024L;
    private List<String> allowedMediaTypes = new ArrayList<>(List.of(
            "image/", "application/pdf", "application/json", "text/plain"));

    public String getDirectory() { return directory == null ? "" : directory.trim(); }
    public String directory() { return getDirectory(); }
    public void setDirectory(String value) { directory = value == null ? "" : value.trim(); }

    public int getMaxBytes() { return maxBytes; }
    public int maxBytes() { return maxBytes; }
    public void setMaxBytes(int value) { maxBytes = value; }

    public long getMaxTotalBytes() { return maxTotalBytes; }
    public long maxTotalBytes() { return maxTotalBytes; }
    public void setMaxTotalBytes(long value) { maxTotalBytes = value; }

    public List<String> getAllowedMediaTypes() {
        return allowedMediaTypes == null ? List.of() : List.copyOf(allowedMediaTypes);
    }
    public List<String> allowedMediaTypes() { return getAllowedMediaTypes(); }
    public void setAllowedMediaTypes(List<String> values) {
        allowedMediaTypes = values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim).map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
    }

    /** 仅做本地配置校验，不探测网络或文件系统。 */
    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        if (getDirectory().isBlank() || getDirectory().length() > 1024) errors.add("directory 无效");
        if (maxBytes <= 0 || maxBytes > 1024L * 1024 * 1024) errors.add("maxBytes 必须在 1..1GiB");
        if (maxTotalBytes < maxBytes || maxTotalBytes > 100L * 1024 * 1024 * 1024) {
            errors.add("maxTotalBytes 必须不小于 maxBytes 且不超过 100GiB");
        }
        if (getAllowedMediaTypes().isEmpty() || getAllowedMediaTypes().stream()
                .anyMatch(value -> value.length() > 128 || !value.matches("[a-z0-9.+-]+/[a-z0-9*.+-]*"))) {
            errors.add("allowedMediaTypes 必须是 MIME 类型或前缀");
        }
        return List.copyOf(errors);
    }

    public Path directoryPath() {
        String value = getDirectory().replace("${user.dir}", System.getProperty("user.dir", "."));
        return Path.of(value).toAbsolutePath().normalize();
    }
}
