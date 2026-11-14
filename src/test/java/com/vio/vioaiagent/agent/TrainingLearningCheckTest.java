package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.reference.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrainingLearningCheckTest {
    static BuildCheckFacts.Move move(List<PokemonLearnsetFacts.Entry> entries){
        var raw=new PokeApiClient.Snapshot("fixture",PokeApiClient.digest("fixture"),Instant.parse("2026-11-14T00:00:00Z"),"https://pokeapi.co/api/v2/pokemon/7/");
        return new BuildCheckFacts.Move(1,"surf","surf","RECORDED",PokemonLearnsetFacts.create(7,"squirtle","surf",entries,raw));
    }
    String answer(String conditions,int level,PokemonLearnsetFacts.Entry... entries){
        return String.join("\n",TrainingLearningCheck.describe(move(List.of(entries)),level,TrainingIntentState.resolve(List.of(),conditions)));
    }
    static PokemonLearnsetFacts.Entry level(int n){return new PokemonLearnsetFacts.Entry("level-up",n,null);}
    @Test void prohibitedLevelingBlocksOnlyRecordedUpgradePathAndKeepsAlternativesUnverified(){
        String text=answer("练级：不允许",50,level(55),new PokemonLearnsetFacts.Entry("machine",0,null));
        assertTrue(text.contains("此升级途径不符合该限制"));assertTrue(text.contains("另有学习器记录"));
        assertTrue(text.contains("不能自动视作可用替代途径"));assertFalse(text.contains("无法学习"));
    }
    @Test void targetAndCapAreDistinctAndBothChecked(){
        String text=answer("目标等级：53；练级：允许到52级",50,level(55));
        assertTrue(text.contains("超过声明练级上限 52"));assertTrue(text.contains("高于声明目标等级 53"));
        assertTrue(text.contains("不等于不存在其他途径或无法学习"));
    }
    @Test void fulfilledThresholdIsNotProofOfKnowingMoveOrRecall(){
        String text=answer("练级：不允许",55,level(55));
        assertTrue(text.contains("不能证明已学会或可直接回忆"));assertFalse(text.contains("此升级途径不符合"));
    }
    @Test void zeroUnknownAndWithinLimitNeverBecomeExecutionPermission(){
        assertEquals("",answer("练级：允许",50,level(0)));
        for(String policy:List.of("允许","允许到60级","随便","允许到60级以上")){
            String text=answer("练级："+policy,50,level(55));
            assertTrue(text.contains("未证明当前可以执行训练"),policy);
        }
    }
    @Test void conflictsAndInvalidLevelsDoNotProduceThresholdConclusions(){
        var state=TrainingIntentState.resolve(List.of(new UserMessage("练级：允许")),"练级：不允许");
        assertEquals(List.of("声明条件有冲突，暂不判断学习等级与训练条件是否相容。"),TrainingLearningCheck.describe(move(List.of(level(55))),50,state));
        for(Integer n:Arrays.asList(null,0,101))assertTrue(TrainingLearningCheck.describe(move(List.of(level(55))),n,TrainingIntentState.resolve(List.of(),null)).getFirst().contains("没有有效保存等级"));
    }
    @Test void boundedDistinctLevelsKeepOmissionVisible(){
        var entries=new ArrayList<PokemonLearnsetFacts.Entry>();
        for(int i=51;i<=55;i++)entries.add(level(i));entries.add(new PokemonLearnsetFacts.Entry("level-up",51,1));
        String text=String.join("\n",TrainingLearningCheck.describe(move(entries),50,TrainingIntentState.resolve(List.of(),"练级：不允许")));
        assertEquals(3,text.split("学习条件核对：",-1).length-1);assertTrue(text.contains("另有 2 个升级等级记录未展开"));
    }
    @Test void unavailableMismatchedTamperedAndCancelledRecordsNeverProduceAdvice(){
        var state=TrainingIntentState.resolve(List.of(),"练级：不允许");var m=move(List.of(level(55)));
        assertTrue(TrainingLearningCheck.describe(new BuildCheckFacts.Move(1,"surf","surf","REFERENCE_UNAVAILABLE",null),50,state).isEmpty());
        assertThrows(IllegalArgumentException.class,()->TrainingLearningCheck.describe(new BuildCheckFacts.Move(1,"protect","protect","RECORDED",m.learning()),50,state));
        var l=m.learning();var changed=new PokemonLearnsetFacts(l.schemaVersion(),l.pokemonId(),l.pokemonSlug(),l.moveSlug(),l.dataReleaseId(),l.gameReleaseId(),l.versionGroup(),l.status(),List.of(level(99)),l.sourceUrl(),l.sourceChecksum(),l.retrievedAt(),l.projectionChecksum());
        assertThrows(IllegalArgumentException.class,()->TrainingLearningCheck.describe(new BuildCheckFacts.Move(1,"surf","surf","RECORDED",changed),50,state));
        Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->TrainingLearningCheck.describe(m,50,state));}finally{Thread.interrupted();}
    }
}
