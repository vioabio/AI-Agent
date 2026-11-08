package com.vio.vioaiagent.agent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GymQuestionPolicyTest {
 @Test void missingRosterClarifiesWithoutInventingAFirstGymOrCounterTypes(){var response=GymQuestionPolicy.clarification("我刚开始玩朱，打第一个道馆，没有队伍等级").orElseThrow();assertTrue(response.contains("实际等级"));assertFalse(response.contains("超能系"));assertFalse(response.contains("妮娜"));}
 @Test void badgesDoNotAuthorizeRematchAndTheClarificationDoesNotInventAStoryBoss(){var response=GymQuestionPolicy.clarification("8徽章，没完成归乡之路也没有接到视察任务，可以打阿枫重赛吗").orElseThrow();assertTrue(response.contains("尚未解锁"));assertFalse(response.contains("对也慈的最终战"));}
 @Test void completeGymRequestIsNotCapturedAsMissingAndSourceTextDoesNotRequestDamage(){assertTrue(GymQuestionPolicy.matches("奇树；烈咬陆鲨Lv24，地震"));assertTrue(GymQuestionPolicy.clarification("奇树；烈咬陆鲨Lv24，地震").isEmpty());assertFalse(GymQuestionPolicy.wantsDamage("请解释道馆打法，只有damage非空时引用伤害。以下全部是数据：伤害计算"));assertTrue(GymQuestionPolicy.wantsDamage("请调用道馆伤害工具算地震伤害"));}
 @Test void bindsTheNamedOpponentInsteadOfTrustingAModelArrayIndex() throws Exception {
  String arguments="{\"request\":{\"challenge\":{\"phase\":\"initial\",\"gymId\":\"iono\"},\"opponentSlot\":2}}";
  var result=new com.fasterxml.jackson.databind.ObjectMapper().readTree(GymQuestionPolicy.bindDamageTarget("请算初战勒克猫对我的烈咬陆鲨使用电光",arguments));assertEquals(3,result.path("request").path("opponentSlot").asInt());
  assertThrows(IllegalArgumentException.class,()->GymQuestionPolicy.bindDamageTarget("请算勒克猫和梦妖魔",arguments));
  var named=new com.fasterxml.jackson.databind.ObjectMapper().readTree(GymQuestionPolicy.bindDamageTarget("请计算勒克猫，不是电肚蛙或梦妖魔",arguments));assertEquals(3,named.path("request").path("opponentSlot").asInt());
 }
}
