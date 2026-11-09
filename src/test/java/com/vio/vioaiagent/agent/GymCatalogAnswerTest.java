package com.vio.vioaiagent.agent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GymCatalogAnswerTest {
 @Test void screenshotQuestionsReturnCatalogInsteadOfPlayerChecklist(){
  for(String question:new String[]{"我想了解下朱紫中朱版本的几个道馆馆主的队伍情况","目标馆主有哪些啊"}){
   var answer=GymCatalogAnswer.reply(question,"pokemon-scarlet-violet").orElseThrow();assertTrue(answer.contains("阿枫"));assertTrue(answer.contains("奇树"));assertTrue(answer.contains("古鲁夏"));assertFalse(answer.contains("先确认你的冒险进度"));
  }
  var rosters=GymCatalogAnswer.reply("我想了解下朱紫中朱版本的几个道馆馆主的队伍情况",null).orElseThrow();var leaders=GymCatalogAnswer.reply("目标馆主有哪些啊",null).orElseThrow();assertTrue(rosters.contains("熊宝宝 Lv.15"));assertFalse(leaders.contains("熊宝宝"));assertNotEquals(rosters,leaders);
 }
 @Test void individualFactsDoNotRequireAPlayerPartyOrRematchEligibility(){
  var initial=GymCatalogAnswer.reply("介绍奇树初战的队伍与招式",null).orElseThrow();assertTrue(initial.contains("梦妖魔"));assertTrue(initial.contains("充电光束"));assertFalse(initial.contains("圈圈熊"));
  assertTrue(GymCatalogAnswer.reply("那奇树呢？",null).orElseThrow().contains("充电光束"));
  var rematch=GymCatalogAnswer.reply("还没通关，但想看看阿枫重赛阵容",null).orElseThrow();assertTrue(rematch.contains("圈圈熊"));assertTrue(rematch.contains("66"));assertFalse(rematch.contains("熊宝宝"));assertTrue(rematch.contains("查看资料不代表已解锁对战"));
 }
 @Test void personalizedPlanningAndDamageContinueToUseValidatedPlayerInputs(){
  for(String q:new String[]{"帮我配置队伍打奇树","帮我配个队伍挑战阿枫","推荐一支队伍打阿枫","奇树初战怎么打","请计算阿枫队伍对我的伤害","检查奇树TM048的获取条件"})assertTrue(GymCatalogAnswer.reply(q,null).isEmpty(),q);
  assertTrue(GymCatalogAnswer.reply("你看下我当前的test1队伍能否应对这个馆主，该如何配置出招呢",null).isEmpty());
  assertTrue(GymCatalogAnswer.reply("查询剑盾的馆主队伍",null).isEmpty());assertTrue(GymCatalogAnswer.reply("目标馆主有哪些", "pokemon-sword-shield").isEmpty());
 }
 @Test void initialAndRematchCanBeComparedWithoutMixingTheirRosters(){var answer=GymCatalogAnswer.reply("查看阿枫初战和重赛队伍",null).orElseThrow();assertTrue(answer.contains("熊宝宝"));assertTrue(answer.contains("圈圈熊"));assertEquals(2,answer.split("### 朱／紫馆主资料",-1).length-1);}
}
