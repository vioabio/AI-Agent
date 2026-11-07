package com.vio.vioaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.LocalPokeApiRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class LocalReferenceDefaultsTest {
    @TempDir Path directory;

    private ApplicationContextRunner runner(String profiles) throws Exception {
        // Copy only tracked defaults; never load the developer's private application-local.yml.
        Path defaults = directory.resolve("defaults.yml");
        try (var input = new ClassPathResource("application.yml").getInputStream()) {
            Files.copy(input, defaults);
        }
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=" + defaults.toUri(),
                        "spring.profiles.active=" + profiles,
                        "vio.pokemon.reference.path=" + directory.resolve("cache"))
                .withUserConfiguration(ReferenceConfiguration.class);
    }

    @Test void localReferenceWorksWithoutDeepseekProfile() throws Exception {
        runner("local").run(context -> {
            assertThat(context).hasSingleBean(LocalPokeApiRepository.class);
            assertThat(context.getBean(LocalPokeApiRepository.class)
                    .findDataRelease(LocalPokeApiRepository.RELEASE)).isPresent();
        });
    }

    @Test void explicitDisableIsRespected() throws Exception {
        runner("local").withPropertyValues("vio.pokemon.reference.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LocalPokeApiRepository.class));
    }

    @Test void productionCannotEnableLocalReferenceThroughLocalProfile() throws Exception {
        runner("local,prod").run(context -> assertThat(context).doesNotHaveBean(LocalPokeApiRepository.class));
    }

    @Configuration(proxyBeanMethods=false)
    @Import(LocalPokeApiRepository.class)
    static class ReferenceConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
