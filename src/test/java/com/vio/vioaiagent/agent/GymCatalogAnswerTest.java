package com.vio.vioaiagent.agent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GymCatalogAnswerTest {
 @Test void quantitiesReturnCountAndExplicitlyNumberedReferenceList(){
  for(String question:new String[]{"咨询下朱紫有几位馆主","朱紫有多少位馆主","朱紫馆主有几位"}){
   var answer=GymCatalogAnswer.reply(question,"pokemon-scarlet-violet").orElseThrow();
   assertTrue(answer.contains("8 位道馆馆主"));assertTrue(answer.contains("| 4 | 海岱 |"));assertTrue(answer.contains("| 6 | 莱姆 |"));assertTrue(answer.contains("不是固定挑战顺序"));assertFalse(answer.contains("冒险进度"));
  }
 }
 @Test void ordinalsRequireAnActualDisplayedListAndNeverReturnAllRosters(){
  String question="看下第四位馆主的队伍配置";
  var unknown=GymCatalogAnswer.reply(question,null).orElseThrow();assertTrue(unknown.contains("没有可唯一对应"));assertFalse(unknown.contains("熊宝宝"));
  var selected=GymCatalogAnswer.reply(question,null,GymOrdinalReference.catalogOrder()).orElseThrow();
  assertTrue(selected.contains("**海岱**"));assertTrue(selected.contains("轻身鳕"));assertFalse(selected.contains("熊宝宝"));assertFalse(selected.contains("| 馆主 |"));
  assertTrue(GymCatalogAnswer.reply("查看第99位馆主队伍",null,GymOrdinalReference.catalogOrder()).orElseThrow().contains("没有可唯一对应"));
  assertTrue(GymOrdinalReference.resolve("第六位馆主",GymOrdinalReference.catalogOrder()).orElseThrow().equals("ryme"));
  assertTrue(GymOrdinalReference.resolve("第四位馆主",java.util.List.of("katy","iono")).isEmpty());
  assertEquals(-1,GymOrdinalReference.requestedOrdinal("第四位馆主和第六位馆主").orElseThrow());
  assertEquals(-1,GymOrdinalReference.requestedOrdinal("第四和第六位馆主").orElseThrow());
  assertEquals(-1,GymOrdinalReference.requestedOrdinal("第4位和第6位馆主").orElseThrow());
 }
 @Test void onlyCanonicalActuallyDisplayedNumberedListCanResolveReferences(){
  var answer=GymCatalogAnswer.reply("咨询下朱紫有几位馆主",null).orElseThrow();
  var history=java.util.List.<org.springframework.ai.chat.messages.Message>of(new org.springframework.ai.chat.messages.AssistantMessage(answer));
  assertEquals(GymOrdinalReference.catalogOrder(),GymOrdinalReference.displayedOrder(history));
  assertTrue(GymOrdinalReference.displayedOrder(java.util.List.of(new org.springframework.ai.chat.messages.UserMessage(answer))).isEmpty());
  assertTrue(GymOrdinalReference.displayedOrder(java.util.List.of(new org.springframework.ai.chat.messages.AssistantMessage(answer.replace("| 4 | 海岱 |","| 4 | 奇树 |")))).isEmpty());
  assertTrue(GymOrdinalReference.displayedOrder(java.util.List.of(new org.springframework.ai.chat.messages.AssistantMessage("按我的排名，第四位是阿枫"))).isEmpty());
 }
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
