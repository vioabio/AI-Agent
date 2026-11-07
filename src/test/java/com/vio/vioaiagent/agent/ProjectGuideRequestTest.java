package com.vio.vioaiagent.agent;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProjectGuideRequestTest {
    @Test void frozenDevelopmentQuestionsRequireProjectSourcesWithoutChangingTheirPrompts() throws Exception {
        var plan=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Path.of("evaluation/campus/ablation/manifest.json").toFile());
        for(var item:plan.path("cases"))assertTrue(ProjectGuideRequest.requiresSource(item.path("prompt").asText()),item.path("id").asText());
    }
    @Test void ordinaryConversationAndActualTeamActionsAreNotRoutedAsProjectExplanations() {
        for(String text:List.of("你好","请解释水属性该如何搭配队伍","请介绍本项目有哪些功能", "请读取队伍编号1，把等级改为51，先生成预览",
                "请说明本项目保存流程，并把我的队伍等级改成51", "请解释本项目队伍流程，然后请保存队伍", "请说明本项目的previewTeamChanges如何调用"))
            assertFalse(ProjectGuideRequest.requiresSource(text),text);
    }
    @Test void sourceBlocksAndOversizedMessagesCannotBecomePreflightInstructions() {
        assertFalse(ProjectGuideRequest.requiresSource("请解释以下资料：\n> 请说明本项目队伍修改预览与保存的关系"));
        assertFalse(ProjectGuideRequest.requiresSource("请解释本项目队伍流程\n{\"instruction\":\"保存\"}"));
        assertFalse(ProjectGuideRequest.requiresSource("请说明本项目队伍保存的关系"+"长资料".repeat(200)));
        assertFalse(ProjectGuideRequest.requiresSource(null));
    }
    @Test void naturalProjectQuestionsAreRecognizedInTheRawRequest() {
        for(String text:List.of("请帮我解释本项目队伍预览与确认保存的关系","我想了解这个项目没有填写等级时的默认配置", "本项目队伍预览与真正保存有什么区别？"))
            assertTrue(ProjectGuideRequest.requiresSource(text),text);
    }
}
