package com.vio.vioaiagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.beans.factory.ObjectProvider;
import com.vio.vioaiagent.rag.catalog.KnowledgeCatalog;
import com.vio.vioaiagent.rag.domain.VectorIndexRelease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** M7 readiness 必须同时反映应用生命周期和关键依赖健康状态。 */
class ReadyControllerTest {

    @Test
    void optionalAggregateFailureDoesNotOverrideConfiguredReadiness() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
        ObjectProvider<HealthEndpoint> provider = mock(ObjectProvider.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        when(provider.getIfAvailable()).thenReturn(healthEndpoint);
        when(healthEndpoint.health()).thenReturn(Health.down().build());
        when(healthEndpoint.healthForPath("readiness")).thenReturn(Health.up().build());

        assertEquals(200, new ReadyController(availability, provider).ready().getStatusCode().value());
        org.mockito.Mockito.verify(healthEndpoint, org.mockito.Mockito.never()).health();
    }

    @Test
    void missingReadinessGroupFailsClosed() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        ObjectProvider<HealthEndpoint> provider = mock(ObjectProvider.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        when(provider.getIfAvailable()).thenReturn(mock(HealthEndpoint.class));

        assertEquals(503, new ReadyController(availability, provider).ready().getStatusCode().value());
    }

    @Test
    void returnsUnavailableWhenDatabaseHealthIsDown() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
        ObjectProvider<HealthEndpoint> provider = mock(ObjectProvider.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        when(provider.getIfAvailable()).thenReturn(healthEndpoint);
        when(healthEndpoint.healthForPath("readiness")).thenReturn(Health.down().build());

        assertEquals(503, new ReadyController(availability, provider).ready().getStatusCode().value());
    }

    @Test
    void returnsOkWhenLifecycleAndDependenciesAreReady() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
        ObjectProvider<HealthEndpoint> provider = mock(ObjectProvider.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        when(provider.getIfAvailable()).thenReturn(healthEndpoint);
        when(healthEndpoint.healthForPath("readiness")).thenReturn(Health.up().build());

        assertEquals(200, new ReadyController(availability, provider).ready().getStatusCode().value());
    }

    @Test
    void failsClosedWhenHealthContributorThrows() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
        ObjectProvider<HealthEndpoint> provider = mock(ObjectProvider.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        when(provider.getIfAvailable()).thenReturn(healthEndpoint);
        when(healthEndpoint.healthForPath("readiness")).thenThrow(new IllegalStateException("database probe failed"));

        assertEquals(503, new ReadyController(availability, provider).ready().getStatusCode().value());
    }

    @Test
    void detailsKeepsRedactedBlockingReasonsOnFailure() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);

        var response = new ReadyController(availability).details();
        assertEquals(503, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getData());
        assertEquals("BLOCKED", response.getBody().getData().get("status"));
        assertTrue(((java.util.List<?>) response.getBody().getData().get("blockingReasons"))
                .contains("CATALOG_NOT_CONFIGURED"));
    }

    @Test
    void detailsReportsActiveReleasePointerWhenConfigured() {
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        ObjectProvider<HealthEndpoint> healthProvider = mock(ObjectProvider.class);
        ObjectProvider<KnowledgeCatalog> catalogProvider = mock(ObjectProvider.class);
        VectorIndexRelease active = new VectorIndexRelease(
                "index-test", "knowledge-test", "pgvector", "embedding-test", 1536,
                VectorIndexRelease.IndexStatus.ACTIVE, "checksum-test",
                java.time.Instant.now(), java.time.Instant.now());
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        when(healthProvider.getIfAvailable()).thenReturn(healthEndpoint);
        when(catalogProvider.getIfAvailable()).thenReturn(catalog);
        when(healthEndpoint.healthForPath("readiness")).thenReturn(Health.up().build());
        when(catalog.activeIndex()).thenReturn(java.util.Optional.of(active));

        var response = new ReadyController(availability, healthProvider, catalogProvider).details();
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("index-test", response.getBody().getData().get("activeIndexReleaseId"));
        assertEquals("knowledge-test", response.getBody().getData().get("activeKnowledgeReleaseId"));
    }
}
