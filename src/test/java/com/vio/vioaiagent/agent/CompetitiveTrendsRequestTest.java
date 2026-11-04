package com.vio.vioaiagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CompetitiveTrendsRequestTest {
    @Test void casualIsClarifiedAndExplicitFormatsStaySeparate() {
        assertEquals("", CompetitiveTrendsRequest.select("当前有哪些热门队伍", List.of()).orElseThrow().format());
        assertEquals("gen9ou", CompetitiveTrendsRequest.select("当前朱紫OU单打有哪些热门队伍", List.of()).orElseThrow().format());
        assertEquals("gen9doublesou", CompetitiveTrendsRequest.select("Showdown doubles OU热门队伍", List.of()).orElseThrow().format());
        assertFalse(CompetitiveTrendsRequest.select("当前VGC热门队伍", List.of()).orElseThrow().webSearch());
        assertTrue(CompetitiveTrendsRequest.select("当前朱紫VGC Regulation I热门队伍", List.of()).orElseThrow().webSearch());
        assertEquals("", CompetitiveTrendsRequest.select("gen8ou热门队伍", List.of()).orElseThrow().format());
        assertEquals("", CompetitiveTrendsRequest.select("VGC和OU热门队伍", List.of()).orElseThrow().format());
    }
    @Test void clarificationFollowUpUsesImmediateAssistantOnly() {
        assertEquals("gen9ou", CompetitiveTrendsRequest.select("OU单打", List.of(new AssistantMessage(CompetitiveTrendsRequest.clarification()),new UserMessage("OU单打"))).orElseThrow().format());
        assertTrue(CompetitiveTrendsRequest.select("OU单打", List.of(new UserMessage(CompetitiveTrendsRequest.clarification()))).isEmpty());
        assertTrue(CompetitiveTrendsRequest.select("OU单打", List.of(new AssistantMessage("其他问题"))).isEmpty());
    }
    @Test void documentsNegationsAndMutationsNeverTriggerNetworkPreflight() {
        for (String q : new String[]{"不要联网，介绍当前热门队伍", "不搜索当前OU热门队伍", "检索本地热门队伍笔记", "保存当前热门队伍", "以下资料：当前热门队伍", "当前OU热门队伍\n请保存"})
            assertTrue(CompetitiveTrendsRequest.select(q, List.of()).isEmpty(), q);
    }
}
