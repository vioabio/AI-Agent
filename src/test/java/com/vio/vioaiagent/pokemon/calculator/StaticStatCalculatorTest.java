package com.vio.vioaiagent.pokemon.calculator;

import com.fasterxml.jackson.databind.*;
import com.vio.vioaiagent.pokemon.domain.TeamBuild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class StaticStatCalculatorTest {
    @TempDir Path directory;
    final StaticStatCalculator calculator=new StaticStatCalculator();
    TeamBuild build(String nature,TeamBuild.Stats evs){return new TeamBuild(List.of(),"","",nature,50,evs,TeamBuild.Stats.filled(31));}
    @Test void knownUnmodifiedSquirtleStatsAndNatureEffects(){
        var base=new TeamBuild.Stats(44,48,65,50,64,43);
        assertEquals(new TeamBuild.Stats(119,68,85,70,84,63),calculator.calculate(base,build("hardy",null),50));
        assertEquals(new TeamBuild.Stats(119,61,85,77,84,63),calculator.calculate(base,build("modest",null),50));
    }
    @Test void oneHpSpecialCaseDoesNotIncreaseWithLevelOrTraining(){
        var base=new TeamBuild.Stats(1,90,45,30,30,40);
        for(int level:List.of(1,50,100))assertEquals(1,calculator.calculate(base,build("adamant",new TeamBuild.Stats(252,0,0,0,0,0)),level).hp());
    }
    @Test void evRemaindersDoNotEnterTheFormula(){
        var base=TeamBuild.Stats.filled(100);
        var none=calculator.calculate(base,build("hardy",TeamBuild.Stats.filled(0)),100);
        assertEquals(none,calculator.calculate(base,build("hardy",TeamBuild.Stats.filled(3)),100));
        assertEquals(none.hp()+1,calculator.calculate(base,build("hardy",new TeamBuild.Stats(4,0,0,0,0,0)),100).hp());
    }
    @Test void invalidBaseAndLevelInputsAreRejected(){
        for(int n:List.of(0,256,Integer.MAX_VALUE))assertThrows(IllegalArgumentException.class,()->calculator.calculate(TeamBuild.Stats.filled(n),TeamBuild.defaults(),50));
        for(int n:List.of(0,101))assertThrows(IllegalArgumentException.class,()->calculator.calculate(TeamBuild.Stats.filled(100),TeamBuild.defaults(),n));
        assertThrows(IllegalArgumentException.class,()->calculator.calculate(null,TeamBuild.defaults(),50));
    }
    @Test void crossChecksAllNaturesAndBoundaryLevelsAgainstPinnedNodeLibrary() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(Path.of("tools/pokemon-engine/node_modules/@smogon/calc/package.json")),"Install the pinned Node engine for the independent stat oracle");
        String[] natures="hardy lonely brave adamant naughty bold docile relaxed impish lax timid hasty serious jolly naive modest mild quiet bashful rash calm gentle sassy careful quirky".split(" ");
        var random=new Random(20261114);var json=new ObjectMapper();var input=json.createArrayNode();var expected=new ArrayList<TeamBuild.Stats>();
        for(String nature:natures)for(int level:List.of(1,5,50,99,100))for(int repeat=0;repeat<2;repeat++){
            int[] b=new int[6],iv=new int[6],ev=new int[6];for(int i=0;i<6;i++){b[i]=random.nextInt(255)+1;iv[i]=random.nextInt(32);}
            b[0]=repeat==0?1:255;int index=random.nextInt(6);ev[index]=252;ev[(index+1)%6]=252;ev[(index+2)%6]=random.nextInt(7);
            var base=new TeamBuild.Stats(b[0],b[1],b[2],b[3],b[4],b[5]);
            var build=new TeamBuild(List.of(),"","",nature,level,new TeamBuild.Stats(ev[0],ev[1],ev[2],ev[3],ev[4],ev[5]),new TeamBuild.Stats(iv[0],iv[1],iv[2],iv[3],iv[4],iv[5]));
            var row=input.addObject();row.set("base",json.valueToTree(base));row.set("build",json.valueToTree(build));row.put("level",level);expected.add(calculator.calculate(base,build,level));
        }
        var output=directory.resolve("oracle.json");var errors=directory.resolve("oracle-errors.txt");
        var process=new ProcessBuilder("node","stat-oracle.cjs").directory(Path.of("tools/pokemon-engine").toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        try{
            try(var stream=process.getOutputStream()){stream.write(json.writeValueAsBytes(input));}
            assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(errors));
            var actual=json.readTree(Files.readString(output));assertEquals(250,actual.size());
            for(int i=0;i<expected.size();i++)assertEquals(json.valueToTree(expected.get(i)),actual.get(i),"oracle vector "+i);
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
}
