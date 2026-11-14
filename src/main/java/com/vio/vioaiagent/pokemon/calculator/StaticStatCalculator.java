package com.vio.vioaiagent.pokemon.calculator;

import com.vio.vioaiagent.pokemon.domain.TeamBuild;

/** Gen 9 unmodified stats only; parity-tested against the locked @smogon/calc dependency. */
public final class StaticStatCalculator {
    public static final String VERSION="gen9-static-stats-v1";
    // Rows increase atk/def/spe/spa/spd, columns decrease the same stat; diagonal is neutral.
    private static final String[][] NATURES={
            {"hardy","lonely","brave","adamant","naughty"},
            {"bold","docile","relaxed","impish","lax"},
            {"timid","hasty","serious","jolly","naive"},
            {"modest","mild","quiet","bashful","rash"},
            {"calm","gentle","sassy","careful","quirky"}};
    private static final int[] INDEX={1,2,5,3,4};
    public TeamBuild.Stats calculate(TeamBuild.Stats bases,TeamBuild build,int level){
        if(bases==null||build==null||level<1||level>100)throw new IllegalArgumentException("Invalid static stat inputs");
        int[] base=values(bases),ev=values(build.evs()),iv=values(build.ivs()),result=new int[6];
        int plus=-1,minus=-1;
        for(int i=0;i<5;i++)for(int j=0;j<5;j++)if(NATURES[i][j].equals(build.nature())&&i!=j){plus=INDEX[i];minus=INDEX[j];}
        for(int i=0;i<6;i++){
            if(base[i]<1||base[i]>255)throw new IllegalArgumentException("Base stat out of range");
            int core=(2*base[i]+iv[i]+ev[i]/4)*level/100;
            result[i]=i==0?(base[i]==1?1:core+level+10):(core+5)*(i==plus?110:i==minus?90:100)/100;
        }
        return new TeamBuild.Stats(result[0],result[1],result[2],result[3],result[4],result[5]);
    }
    private static int[] values(TeamBuild.Stats s){return new int[]{s.hp(),s.atk(),s.def(),s.spa(),s.spd(),s.spe()};}
}
