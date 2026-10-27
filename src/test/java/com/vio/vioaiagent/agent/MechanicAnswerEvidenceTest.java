package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import static org.junit.jupiter.api.Assertions.*;

class MechanicAnswerEvidenceTest {
    static final String INPUT = "{\"kind\":\"ability\",\"slug\":\"torrent\",\"dataReleaseId\":\"pokeapi-reference-v1\",\"gameReleaseId\":\"pokemon-scarlet-violet\"}";
    String output(String kind, String slug) { return new IndependentMechanicCatalog(new ObjectMapper(), new DefaultResourceLoader()).read(kind, slug, "pokeapi-reference-v1", "pokemon-scarlet-violet").toString(); }
    @Test void showsVerifiedParaphraseSourceAndReviewBoundaryWithoutRawMetadata() {
        var e = new MechanicAnswerEvidence(); e.observe(output("ability", "torrent"), INPUT); String body = e.summary("");
        assertTrue(body.contains("进攻能力乘以1.5")); assertTrue(body.contains("待人工复核")); assertTrue(body.contains("来源：[Pokémon Showdown]"));
        assertFalse(body.contains("sha256:")); assertFalse(body.contains("sourceCommit")); assertFalse(body.contains("descriptionEn"));
    }
    @Test void wrongResourceUnboundInputAndChangedTextCannotEnterAnswer() {
        var e = new MechanicAnswerEvidence(); e.observe(output("ability", "snow-cloak"), INPUT); assertTrue(e.summary("").contains("未取得可核验"));
        e.observe(output("ability", "torrent"), null); assertTrue(e.summary("").contains("未取得可核验"));
        e.observe(output("ability", "torrent").replace("进攻能力乘以1.5", "所有伤害翻倍"), INPUT); assertFalse(e.summary("").contains("所有伤害翻倍"));
    }
    @Test void failureInvalidatesOnlyItsBoundResourceAndNeverPublishesRawErrors() {
        var e = new MechanicAnswerEvidence(); e.observe(output("ability", "torrent"), INPUT);
        String snow = INPUT.replace("torrent", "snow-cloak"); e.observe(output("ability", "snow-cloak"), snow); e.observe("[tool.error] private-source-error", snow);
        assertTrue(e.summary("").contains("激流（特性）")); assertFalse(e.summary("").contains("雪隐（特性）")); assertFalse(e.summary("").contains("private-source-error"));
        e.observe("[tool.error] private-source-error", null); assertFalse(e.summary("").contains("激流（特性）"));
    }
    @Test void explicitDetailedRequestCannotUseTheOverviewShortcutOrUnsupportedModelText() {
        var e = new GuideAnswerEvidence(); e.begin("请核对激流的触发门槛与倍率"); assertTrue(e.active()); assertTrue(e.planningInstruction().contains("lookupPokemonMechanics"));
        assertTrue(e.verifiedAnswer().contains("不能从通用短文或模型记忆补造")); assertFalse(e.overviewReady("请介绍杰尼龟培养与激流的详细机制"));
        assertFalse(MechanicAnswerEvidence.requested("你好，不要说明详细机制")); assertFalse(MechanicAnswerEvidence.requested("火属性打草属性的克制倍率是多少"));
    }
    @Test void combinedLearningAndDetailedMechanicRequestKeepsBothEvidenceInstructionsAndGaps() {
        var e = new GuideAnswerEvidence(); e.begin("查询水之波动的学习途径与触发概率"); String instruction = e.planningInstruction(), answer = e.verifiedAnswer();
        assertTrue(instruction.contains("lookupPokemonLearnset")); assertTrue(instruction.contains("lookupPokemonMechanics")); assertTrue(answer.contains("学习途径资料")); assertTrue(answer.contains("第二来源机制说明"));
    }
}
