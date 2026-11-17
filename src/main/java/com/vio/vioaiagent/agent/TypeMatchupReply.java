package com.vio.vioaiagent.agent;

import com.vio.vioaiagent.pokemon.calculator.TypeEffectivenessCalculator;
import java.util.*;
import java.util.regex.Pattern;

/** Pure type questions are rendered from the local chart, including each dual-type factor. */
public final class TypeMatchupReply {
    private static final String TYPE="一般|火|水|电|草|冰|格斗|毒|地面|飞行|超能力|虫|岩石|幽灵|龙|恶|钢|妖精";
    private static final String ATTACK_TYPE="(?:"+TYPE+")(?:属性|系)?";
    private static final String ATTACK_SEPARATOR="(?:以及|还有|和|与|及|、|[，,])";
    private static final Pattern ATTACK=Pattern.compile("^(?:请问|请|帮我查一下|帮我查)?("+ATTACK_TYPE+"(?:"+ATTACK_SEPARATOR+ATTACK_TYPE+"){0,5})(?:招式|攻击)?(?:分别)?(?:打|攻击|对)(.+)$");
    private static final Pattern TARGET=Pattern.compile("("+TYPE+")(?:[／/+＋]("+TYPE+"))?(?:双属性|属性)?");
    private TypeMatchupReply() { }
    public static Optional<String> reply(String question) {
        if(question==null||question.length()>400)return Optional.empty();
        String text=question.replaceAll("\\s+","").replaceAll("不考虑特性[、，,]道具和太晶[。]?","");
        text=text.replaceFirst("请(?:调用|查询|使用|核对)(?:本地)?属性(?:工具|表)(?:后)?(?:用中文)?(?:简短)?回答[。]?$", "");
        text=text.replaceFirst("^(?:在)?基础属性关系下[，,]?", "");
        text=text.replaceFirst("^不考虑特性[、，,]?道具[、，,]?太晶[，,]?", "");
        text=text.replace("招式攻击", "招式打").replaceAll("超能(?!力)","超能力");
        var attack=ATTACK.matcher(text);if(!attack.matches())return Optional.empty();
        var attacks=Arrays.stream(attack.group(1).split(ATTACK_SEPARATOR))
                .map(type->type.replaceFirst("(?:属性|系)$", "")).distinct().toList();
        String expression = attack.group(2);
        String unit = "(?:" + TYPE + ")(?:[／/+＋](?:" + TYPE + "))?(?:双属性|属性)?";
        var structured = Pattern.compile("^(" + unit + "(?:(?:以及|还有|和|与|及|、)" + unit + ")*)(.*)$").matcher(expression);
        if (structured.matches()) {
            String suffix = structured.group(2);
            String mathWords = "对(?:" + TYPE + ")|目标|宝可梦|基础|各项|最终|分别|每一步|多少|怎么|是多少|倍率|乘积|乘法|过程|相乘|抵抗|免疫|还是|写清楚|写出|逐项|请把|只看|属性表|结果|和|与|各|都|的|时|是|倍|乘";
            if (suffix.matches("(?s).*(乘|逐项|抵抗|免疫).*")
                    && suffix.replaceAll("(?:" + mathWords + ")", "").matches("[，,、。？?：:；;\\s]*"))
                expression = structured.group(1);
        }
        String targets=expression.replaceFirst("(?:的)?(?:基础)?(?:属性)?(?:倍率)?(?:分别|各自|各)?(?:是多少|为多少|多少|是几|几)倍?(?:伤害)?[？?。]?$", "").replaceAll("[，,？?。]","");
        targets=targets.replaceFirst("(?:基础)?属性倍率(?:分别|各自|各)?(?:是多少|为多少|多少)$","");
        targets=targets.replaceFirst("(?:的)?(?:基础)?倍率(?:分别|各自|各)?(?:是多少|为多少|多少)$","");
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
        for(String attackType:attacks){
            for(var defender:defenders){
                var factors=defender.stream().map(type->calculator.calculate(attackType,type)).toList();
                double multiplier=factors.stream().reduce(1d,(left,right)->left*right);
                var factorLabels=factors.stream().map(value->value+"×").toList();
                answer.append("| ").append(attackType).append(" | ").append(String.join("／",defender)).append(" | ")
                    .append(String.join(" × ",factorLabels)).append(" | ").append(multiplier).append("× |\n");
            }
        }
        return Optional.of(answer.append("\n双属性按两项倍率相乘，免疫项为0时乘积为0。来源：项目本地第九世代属性表。实际伤害还需招式、能力值及特性、道具、天气、场地和太晶等对局条件。\n").toString());
    }
}
