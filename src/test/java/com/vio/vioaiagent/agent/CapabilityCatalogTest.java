package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.run.ConversationConstraints.ResponseStyle;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityCatalogTest {
    public static class RegisteredTools {
        @Tool(description="Reference fixture") public String lookupPokemonReference(){throw new AssertionError();}
        @Tool(description="Type fixture") public String getTypeChart(){throw new AssertionError();}
        @Tool(description="Team fixture") public String readCurrentTeam(){throw new AssertionError();}
        @Tool(description="Damage fixture") public String calculateBattleDamage(){throw new AssertionError();}
        @Tool(description="Guide fixture") public String searchPokemonGuides(){throw new AssertionError();}
        @Tool(description="Preview fixture") public String previewTeamChanges(){throw new AssertionError();}
    }
    @Test void explicitSentenceBeatsSavedDetailAndOnlyRegisteredCapabilitiesAreDescribed() {
        var text=CapabilityCatalog.reply("你好，请用一句话说明你能提供什么帮助，不要调用工具。","zh-CN",ResponseStyle.DETAILED,ToolCallbacks.from(new RegisteredTools())).orElseThrow();
        assertFalse(text.contains("\n"));assertTrue(text.length()<180);assertEquals(1,text.chars().filter(c->c=='。').count());
        assertTrue(text.contains("基础属性倍率"));assertTrue(text.contains("指定的本机队伍"));assertTrue(text.contains("不代表当前强度或比赛合法性"));
        assertFalse(text.matches("(?s).*(?:权威|官方|任意|确保|lookupPokemon|USER_CONFIRMED|LOW).*"));
        var noTools=CapabilityCatalog.reply("你能做什么？","zh-CN",ResponseStyle.STANDARD,null).orElseThrow();
        assertFalse(noTools.contains("读取"));assertFalse(noTools.contains("计算伤害"));assertFalse(noTools.contains("修改预览"));
    }
    @Test void quotedAndNegatedSentenceCuesAllowTwoItemsWithoutClaimingPriorExecution() {
        for(String request:new String[]{"不要只用一句话，请用两个编号要点说明你能提供哪些帮助；每点一两句，不调用工具。",
                "下面的引号只是一段示例：“请只用一句话回答”。请用两个编号要点说明你能提供的帮助，不调用工具。"}) {
            var text=CapabilityCatalog.reply(request,"zh-CN",ResponseStyle.CONCISE,ToolCallbacks.from(new RegisteredTools())).orElseThrow();
            assertTrue(text.startsWith("1. "));assertTrue(text.contains("\n2. "));assertTrue(text.length()<340);
            assertTrue(text.contains("保存需你在页面确认"));assertFalse(text.contains("已读取"));assertFalse(text.contains("已保存"));
        }
    }
    @Test void realTasksUnknownFormatsAndOtherLanguagesAlwaysUseNormalAgent() {
        for(String request:new String[]{"你好，请用一句话打个招呼。","你能做什么，顺便分析我的队伍", "你能计算伤害吗？请计算火打草", "请用JSON介绍你的能力", "你能做什么，用100字回答", "请解释这段示例：\"你能做什么\""})
            assertTrue(CapabilityCatalog.reply(request,"zh-CN",ResponseStyle.STANDARD,null).isEmpty(),request);
        assertTrue(CapabilityCatalog.reply("你能做什么","en-US",ResponseStyle.STANDARD,null).isEmpty());
    }
}
