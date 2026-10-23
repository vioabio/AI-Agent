package com.vio.vioaiagent.pokemon;

import com.vio.vioaiagent.exception.GlobalExceptionHandler;
import com.vio.vioaiagent.pokemon.reference.*;
import com.vio.vioaiagent.pokemon.engine.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LocalPokemonInputErrorsTest {
    @Test
    void invalidReferenceVersionIsActionableBadRequestRatherThanGenericServerFailure() throws Exception {
        LocalPokeApiRepository repository = mock(LocalPokeApiRepository.class);
        when(repository.detail("squirtle", "invalid")).thenThrow(new IllegalArgumentException("请选择有效资料版本"));
        MockMvcBuilders.standaloneSetup(new PokemonReferenceController(repository))
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/pokemon/reference/pokemon/squirtle").param("dataReleaseId", "invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("请选择有效资料版本"));
    }

    @Test
    void rejectedEngineConfigurationHasClientErrorAndUsefulExplanation() throws Exception {
        LocalDamageEngine engine = mock(LocalDamageEngine.class);
        when(engine.calculate(any())).thenThrow(new IllegalArgumentException("伤害引擎拒绝该配置，请检查英文招式"));
        MockMvcBuilders.standaloneSetup(new DamageEngineController(engine))
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(post("/pokemon/calculations/engine-damage").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("伤害引擎拒绝该配置，请检查英文招式"));
    }
}
