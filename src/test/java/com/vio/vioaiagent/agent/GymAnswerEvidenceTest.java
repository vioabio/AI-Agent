package com.vio.vioaiagent.agent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GymAnswerEvidenceTest {
 @Test void teraComparisonMustIncludeBothSidesAndCannotBeAdvertisedAsDamage(){
  var evidence=new GymAnswerEvidence();evidence.observe("planGymChallenge","""
  {"sourceVersion":"sv-initial-v2","gym":{"name":"寇沙","phase":"initial","battleMode":"Singles","opponents":[{"name":"Sudowoodo","nameZh":"树才怪","level":17,"types":["岩石"],"teraType":"草","ability":"Sturdy","moves":[]}]},"plan":[],"missing":[],"resourceChecks":[],"matchups":[{"pokemon":"喷火龙","move":"喷射火焰","opponent":"Sudowoodo","beforeTera":0.5,"afterTera":2.0}]}
  """);var answer=evidence.answer();assertTrue(answer.contains("太晶前 0.5倍 → 太晶后 2.0倍"));assertTrue(answer.contains("不等于实际伤害倍数变化"));
 }
}
