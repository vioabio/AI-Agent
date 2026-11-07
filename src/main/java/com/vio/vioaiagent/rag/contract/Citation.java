package com.vio.vioaiagent.rag.contract;

/** 面向用户展示且可审计的证据引用。 */
public record Citation(
        String documentId,
        String documentVersion,
        String sourceTitle,
        String sourceUrl,
        String chunkId,
        String evidenceText,
        double retrievalScore) {

    public Citation {
        if (documentId == null || documentId.isBlank() || chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("引用必须包含 documentId 和 chunkId");
        }
        evidenceText = evidenceText == null ? "" : evidenceText;
    }
}

