package com.vio.vioaiagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.constant.FileConstant;
import com.vio.vioaiagent.config.ResourceDownloadProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 受治理的资源下载工具。
 *
 * <p>下载结果写成不可变 artifact：不覆盖同名文件，先写同目录临时文件，
 * 校验大小/MIME/DNS 后再原子提升，并只向模型返回稳定字段，不泄露本机路径或
 * 底层网络异常。真正生产出口仍应在网关/代理层重复执行 SSRF 策略。</p>
 */
@Slf4j
public class ResourceDownloadTool {

    static final int DEFAULT_MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024;
    static final long DEFAULT_MAX_TOTAL_BYTES = 100 * 1024 * 1024L;
    static final Set<String> DEFAULT_ALLOWED_MEDIA_TYPES = Set.of(
            "image/", "application/pdf", "application/json", "text/plain");

    private final Path downloadDir;
    private final int maxDownloadBytes;
    private final long maxTotalBytes;
    private final Set<String> allowedMediaTypes;
    private final Object downloadLock = new Object();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResourceDownloadTool() {
        this(Path.of(FileConstant.FILE_SAVE_DIR, "download"),
                DEFAULT_MAX_DOWNLOAD_BYTES, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_ALLOWED_MEDIA_TYPES);
    }

    /** Spring 配置构造器；只消费非敏感目录/配额，不读取密钥。 */
    public ResourceDownloadTool(ResourceDownloadProperties properties) {
        this(properties == null ? Path.of(FileConstant.FILE_SAVE_DIR, "download") : properties.directoryPath(),
                properties == null ? DEFAULT_MAX_DOWNLOAD_BYTES : properties.maxBytes(),
                properties == null ? DEFAULT_MAX_TOTAL_BYTES : properties.maxTotalBytes(),
                properties == null ? DEFAULT_ALLOWED_MEDIA_TYPES : Set.copyOf(properties.allowedMediaTypes()));
        if (properties != null && !properties.validationErrors().isEmpty()) {
            throw new IllegalArgumentException("资源下载配置无效: " + String.join(",", properties.validationErrors()));
        }
    }

    /** 可注入的离线构造器，供单测和受控部署指定 artifact 根目录/配额。 */
    ResourceDownloadTool(Path downloadDir, int maxDownloadBytes, long maxTotalBytes,
                         Set<String> allowedMediaTypes) {
        if (downloadDir == null || maxDownloadBytes <= 0 || maxTotalBytes < maxDownloadBytes) {
            throw new IllegalArgumentException("下载目录或配额非法");
        }
        this.downloadDir = downloadDir.toAbsolutePath().normalize();
        this.maxDownloadBytes = maxDownloadBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.allowedMediaTypes = allowedMediaTypes == null || allowedMediaTypes.isEmpty()
                ? DEFAULT_ALLOWED_MEDIA_TYPES : Set.copyOf(allowedMediaTypes);
    }

