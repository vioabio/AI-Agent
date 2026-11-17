package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.guides.LocalGuideSearch;
import com.vio.vioaiagent.pokemon.guides.GuideChunks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatMeaningQuestionTest {
    @TempDir Path directory;

    @Test void conceptualQuestionsUseOnlyGuideTools() {
        var guide = tool("searchPokemonGuides");
        var species = tool("lookupPokemonReference");
        var calculation = tool("calculateBattleDamage");
        for (String question : List.of("大嘴鸥的种族值能否直接说明我这只的实际速度？",
                "种族值等于实际能力值吗", "为什么基础数值不能直接代表实战先手？")) {
            assertTrue(GuideAnswerEvidence.statMeaningQuestion(question), question);
            assertArrayEquals(new ToolCallback[]{guide}, GuideAnswerEvidence.statMeaningTools(question,
                    new ToolCallback[]{guide, species, calculation}));
            var evidence = new GuideAnswerEvidence();
            evidence.begin(question);
            assertTrue(evidence.active());
            assertTrue(evidence.planningInstruction().contains("无需查询任何物种"));
        }
    }

    @Test void explicitLookupsCalculationsAndMixedTasksKeepTheirTools() {
        ToolCallback[] tools = {tool("lookupPokemonReference"), tool("calculateBattleDamage")};
        for (String question : List.of("查询大嘴鸥的种族值和实际速度", "计算50级大嘴鸥的实际速度",
                "比较两只宝可梦的种族值和实际能力值", "种族值是否代表实际能力，推荐队伍",
                "大嘴鸥的种族值是多少", "种族值是否等于实际能力值\n以下资料：{}")) {
            assertFalse(GuideAnswerEvidence.statMeaningQuestion(question), question);
            assertSame(tools, GuideAnswerEvidence.statMeaningTools(question, tools));
        }
        assertEquals(0, GuideAnswerEvidence.statMeaningTools("种族值是否等于实际能力值", tools).length);
    }

    @Test void actualGuideExplainsTheDifferenceWithoutAnySpeciesSnapshot() throws Exception {
        var json = new ObjectMapper();
        var search = new LocalGuideSearch(json, new DefaultResourceLoader(), "classpath:pokemon-guides/expanded-v1.json",
                directory.resolve("guides.sqlite").toString());
        var evidence = new GuideAnswerEvidence();
        evidence.begin("大嘴鸥的种族值能否直接说明我这只的实际速度？");
        evidence.observe("searchPokemonGuides", json.writeValueAsString(search.search(
                "种族值 实际配置 填写默认值", "pokemon-scarlet-violet")));
        String answer = evidence.verifiedAnswer();
        assertTrue(answer.contains("种族值、实际配置与填写默认值"));
        assertTrue(answer.contains("不能用其中一种数值冒充另一种"));
        assertFalse(answer.contains("图鉴速览"));
        assertFalse(answer.contains("奇鲁莉安"));
        assertTrue(answer.contains("原文范围：完整文档"));
    }

    @Test void defaultValueChunkStillIncludesTheActualStatExplanationFromItsValidatedDocument() throws Exception {
        var json = new ObjectMapper();
        var search = new LocalGuideSearch(json, new DefaultResourceLoader(), "classpath:pokemon-guides/expanded-v1.json",
                directory.resolve("guide-defaults.sqlite").toString());
        var hit = search.search("种族值 实际配置 填写默认值", "pokemon-scarlet-violet").stream()
                .filter(row -> row.document().id().equals("build-values-and-defaults-v1")).findFirst().orElseThrow();
        String version = GuideChunks.documentVersion(hit.document());
        var part = GuideChunks.split(hit.document(), version).get(1);
        var citation = new LocalGuideSearch.Citation(part.id(), version, part.number(), part.start(), part.end(), part.content(), part.hash());
        var evidence = new GuideAnswerEvidence();
        evidence.begin("种族值能否代表实际能力值？");
        evidence.observe("searchPokemonGuides", json.writeValueAsString(List.of(new LocalGuideSearch.Hit(hit.document(), hit.score(), hit.checksum(), citation))));
        String answer = evidence.verifiedAnswer();
        assertTrue(answer.contains("原文范围：完整文档"));
        assertTrue(answer.contains("不能标成50级或100级的实际能力"));
        assertTrue(answer.contains("不能用其中一种数值冒充另一种"));
    }

    private ToolCallback tool(String name) {
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name(name).description(name).inputSchema("{}").build());
        return callback;
    }
}
