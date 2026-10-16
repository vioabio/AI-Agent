package com.vio.vioaiagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Spring binding must fail startup rather than silently admit with invalid limits. */
class RunAdmissionPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AdmissionConfiguration.class);

    @Test
    void defaultsProvideFiniteCapacityAndQueueDeadline() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RunAdmissionProperties.class);
            RunAdmissionProperties properties = context.getBean(RunAdmissionProperties.class);
            assertThat(properties.threadCount()).isEqualTo(8);
            assertThat(properties.queueCapacity()).isEqualTo(16);
            assertThat(properties.maxInFlight()).isEqualTo(24);
            assertThat(properties.maxPerPrincipal()).isEqualTo(4);
            assertThat(properties.maxPerTenant()).isEqualTo(16);
            assertThat(properties.queueWaitMs()).isEqualTo(30_000);
            assertThat(properties.retryAfterSeconds()).isEqualTo(1);
        });
    }

    @Test
    void explicitConfigurationBindsEveryAdmissionSetting() {
        runner.withPropertyValues("vio.run.admission.thread-count=3",
                "vio.run.admission.queue-capacity=5",
                "vio.run.admission.max-per-principal=2",
                "vio.run.admission.max-per-tenant=6",
                "vio.run.admission.queue-wait-ms=1250",
                "vio.run.admission.retry-after-seconds=9").run(context -> {
            assertThat(context).hasNotFailed();
            RunAdmissionProperties properties = context.getBean(RunAdmissionProperties.class);
            assertThat(properties.threadCount()).isEqualTo(3);
            assertThat(properties.queueCapacity()).isEqualTo(5);
            assertThat(properties.maxInFlight()).isEqualTo(8);
            assertThat(properties.maxPerPrincipal()).isEqualTo(2);
            assertThat(properties.maxPerTenant()).isEqualTo(6);
            assertThat(properties.queueWaitMs()).isEqualTo(1250);
            assertThat(properties.retryAfterSeconds()).isEqualTo(9);
        });
    }

    @Test
    void zeroQueueAndMinimumSettingsAreValid() {
        runner.withPropertyValues("vio.run.admission.thread-count=1",
                "vio.run.admission.queue-capacity=0",
                "vio.run.admission.max-per-principal=1",
                "vio.run.admission.max-per-tenant=1",
                "vio.run.admission.queue-wait-ms=1",
                "vio.run.admission.retry-after-seconds=1").run(context -> {
            assertThat(context).hasNotFailed();
            RunAdmissionProperties properties = context.getBean(RunAdmissionProperties.class);
            assertThat(properties.queueCapacity()).isZero();
            assertThat(properties.maxInFlight()).isEqualTo(1);
            assertThat(properties.queueWaitMs()).isEqualTo(1);
        });
    }

    @Test
    void maximumSupportedSettingsKeepCapacityBounded() {
        runner.withPropertyValues("vio.run.admission.thread-count=256",
                "vio.run.admission.queue-capacity=10000",
                "vio.run.admission.max-per-principal=10256",
                "vio.run.admission.max-per-tenant=10256",
                "vio.run.admission.queue-wait-ms=3600000",
                "vio.run.admission.retry-after-seconds=3600").run(context -> {
            assertThat(context).hasNotFailed();
            RunAdmissionProperties properties = context.getBean(RunAdmissionProperties.class);
            assertThat(properties.maxInFlight()).isEqualTo(10_256);
            assertThat(properties.maxPerPrincipal()).isEqualTo(10_256);
            assertThat(properties.maxPerTenant()).isEqualTo(10_256);
            assertThat(properties.queueWaitMs()).isEqualTo(3_600_000);
            assertThat(properties.retryAfterSeconds()).isEqualTo(3600);
        });
    }

    @ParameterizedTest(name = "{0}={1} must reject startup")
    @CsvSource({"thread-count, 0", "thread-count, 257", "queue-capacity, -1",
            "queue-capacity, 10001", "max-per-principal, 0", "max-per-principal, 10257",
            "max-per-tenant, 0", "max-per-tenant, 10257", "queue-wait-ms, 0",
            "queue-wait-ms, 3600001", "retry-after-seconds, 0", "retry-after-seconds, 3601"})
    void outOfRangeSettingsFailStartup(String property, String value) {
        runner.withPropertyValues("vio.run.admission." + property + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"thread-count=unbounded", "queue-capacity=1.5",
            "queue-wait-ms=9223372036854775808", "retry-after-seconds=forever"})
    void malformedSettingsFailStartup(String setting) {
        runner.withPropertyValues("vio.run.admission." + setting).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isNotNull();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RunAdmissionProperties.class)
    static class AdmissionConfiguration { }
}
