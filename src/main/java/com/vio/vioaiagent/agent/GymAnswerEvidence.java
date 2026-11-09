package com.vio.vioaiagent.agent;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Publishes sourced gym facts and computed candidates; arbitrary model prose cannot acquire source status. */
final class GymAnswerEvidence {
    private JsonNode report;
    private boolean attempted;
    void observe(String name,String output){
        if(!Set.of("planGymChallenge","calculateGymDamage").contains(name))return;
        attempted=true;
        if(!name.equals("planGymChallenge")||output==null||output.length()>131072||output.startsWith("[tool."))return;
        try{var value=new ObjectMapper().readTree(output);if(value.path("gym").path("opponents").isArray()&&value.path("plan").isArray()&&value.path("sourceVersion").asText().matches("sv-(initial|rematch)-v2.*"))report=value;}catch(Exception ignored){ }
    }
    boolean complete(){return report!=null;}
    boolean attempted(){return attempted;}
    String adviceOnly(){String full=answer();int start=full.indexOf("### 当前配置的建议");return start<0?full:full.substring(start);}
    String answer(){
        if(!complete())return "本次尚未取得有效道馆分析，暂不提供馆主阵容或具体打法。请在道馆挑战面板选择阶段、目标馆主和真实队伍，并确认进度后重试；失败时不会把模型记忆当作已核实资料。";
        var gym=report.path("gym");var text=new StringBuilder("### 馆主资料 · ").append(safe(gym.path("name").asText())).append(gym.path("phase").asText().equals("rematch")?"（视察重赛）":"（初战）").append("\n")
                .append(gym.path("battleMode").asText().equals("Doubles")?"双打":"单打").append("；资料中的排列不保证AI固定出招顺序。\n\n| 成员 | 等级 | 属性 / 太晶 | 特性 | 已核实招式 |\n| --- | --- | --- | --- | --- |\n");
        for(var p:gym.path("opponents"))text.append("| ").append(safe(p.path("nameZh").asText(p.path("name").asText()))).append(" | ").append(p.path("level").asInt()).append(" | ").append(join(p.path("types"))).append(p.hasNonNull("teraType")?" → "+safe(p.path("teraType").asText()):"").append(" | ").append(safe(p.path("abilityZh").asText(p.path("ability").asText()))).append(" | ").append(join(p.has("movesZh")?p.path("movesZh"):p.path("moves"))).append(" |\n");
        text.append("\n### 当前配置的建议\n以下是已填招式的属性覆盖与等级差，不是完整伤害或胜率。\n");for(var p:report.path("plan"))text.append("- ").append(safe(p.asText())).append('\n');
        int comparisons=0;
        for(var match:report.path("matchups")){
            if(match.path("beforeTera").asDouble()==match.path("afterTera").asDouble())continue;
            if(comparisons++==8){text.append("- 其他已填招式的前后对比可在道馆伤害面板逐项核对。\n");break;}
            if(comparisons==1)text.append("\n### 太晶前后属性效果\n这里只比较防御属性变化，不等于实际伤害倍数变化。\n");
            String foe=match.path("opponent").asText();for(var p:gym.path("opponents"))if(p.path("name").asText().equals(foe))foe=p.path("nameZh").asText(foe);
            text.append("- ").append(safe(match.path("pokemon").asText())).append("的").append(safe(match.path("move").asText())).append(" → ").append(safe(foe)).append("：太晶前 ").append(match.path("beforeTera").asDouble()).append("倍 → 太晶后 ").append(match.path("afterTera").asDouble()).append("倍。\n");
        }
        if(!report.path("resourceChecks").isEmpty()){text.append("\n### 资源获取条件\n");for(var r:report.path("resourceChecks"))text.append("- ").append(safe(r.path("name").asText())).append("：").append(safe(r.path("reason").asText())).append('\n');}
        text.append("\n### 仍需确认\n");for(var p:report.path("missing"))text.append("- ").append(safe(p.asText())).append('\n');
        String source=gym.path("phase").asText().equals("rematch")?"gymrematches":"gyms";
        text.append("\n来源：[Serebii馆主资料](https://www.serebii.net/scarletviolet/").append(source).append(".shtml)。本次没有保存或修改你的队伍。\n");
        return text.toString();
    }
    private static String join(JsonNode values){var list=new ArrayList<String>();values.forEach(v->list.add(safe(v.asText())));return String.join(" / ",list);}
    private static String safe(String text){return text.replaceAll("[|\\r\\n<>`]"," ");}
}
