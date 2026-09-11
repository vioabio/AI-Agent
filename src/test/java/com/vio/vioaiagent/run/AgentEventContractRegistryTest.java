package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentEventContractRegistryTest {

    @Test
    void citationCardRequiresReleaseLineageAndHttpSource() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceUrl", "https://example.com/source");
        payload.put("evidenceText", "证据");
        payload.put("documentVersion", "v1");
        payload.put("chunkId", "chunk-1");
        payload.put("dataReleaseId", "data-1");
        payload.put("knowledgeReleaseId", "knowledge-1");
        payload.put("indexReleaseId", "index-1");
        assertDoesNotThrow(() -> AgentEventContractRegistry.validate(
                AgentEventType.RETRIEVAL_CITATION_CARD, payload));

        payload.put("sourceUrl", "file:///tmp/evidence");
        assertThrows(IllegalArgumentException.class, () -> AgentEventContractRegistry.validate(
                AgentEventType.RETRIEVAL_CITATION_CARD, payload));
    }

    @Test
    void completeAnswerCannotUseUnknownReleasePlaceholder() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answerId", "answer-run-1");
        payload.put("lineageStatus", "COMPLETE");
        payload.put("gameReleaseId", "pokemon-scarlet-violet");
        payload.put("battleFormatId", "casual-singles-v1");
        payload.put("rulesetVersionId", "casual-v1");
        payload.put("dataReleaseId", "data-1");
        payload.put("knowledgeReleaseId", "unknown");
        payload.put("indexReleaseId", "index-1");
        assertThrows(IllegalArgumentException.class, () -> AgentEventContractRegistry.validate(
                AgentEventType.ANSWER_COMPLETED, payload));
    }

    @Test
    void contextCardRequiresStableKeysButAllowsMissingValuesForClarification() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("gameReleaseId", "");
        payload.put("battleFormatId", "casual-singles-v1");
        payload.put("rulesetVersionId", "");
        payload.put("language", "zh-CN");
        assertDoesNotThrow(() -> AgentEventContractRegistry.validate(
                AgentEventType.CONTEXT_CARD, payload));
        payload.remove("language");
        assertThrows(IllegalArgumentException.class, () -> AgentEventContractRegistry.validate(
                AgentEventType.CONTEXT_CARD, payload));
    }
}
