package com.vio.vioaiagent.agent;

import java.util.Set;
import java.util.regex.Pattern;

/** Shared interpretation of explicit declarations; never inferred in-game progress. */
public record TrainingLevelConstraints(Integer targetLevel,Integer cap,Permission permission,boolean conflicted) {
    public enum Permission { UNKNOWN, ALLOWED, PROHIBITED }
    private static final Pattern CAP=Pattern.compile("允许(?:练级)?到(100|[1-9][0-9]?)级");
    public static TrainingLevelConstraints from(TrainingIntentState intent){
        String raw=intent.declared("目标等级").orElse("");
        Integer target=raw.matches("(?:100|[1-9][0-9]?)(?:级)?")?Integer.valueOf(raw.replace("级","")):null;
        String policy=intent.declared("练级").orElse("");var match=CAP.matcher(policy);
        Integer cap=match.matches()?Integer.valueOf(match.group(1)):null;
        Permission permission=Set.of("不允许","不允许练级","禁止练级","不练级","不能练级").contains(policy)?Permission.PROHIBITED
                :cap!=null||Set.of("允许","允许练级").contains(policy)?Permission.ALLOWED:Permission.UNKNOWN;
        return new TrainingLevelConstraints(target,cap,permission,intent.conflicted());
    }
    public boolean permitsProjectionFrom(int savedLevel){
        return !conflicted&&permission==Permission.ALLOWED&&targetLevel!=null&&targetLevel>savedLevel&&(cap==null||targetLevel<=cap);
    }
}
