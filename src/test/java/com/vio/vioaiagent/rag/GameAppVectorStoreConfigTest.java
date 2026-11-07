package com.vio.vioaiagent.rag;

import com.vio.vioaiagent.config.LegacyMemoryRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the local compatibility RAG bootstrap.
 * No model or network call is made: every ETL dependency is a mock.
 */
class GameAppVectorStoreConfigTest {

    @Test
    void rejectsLoadFailureByDefaultInsteadOfReturningAnEmptyStore() {
        GameAppDocumentLoader loader = mock(GameAppDocumentLoader.class);
        when(loader.loadMarkdowns()).thenThrow(new IllegalStateException("provider detail must not leak"));

        LegacyMemoryRagProperties properties = new LegacyMemoryRagProperties();
        LegacyMemoryVectorStoreInitializationException failure = assertThrows(
                LegacyMemoryVectorStoreInitializationException.class,
                () -> config(loader, properties).gameAppVectorStore(mock(EmbeddingModel.class)));

        assertEquals(LegacyMemoryVectorStoreInitializationException.CODE, failure.code());
        assertEquals("document-load", failure.phase());
        assertEquals("IllegalStateException", failure.failureType());
        // The provider's message is deliberately not copied into the safe error.
        org.junit.jupiter.api.Assertions.assertFalse(failure.getMessage().contains("provider detail"));
    }

    @Test
    void rejectsAnEmptyLoadedCorpusByDefault() {
        GameAppDocumentLoader loader = mock(GameAppDocumentLoader.class);
        when(loader.loadMarkdowns()).thenReturn(List.of());

        LegacyMemoryVectorStoreInitializationException failure = assertThrows(
                LegacyMemoryVectorStoreInitializationException.class,
                () -> config(loader, new LegacyMemoryRagProperties())
                        .gameAppVectorStore(mock(EmbeddingModel.class)));

        assertEquals("document-load", failure.phase());
    }

    @Test
    void rejectsEnrichmentFailureByDefault() {
        GameAppDocumentLoader loader = mock(GameAppDocumentLoader.class);
        when(loader.loadMarkdowns()).thenReturn(List.of(new Document("fixture")));
        MyTokenTextSplitter splitter = mock(MyTokenTextSplitter.class);
        when(splitter.splitCustomized(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(new Document("chunk")));
        MyKeywordEnricher enricher = mock(MyKeywordEnricher.class);
        when(enricher.enrichDocuments(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new RuntimeException("enrichment unavailable"));

        LegacyMemoryVectorStoreInitializationException failure = assertThrows(
                LegacyMemoryVectorStoreInitializationException.class,
                () -> new GameAppVectorStoreConfig(loader, splitter, enricher,
                        new LegacyMemoryRagProperties()).gameAppVectorStore(mock(EmbeddingModel.class)));

        assertEquals("metadata-enrichment", failure.phase());
        assertEquals("RuntimeException", failure.failureType());
    }

    @Test
    void explicitOptInReturnsAnEmptyStoreWithDegradedSemantics() {
        GameAppDocumentLoader loader = mock(GameAppDocumentLoader.class);
        when(loader.loadMarkdowns()).thenThrow(new IllegalStateException("offline fixture"));

        LegacyMemoryRagProperties properties = new LegacyMemoryRagProperties();
        properties.setEnabled(true);

        VectorStore store = config(loader, properties).gameAppVectorStore(mock(EmbeddingModel.class));

        assertNotNull(store);
        org.junit.jupiter.api.Assertions.assertTrue(store instanceof SimpleVectorStore);
        // The opt-in path is intentionally an empty compatibility store; it
        // must not be mistaken for a populated/production knowledge index.
        // The test deliberately does not invoke similaritySearch: that would
        // exercise the mocked embedding model rather than the fallback gate.
    }

    private GameAppVectorStoreConfig config(GameAppDocumentLoader loader,
                                             LegacyMemoryRagProperties properties) {
        return new GameAppVectorStoreConfig(loader, mock(MyTokenTextSplitter.class),
                mock(MyKeywordEnricher.class), properties);
    }
}
