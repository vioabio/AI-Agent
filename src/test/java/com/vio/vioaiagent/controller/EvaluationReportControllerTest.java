package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.feedback.FeedbackAuthorization;
import com.vio.vioaiagent.rag.promotion.EvaluationReportConflictException;
import com.vio.vioaiagent.rag.promotion.EvaluationReport;
import com.vio.vioaiagent.rag.promotion.EvaluationReportRepository;
import com.vio.vioaiagent.security.PrincipalPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Controller-only authorization and safe error contract; OIDC filter-chain tests cover identity wiring. */
class EvaluationReportControllerTest {
    final EvaluationReportRepository reports = mock(EvaluationReportRepository.class);
    final FeedbackAuthorization authorization = mock(FeedbackAuthorization.class);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EvaluationReportController(reports, authorization)).build();

    @AfterEach
    void clearContext() { RequestContext.clear(); }

    @Test
    void anonymousCannotImportWithForgedRoleOrUserHeaders() throws Exception {
        mvc.perform(post("/knowledge/evaluation-reports").contentType(MediaType.APPLICATION_JSON)
                .header("X-Evaluation-Report-Hash", "hash").header("X-Evaluation-Source", "source")
                .header("X-Role", "QUALITY").header("X-User-Id", "admin").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reports);
    }

    @Test
    void authenticatedReaderCannotImportEvenWithForgedOperatorHeader() throws Exception {
        identity("reader");
        mvc.perform(post("/knowledge/evaluation-reports").contentType(MediaType.APPLICATION_JSON)
                .header("X-Evaluation-Report-Hash", "hash").header("X-Evaluation-Source", "source")
                .header("X-Role", "QUALITY").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reports);
    }

    @Test
    void invalidEvidenceAndConflictingImportsHaveDistinctErrors() throws Exception {
        identity("quality");
        when(reports.importArtifact(any(), anyString(), anyString(), eq("quality-user")))
                .thenThrow(new IllegalArgumentException("internal artifact details"))
                .thenThrow(new EvaluationReportConflictException());
        mvc.perform(upload("{}".getBytes())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("评测报告内容、摘要或基线不符合冻结契约"));
        mvc.perform(upload("{}".getBytes())).andExpect(status().isConflict());
    }

    @Test
    void oversizedUploadIsRejectedBeforePersistence() throws Exception {
        identity("quality");
        mvc.perform(upload(new byte[1_048_577])).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(reports);
    }

    @Test
    void trustedOperatorImportsExactBytesAndServerIdentity() throws Exception {
        identity("quality");
        byte[] original = "{\"note\":\"原始报告\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        EvaluationReport report = new EvaluationReport("eval-controller", "sha256:" + "0".repeat(64),
                "v1.1", "fixture-model", "fixture-data", "fixture-knowledge", "fixture-index",
                1, false, true, java.time.Instant.parse("2026-10-10T00:00:00Z"));
        when(reports.importArtifact(aryEq(original), anyString(), anyString(), eq("quality-user")))
                .thenReturn(report);
        mvc.perform(upload(original).header("X-User-Id", "forged-admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.evaluationRunId").value("eval-controller"));
        verify(reports).importArtifact(aryEq(original), eq(report.reportHash()), eq("ci/run/metrics.json"), eq("quality-user"));
    }

    @Test
    void repositoryFailuresDoNotExposeConnectionOrSecretDetails() throws Exception {
        identity("quality");
        when(reports.findByEvaluationRunId("run-1"))
                .thenThrow(new DataAccessResourceFailureException("jdbc:postgresql://private/?password=secret"));
        mvc.perform(get("/knowledge/evaluation-reports/run-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("评测报告读取或完整性校验失败"));
    }

    private void identity(String role) {
        RequestContext.setPrincipalId("quality-user");
        RequestContext.setPrincipalPolicy(new PrincipalPolicy("quality-user", "test-tenant",
                Set.of(role), Set.of("fixture-tool")));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder upload(byte[] content) {
        return post("/knowledge/evaluation-reports").contentType(MediaType.APPLICATION_JSON)
                .header("X-Evaluation-Report-Hash", "sha256:" + "0".repeat(64))
                .header("X-Evaluation-Source", "ci/run/metrics.json").content(content);
    }
}
