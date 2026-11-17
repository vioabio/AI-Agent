package com.vio.vioaiagent.agent;

import org.springframework.ai.chat.messages.*;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded user declarations from this conversation, not facts, permissions or tool arguments. */
public final class TrainingIntentState {
    public static final int MAX_USER_TURNS=64;
    private static final List<String> FIELDS=List.of("游戏","目标","进度","练级","资源");
    private static final List<String> DISPLAY_FIELDS=List.of("游戏","目标","目标等级","进度","练级","资源","徽章","已击败馆主","主线通关","视察任务","所需学习器","已有学习器");
    private static final Pattern ENTRY=Pattern.compile("(?:^|[；;\\n])\\s*(游戏版本|游戏|目标等级|目标|进度|练级|资源|徽章|已击败馆主|主线通关|视察任务|所需学习器|已有学习器)(改为|更新为)?\\s*[：:]\\s*([^；;\\n]+)");
    private record Value(String text,boolean conflict) { }
    private record Declaration(String field,String text,boolean update) { }
    private final Map<String,Value> fields;
    private final boolean limited;
    private TrainingIntentState(Map<String,Value> fields,boolean limited){this.fields=Map.copyOf(fields);this.limited=limited;}
    public static TrainingIntentState resolve(List<Message> history,String current){
        var users=(history==null?List.<Message>of():history).stream().filter(UserMessage.class::isInstance).map(Message::getText).toList();
        var fields=new LinkedHashMap<String,Value>();
        users.stream().skip(Math.max(0,users.size()-MAX_USER_TURNS)).forEach(text->apply(fields,text));
        apply(fields,current);return new TrainingIntentState(fields,users.size()>MAX_USER_TURNS);
    }
    private static void apply(Map<String,Value> fields,String text){
        if(text==null||text.length()>3000||text.contains("```")||text.matches("(?s).*(引用原文|工具返回|以下为资料|以下是资料).*"))return;
        if(resetRequested(text)){fields.clear();return;}
        var declarations=new ArrayList<Declaration>();
        applyNaturalDeclarations(declarations,text);
        var matcher=ENTRY.matcher(text);
        while(matcher.find()){
            String field=matcher.group(1).equals("游戏版本")?"游戏":matcher.group(1);
            String value=matcher.group(3).strip().replaceAll("[。！!]+$","");
            if(value.length()>80||value.chars().anyMatch(Character::isISOControl))continue;
            if(Set.of("未知","待定","未确定","不清楚").contains(value)){capture(declarations,field,null,false);continue;}
            if(field.equals("游戏"))value=switch(value){case "朱","朱版","朱版本","宝可梦朱"->"朱";case "紫","紫版","紫版本","宝可梦紫"->"紫";default->null;};
            if(value==null||value.isBlank()){capture(declarations,field,null,false);continue;}
            capture(declarations,field,value,matcher.group(2)!=null);
        }
        // Scope changes invalidate old declarations before this message supplies new conditions.
        for(var d:declarations)if(d.field().equals("游戏"))declare(fields,d.field(),d.text(),d.update());
        for(var d:declarations)if(!d.field().equals("游戏"))declare(fields,d.field(),d.text(),d.update());
    }
    public static boolean resetRequested(String text){return text!=null&&Set.of("清空培养条件","重新设置培养条件").contains(text.strip());}
    private static void capture(List<Declaration> declarations,String field,String value,boolean update){
        if(field.equals("目标")&&value!=null){
            var gym=Pattern.compile("("+String.join("|",GymConversationContext.NAMES)+")(初战|视察重赛|重赛)").matcher(value.replaceAll("\\s+",""));
            if(gym.matches())value=gym.group(1)+(gym.group(2).equals("初战")?"初战":"重赛");
        }
        declarations.add(new Declaration(field,value,update));
    }
    private static void declare(Map<String,Value> fields,String field,String value,boolean update){
        if(value==null){if(field.equals("游戏"))fields.clear();else fields.remove(field);return;}
        var old=fields.get(field);
        if(field.equals("游戏")&&update&&old!=null&&(old.conflict()||!old.text().equals(value))){fields.clear();old=null;}
        if(update||old==null)fields.put(field,new Value(value,false));
        else if(old.conflict()||!old.text().equals(value))fields.put(field,new Value("",true));
    }
    /** Exact first-person clauses only. This is not general NLP or an authorization extractor. */
    private static void applyNaturalDeclarations(List<Declaration> declarations,String text){
        // Labelled messages keep their original parsing, including commas inside values.
        if(text.matches("(?s).*[：:\"“”‘’{}<>].*")
                ||Pattern.compile("引用|资料|工具|假设|示例|例如|如果|假如|他说|她说|攻略|文档|以下|如下|转述|别人|队友|对手|建议|是否|能否|吗|[？?]").matcher(text).find())return;
        for(String part:text.split("[，,。！!；;\\n]")){
            String clause=part.strip();var game=Pattern.compile("我(?:玩的是|正在玩)(?:宝可梦)?(朱|紫)(?:版|版本)?").matcher(clause);
            if(game.matches()){capture(declarations,"游戏",game.group(1),false);continue;}
            var gameUpdate=Pattern.compile("我把游戏版本改为(?:宝可梦)?(朱|紫)(?:版|版本)?").matcher(clause);
            if(gameUpdate.matches()){capture(declarations,"游戏",gameUpdate.group(1),true);continue;}
            var badges=Pattern.compile("我(?:刚|已经)?拿到([0-8零一二三四五六七八两])枚徽章").matcher(clause);
            if(badges.matches()){
                String raw=badges.group(1);int count=Character.isDigit(raw.charAt(0))?Integer.parseInt(raw):raw.equals("两")?2:"零一二三四五六七八".indexOf(raw);
                capture(declarations,"徽章",String.valueOf(count),false);capture(declarations,"进度",count+"枚徽章",false);continue;
            }
            var target=Pattern.compile("(我的目标是|我把目标改为)(阿枫|寇沙|奇树|海岱|青木|莱姆|莉普|古鲁夏)(初战|视察重赛|重赛)").matcher(clause);
            if(target.matches()){capture(declarations,"目标",target.group(2)+(target.group(3).equals("重赛")?"视察重赛":target.group(3)),target.group(1).equals("我把目标改为"));continue;}
            if(Set.of("我不想练级","我不允许练级","我不练级").contains(clause)){capture(declarations,"练级","不允许",false);continue;}
            if(clause.equals("我允许练级")){capture(declarations,"练级","允许",false);continue;}
            var cap=Pattern.compile("我(?:最多|可以)练到(100|[1-9][0-9]?)级").matcher(clause);
            if(cap.matches()){capture(declarations,"练级","允许到"+cap.group(1)+"级",false);continue;}
            String resource=switch(clause){case "我不使用道具"->"不使用道具";case "我不制作学习器"->"不制作学习器";case "我只用已有学习器","我仅使用已有学习器"->"仅使用已有学习器";default->null;};
            if(resource!=null)capture(declarations,"资源",resource,false);
        }
    }
    /** Normalize only bounded condition-label verbs, never the contents or real build edits. */
    public static String normalizeConditionUpdates(String question){
        if(question==null)return null;
        String normalized=question.replaceAll("(^|[；;])\\s*(游戏版本|游戏|目标等级|目标|进度|练级|资源|徽章|已击败馆主|主线通关|视察任务|所需学习器|已有学习器)改为([：:])","$1$2更新为$3");
        normalized=normalized.replaceAll("(^|[。；;，,])\\s*我把目标改为((?:"+String.join("|",GymConversationContext.NAMES)+")(?:初战|视察重赛|重赛))(?=$|[。；;，,])","$1目标更新为$2");
        return normalized.replaceAll("(^|[。；;，,])\\s*我把游戏版本改为((?:朱|紫)(?:版本|版)?)(?=$|[。；;，,])","$1游戏更新为$2");
    }
    public boolean conflicted(){return fields.values().stream().anyMatch(Value::conflict);}
    public Optional<String> declared(String field){var value=fields.get(field);return value==null||value.conflict()?Optional.empty():Optional.of(value.text());}
    public List<String> missing(){return FIELDS.stream().filter(f->!fields.containsKey(f)).toList();}
    public String describe(){
        var out=new StringBuilder("以下为本会话用户声明，不是游戏内验证事实，也不更改当前对话版本或保存配置。可单独发送“清空培养条件”重新填写。\n\n");
        for(String key:DISPLAY_FIELDS){var value=fields.get(key);if(value==null)continue;
            out.append("- ").append(key).append("：").append(value.conflict()?"前后声明冲突；请用“"+key+"改为：…”明确选择":EvidenceText.inline(value.text())).append("。\n");
        }
        if(!missing().isEmpty())out.append("\n仅需补充尚未声明的条件：").append(String.join("、",missing())).append("。\n");
        else if(!conflicted())out.append("\n上述条件已声明，无需重复提供；实际解锁、库存与成员状态仍需核验。\n");
        if(conflicted())out.append("\n冲突解决前不推导目标等级、训练投入或可执行获取路线。\n");
        if(limited)out.append("\n仅复用最近64条已完成用户消息，较早条件可能未纳入；必要时请重新明确。\n");
        return out.toString();
    }
}