    @Tool(description = "Download a resource from a public URL into an immutable artifact")
    public String downloadResource(@ToolParam(description = "URL of the resource to download") String url,
                                   @ToolParam(description = "Name of the file to save the downloaded resource") String fileName) {
        String safeFileName = safeFileName(fileName);
        if (safeFileName == null) return error("INVALID_FILE_NAME", "file name is invalid");
        URI target;
        try {
            target = validateTarget(url);
        } catch (IllegalArgumentException ex) {
            return error("INVALID_URL", "only public HTTP(S) URLs are allowed");
        }

        synchronized (downloadLock) {
            Path temporary = null;
            HttpURLConnection connection = null;
            try {
                Files.createDirectories(downloadDir);
                Path destination = downloadDir.resolve(safeFileName).normalize();
                if (!destination.getParent().equals(downloadDir)) {
                    return error("INVALID_FILE_NAME", "file name is invalid");
                }
                if (Files.exists(destination)) {
                    return error("ARTIFACT_EXISTS", "an artifact with this name already exists");
                }
                long existingBytes = directoryBytes(downloadDir);
                if (existingBytes >= maxTotalBytes) {
                    return error("DISK_QUOTA_EXCEEDED", "download storage quota exceeded");
                }

                // Re-resolve immediately before opening the socket. The post-response
                // comparison below catches common DNS rebinding attempts; production
                // should still force all egress through a policy-enforcing proxy.
                Set<String> resolvedBefore = publicAddresses(target.getHost());
                connection = (HttpURLConnection) target.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.setRequestProperty("Accept", "image/*,application/pdf,application/json,text/plain");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) return error("REDIRECT_NOT_ALLOWED", "redirects are not allowed");
                if (status < 200 || status >= 300) return error("UPSTREAM_HTTP_ERROR", "upstream request failed");
                Set<String> resolvedAfter = publicAddresses(target.getHost());
                if (!resolvedBefore.equals(resolvedAfter)) {
                    return error("DNS_REBINDING_BLOCKED", "network target changed during request");
                }

                String mediaType = mediaType(connection.getContentType());
                if (!allowed(mediaType)) return error("UNSUPPORTED_MEDIA_TYPE", "response media type is not allowed");
                long contentLength = connection.getContentLengthLong();
                if (contentLength > maxDownloadBytes || contentLength >= 0
                        && existingBytes + contentLength > maxTotalBytes) {
                    return error("DOWNLOAD_TOO_LARGE", "response exceeds download quota");
                }

                temporary = downloadDir.resolve(".part-" + UUID.randomUUID());
                long bytes;
                try (InputStream input = connection.getInputStream()) {
                    bytes = copyBounded(input, temporary, maxDownloadBytes);
                }
                if (bytes <= 0 || existingBytes + bytes > maxTotalBytes) {
                    return error("DOWNLOAD_TOO_LARGE", "response exceeds download quota");
                }
                String digest = sha256(temporary);
                moveWithoutOverwrite(temporary, destination);
                temporary = null;
                return success(safeFileName, bytes, digest, mediaType);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                return error("ARTIFACT_EXISTS", "an artifact with this name already exists");
            } catch (Exception ex) {
                // Log only the stable error class; URL, local path and nested message
                // may contain credentials or infrastructure details.
                log.warn("resource download failed: errorType={}", ex.getClass().getSimpleName());
                return error("DOWNLOAD_FAILED", "resource download failed");
            } finally {
                if (connection != null) connection.disconnect();
                if (temporary != null) {
                    try { Files.deleteIfExists(temporary); }
                    catch (IOException cleanup) { log.warn("download temp cleanup failed: errorType={}", cleanup.getClass().getSimpleName()); }
                }
            }
        }
    }

    /** 只允许单层文件名和有限 ASCII 集，避免路径/控制字符进入 artifact 名称。 */
    static String safeFileName(String value) {
        if (value == null || value.isBlank() || value.length() > 180) return null;
        try {
            Path path = Path.of(value);
            if (!path.getFileName().toString().equals(value) || value.indexOf('\0') >= 0) return null;
        } catch (RuntimeException ex) {
            return null;
        }
        return value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,179}") ? value : null;
    }

    static URI validateTarget(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("url");
        URI target = URI.create(value.trim());
        if (!("http".equalsIgnoreCase(target.getScheme()) || "https".equalsIgnoreCase(target.getScheme()))
                || target.getHost() == null || target.getHost().isBlank()
                || target.getUserInfo() != null || target.getPort() > 0 && target.getPort() < 1024) {
            throw new IllegalArgumentException("url");
        }
        publicAddresses(target.getHost());
        return target;
    }

    static Set<String> publicAddresses(String host) {
        try {
            Set<String> addresses = java.util.Arrays.stream(InetAddress.getAllByName(host))
                    .map(InetAddress::getHostAddress).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (addresses.isEmpty() || addresses.stream().anyMatch(ResourceDownloadTool::isPrivateOrReserved)) {
                throw new IllegalArgumentException("private address");
            }
            return addresses;
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("unresolvable host", ex);
        }
    }

    private static boolean isPrivateOrReserved(String raw) {
        try {
            InetAddress address = InetAddress.getByName(raw);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) return true;
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                int a = bytes[0] & 0xff, b = bytes[1] & 0xff, c = bytes[2] & 0xff;
                return a == 0 || a == 10 || a == 127 || a >= 224
                        || a == 169 && b == 254
                        || a == 172 && b >= 16 && b <= 31
                        || a == 192 && (b == 0 || b == 168)
                        || a == 198 && (b == 18 || b == 19 || b == 51)
                        || a == 203 && b == 0 && c == 113
                        || a == 100 && b >= 64 && b <= 127;
            }
            // IPv6 ULA fc00::/7 and documentation 2001:db8::/32.
            return (bytes[0] & 0xfe) == 0xfc
                    || (bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8;
        } catch (UnknownHostException ex) {
            return true;
        }
    }

    private boolean allowed(String mediaType) {
        return mediaTypeAllowed(mediaType, allowedMediaTypes);
    }

    /**
     * MIME allowlist semantics are exact for a concrete type and prefix-only
     * when the configured value explicitly ends in {@code /}.  A plain
     * {@code startsWith} check would incorrectly accept values such as
     * {@code application/json-malicious} for an {@code application/json}
     * allowlist entry.
     */
    static boolean mediaTypeAllowed(String mediaType, Set<String> allowlist) {
        if (mediaType == null || mediaType.isBlank() || allowlist == null || allowlist.isEmpty()) return false;
        String normalized = mediaType.trim().toLowerCase(Locale.ROOT);
        return allowlist.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(pattern -> pattern.endsWith("/")
                        ? normalized.startsWith(pattern)
                        : normalized.equals(pattern));
    }

    private static String mediaType(String contentType) {
        if (contentType == null) return "";
        int separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator)).trim().toLowerCase(Locale.ROOT);
    }

    private static long copyBounded(InputStream input, Path destination, long limit) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (var output = Files.newOutputStream(destination)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > limit) throw new IOException("response too large");
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    private static void moveWithoutOverwrite(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, destination);
        }
    }

    private static long directoryBytes(Path directory) throws IOException {
        try (var stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); } catch (IOException ex) { return Long.MAX_VALUE; }
            }).reduce(0L, (left, right) -> {
                if (left == Long.MAX_VALUE || right == Long.MAX_VALUE || Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
                return left + right;
            });
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String success(String fileName, long bytes, String digest, String mediaType) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "status", "success", "artifactFileName", fileName, "bytes", bytes,
                    "sha256", digest, "mediaType", mediaType));
        } catch (Exception ex) {
            return "{\"status\":\"success\",\"artifactFileName\":\"" + fileName + "\"}";
        }
    }

    private String error(String code, String message) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "status", "error", "code", code, "message", message));
        } catch (Exception ex) {
            return "{\"status\":\"error\",\"code\":\"DOWNLOAD_FAILED\"}";
        }
    }
}
