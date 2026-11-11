package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.calculator.TypeEffectivenessCalculator;
import java.util.*;
import java.util.regex.Pattern;

/** Pure type questions are rendered from the local chart, including each dual-type factor. */
public final class TypeMatchupReply {
    private static final String TYPE="一般|火|水|电|草|冰|格斗|毒|地面|飞行|超能力|虫|岩石|幽灵|龙|恶|钢|妖精";
    private static final Pattern ATTACK=Pattern.compile("^(?:请问|请|帮我查一下|帮我查)?("+TYPE+")(?:属性)?(?:招式|攻击)?(?:打|攻击|对)(.+)$");
    private static final Pattern TARGET=Pattern.compile("("+TYPE+")(?:[／/+＋]("+TYPE+"))?(?:双属性|属性)?");
    private TypeMatchupReply() { }
    public static Optional<String> reply(String question) {
        if(question==null||question.length()>400)return Optional.empty();
        String text=question.replaceAll("\\s+","").replaceAll("不考虑特性[、，,]道具和太晶[。]?","");
        var attack=ATTACK.matcher(text);if(!attack.matches())return Optional.empty();
        String targets=attack.group(2).replaceAll("[，,？?。]","");
        targets=targets.replaceFirst("(?:基础)?属性倍率(?:分别)?(?:是多少|为多少|多少)$","");
        targets=targets.replaceFirst("(?:的)?(?:基础)?倍率(?:分别)?(?:是多少|为多少|多少)$","");
        // Consume the entire remaining expression. Mixed strategy/mechanics tasks
        // retain their existing routes instead of being reduced to a type table.
        var groups=targets.split("以及|还有|和|与|及|、",-1);
        if(groups.length==0||groups.length>6)return Optional.empty();
        var defenders=new ArrayList<List<String>>();
        for(String group:groups){
            var target=TARGET.matcher(group);if(!target.matches())return Optional.empty();
            if(group.endsWith("双属性")&&target.group(2)==null)return Optional.empty();
            if(target.group(2)!=null&&target.group(1).equals(target.group(2)))return Optional.empty();
            defenders.add(target.group(2)==null?List.of(target.group(1)):List.of(target.group(1),target.group(2)));
        }
        var calculator=new TypeEffectivenessCalculator();
        var answer=new StringBuilder("### 基础属性倍率\n\n| 攻击属性 | 防御属性 | 逐项倍率 | 最终倍率 |\n| --- | --- | --- | --- |\n");
        for(var defender:defenders){
            var factors=defender.stream().map(type->calculator.calculate(attack.group(1),type)+"×").toList();
            double multiplier=calculator.calculate(attack.group(1),defender.toArray(String[]::new));
            answer.append("| ").append(attack.group(1)).append(" | ").append(String.join("／",defender)).append(" | ")
                .append(String.join(" × ",factors)).append(" | ").append(multiplier).append("× |\n");
        }
        return Optional.of(answer.append("\n双属性按两项倍率相乘，免疫项为0时乘积为0。来源：项目本地第九世代属性表。实际伤害还需招式、能力值及特性、道具、天气、场地和太晶等对局条件。\n").toString());
    }
}
