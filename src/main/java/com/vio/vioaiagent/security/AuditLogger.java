package com.vio.vioaiagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

/**
 * 审计日志记录器 — 安全体系第四层.
 *
 * <p>将每次工具调用以 JSONL 格式按天持久化到 logs/audit/ 目录下.
 * 每条记录一行独立的 JSON, 便于用 grep、jq 等工具查询和分析.
 *
 * <p>结合参数脱敏器（ParameterMasker）在写入前自动处理敏感数据.
 *
 * <pre>{@code
 * AuditLogger logger = new AuditLogger(masker);
 * logger.log(AuditEntry.of(traceId, sessionId, userId,
 *     toolName, masker.mask(params), "allow", "hitl", 150));
 * }</pre>
 *
 * @author vio
 */
@Slf4j
public class AuditLogger {

    private final ParameterMasker masker;
    private final ObjectMapper mapper;

    public AuditLogger(ParameterMasker masker) {
        this.masker = masker;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    /**
     * 记录一条审计日志.
     *
     * @param entry 审计条目
     */
    public void log(AuditEntry entry) {
        // 脱敏参数
        Map<String, Object> maskedParams = masker.mask(entry.params());

        // 创建脱敏后的条目
        AuditEntry maskedEntry = new AuditEntry(
                entry.traceId(), entry.sessionId(), entry.userId(),
                entry.toolName(), maskedParams,
                entry.outcome(), entry.approver(), entry.durationMs(),
                entry.timestamp()
        );

        // 按天写入 JSONL 文件
        String filename = "logs" + File.separator + "audit" + File.separator
                + "audit-" + LocalDate.now() + ".jsonl";
        File file = new File(filename);
        file.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(mapper.writeValueAsString(maskedEntry) + "\n");
            writer.flush();
        } catch (IOException e) {
            log.error("审计日志写入失败: {}", filename, e);
        }
    }

    /**
     * 记录工具调用（便捷方法）.
     */
    public void log(String traceId, String sessionId, String userId,
                     String toolName, Map<String, Object> params,
                     String outcome, String approver, long durationMs) {
        log(AuditEntry.of(traceId, sessionId, userId,
                toolName, params, outcome, approver, durationMs));
    }
}
