package com.vio.vioaiagent.agent;

import org.springframework.ai.chat.messages.*;
import java.util.*;

/** Resolves only an NPC identity/stage from the current conversation, never a player's build from model prose. */
final class GymConversationContext {
    static final List<String> NAMES=List.of("阿枫","寇沙","奇树","海岱","青木","莱姆","莉普","古鲁夏");
    static final List<String> IDS=List.of("katy","brassius","iono","kofu","larry","ryme","tulip","grusha");
    record Target(String gymId,String name,String phase,boolean defaultStage) { }
    private GymConversationContext() { }
    static boolean savedTeamRequest(String text){return text!=null&&text.matches("(?is).*(队伍|队).*")&&text.matches("(?is).*(应对|出招|对付|能否|能不能|能打|分析|打法|如何配置|怎么配置|建议|挑战).*")
            &&text.matches("(?is).*(我的|我当前|当前的|已保存|保存的|看下|看看|读取).*");}
    static Optional<Target> resolve(String question,List<Message> history){
        var current=names(question);if(current.size()>1)return Optional.empty();
        String id=current.isEmpty()?null:current.getFirst();int selectedAt=-1;
        if(id==null)for(int i=history.size()-1;i>=0;i--){var message=history.get(i);if(!(message instanceof UserMessage))continue;var choices=names(message.getText());if(choices.size()>1)return Optional.empty();if(choices.size()==1){id=choices.getFirst();selectedAt=i;break;}}
        if(id==null)for(int i=history.size()-1;i>=0;i--){var message=history.get(i);if(!(message instanceof AssistantMessage)||!message.getText().matches("(?s).*(馆主资料|队伍的应对方向|\\*\\*(?:阿枫|寇沙|奇树|海岱|青木|莱姆|莉普|古鲁夏)\\*\\*).*"))continue;var choices=names(message.getText());if(choices.size()==1){id=choices.getFirst();selectedAt=i;break;}}
        if(id==null)return Optional.empty();
        String phase=phase(question);
        if(phase==null)for(int i=history.size()-1;i>=Math.max(0,selectedAt);i--){var message=history.get(i);var mentioned=names(message.getText());if(!mentioned.isEmpty()&&!mentioned.contains(id))continue;String stage=phase(message.getText());if(stage!=null){phase=stage;break;}}
        boolean defaults=phase==null;if(defaults)phase="initial";
        return Optional.of(new Target(id,NAMES.get(IDS.indexOf(id)),phase,defaults));
    }
    private static List<String> names(String text){if(text==null)return List.of();text=text.split("以下全部是数据",2)[0];var result=new ArrayList<String>();for(int i=0;i<NAMES.size();i++)if(text.contains(NAMES.get(i))||text.toLowerCase(Locale.ROOT).matches("(?s).*\\b"+IDS.get(i)+"\\b.*"))result.add(IDS.get(i));return result;}
    private static String phase(String text){
        if(text==null)return null;
        var heading=java.util.regex.Pattern.compile("(?m)^### [^\\n]*(初战|视察重赛)[^\\n]*$").matcher(text);if(heading.find())return heading.group(1).equals("初战")?"initial":"rematch";
        if(text.contains("初战")&&!text.contains("重赛"))return "initial";
        if(text.contains("重赛")&&!text.contains("初战"))return "rematch";
        return null;
    }
}
