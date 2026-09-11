package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.common.Result;
import com.vio.vioaiagent.rag.ingestion.KnowledgeIngestionConflictException;
import com.vio.vioaiagent.rag.ingestion.KnowledgeIngestionJobService;
import com.vio.vioaiagent.rag.ingestion.KnowledgeIngestionNotFoundException;
import com.vio.vioaiagent.rag.ingestion.KnowledgeLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 异步摄取 HTTP 契约：并发冲突/资源不存在不能被误报为 400。 */
class KnowledgeLifecycleControllerContractTest {

    @TempDir
    Path staging;

    @Test
    void submitMapsIngestionConflictTo409() throws Exception {
        Files.writeString(staging.resolve("guide.md"), "guide");
        KnowledgeIngestionJobService jobs = mock(KnowledgeIngestionJobService.class);
        when(jobs.submit(any(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new KnowledgeIngestionConflictException("idempotencyKey 冲突"));
        KnowledgeLifecycleController controller = controllerWith(jobs);

        ResponseEntity<Result<KnowledgeLifecycleController.IngestionJobView>> response =
                controller.submitIngestionJob(validJobBody(), null, null);

        assertEquals(409, response.getStatusCode().value());
        assertEquals(409, response.getBody().getCode());
    }

    @Test
    void replayMapsMissingDlqTo404() {
        KnowledgeIngestionJobService jobs = mock(KnowledgeIngestionJobService.class);
        when(jobs.replayDlq(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new KnowledgeIngestionNotFoundException("找不到 DLQ"));
        KnowledgeLifecycleController controller = controllerWith(jobs);
        KnowledgeLifecycleController.ReplayBody body = new KnowledgeLifecycleController.ReplayBody();
        body.reason = "修复后重放";
        body.newIdempotencyKey = "replay-key";

        ResponseEntity<Result<KnowledgeLifecycleController.IngestionJobView>> response =
                controller.replayIngestionDlq("missing-dlq", body, null, null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals(404, response.getBody().getCode());
    }

    @Test
    void infrastructureFailureMapsTo503WithoutLeakingCause() throws Exception {
        Files.writeString(staging.resolve("guide.md"), "guide");
        KnowledgeIngestionJobService jobs = mock(KnowledgeIngestionJobService.class);
        when(jobs.submit(any(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("jdbc:postgresql://db.internal/app?password=secret"));
        KnowledgeLifecycleController controller = controllerWith(jobs);

        ResponseEntity<Result<KnowledgeLifecycleController.IngestionJobView>> response =
                controller.submitIngestionJob(validJobBody(), null, null);

        assertEquals(503, response.getStatusCode().value());
        assertEquals(503, response.getBody().getCode());
        org.junit.jupiter.api.Assertions.assertEquals("依赖服务暂不可用", response.getBody().getMessage());
    }

    @Test
    void unexpectedStateMapsTo500WithoutLeakingCause() throws Exception {
        Files.writeString(staging.resolve("guide.md"), "guide");
        KnowledgeIngestionJobService jobs = mock(KnowledgeIngestionJobService.class);
        when(jobs.submit(any(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("local path C:\\secrets\\token.txt"));
        KnowledgeLifecycleController controller = controllerWith(jobs);

        ResponseEntity<Result<KnowledgeLifecycleController.IngestionJobView>> response =
                controller.submitIngestionJob(validJobBody(), null, null);

        assertEquals(500, response.getStatusCode().value());
        assertEquals(500, response.getBody().getCode());
        org.junit.jupiter.api.Assertions.assertEquals("服务内部错误", response.getBody().getMessage());
    }

    @Test
    void validationMessageIsRedactedWhenItLooksLikeACredential() throws Exception {
        Files.writeString(staging.resolve("guide.md"), "guide");
        KnowledgeIngestionJobService jobs = mock(KnowledgeIngestionJobService.class);
        when(jobs.submit(any(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalArgumentException("token=do-not-return-this"));
        KnowledgeLifecycleController controller = controllerWith(jobs);

        ResponseEntity<Result<KnowledgeLifecycleController.IngestionJobView>> response =
                controller.submitIngestionJob(validJobBody(), null, null);

        assertEquals(400, response.getStatusCode().value());
        assertEquals(400, response.getBody().getCode());
        org.junit.jupiter.api.Assertions.assertEquals("请求不合法", response.getBody().getMessage());
    }

    private KnowledgeLifecycleController controllerWith(KnowledgeIngestionJobService jobs) {
        KnowledgeLifecycleController controller = new KnowledgeLifecycleController(
                mock(KnowledgeLifecycleService.class), staging.toString());
        ReflectionTestUtils.setField(controller, "ingestionJobService", jobs);
        return controller;
    }

    private KnowledgeLifecycleController.IngestJobBody validJobBody() {
        KnowledgeLifecycleController.IngestJobBody body = new KnowledgeLifecycleController.IngestJobBody();
        body.stagedFile = "guide.md";
        body.versionNo = "v1";
        body.parserVersion = "parser-1";
        body.schemaVersion = "1";
        body.contentVersion = "content-1";
        body.idempotencyKey = "guide-key";
        body.targetKnowledgeReleaseId = "release-1";
        body.source = new KnowledgeLifecycleController.SourceBody();
        body.source.id = "source-1";
        body.source.sourceUrl = "https://example.invalid/guide";
        body.source.title = "官方攻略";
        body.source.license = "CC-BY-4.0";
        body.source.trustLevel = "OFFICIAL";
        body.source.ownerId = "operator-1";
        body.document = new KnowledgeLifecycleController.DocumentBody();
        body.document.id = "doc-1";
        body.document.canonicalKey = "guide/sv";
        body.document.language = "zh-CN";
        body.document.gameReleaseId = "sv-1";
        body.document.battleFormatId = "singles";
        body.document.rulesetVersionId = "rules-1";
        return body;
    }
}
