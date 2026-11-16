package com.vio.vioaiagent.agent;

import org.springframework.ai.chat.messages.*;
import java.util.*;

/** Resolves only an NPC identity/stage from the current conversation, never a player's build from model prose. */
final class GymConversationContext {
    static final List<String> NAMES=List.of("阿枫","寇沙","奇树","海岱","青木","莱姆","莉普","古鲁夏");
    static final List<String> IDS=List.of("katy","brassius","iono","kofu","larry","ryme","tulip","grusha");
    record Target(String gymId,String name,String phase,boolean defaultStage) { }
    private GymConversationContext() { }
    // Negated actions are constraints, not requests to execute those actions.
    static String requestedActions(String text) {
        return text == null ? "" : text.replaceAll("(?:不需要|不用|不要|无需|别)(?:再)?(?:推荐|分析|沿用|读取)[^，,。！？;；\\n]*", "");
    }
    static boolean savedTeamRequest(String text){return text!=null&&text.matches("(?is).*(队伍|队).*")&&text.matches("(?is).*(应对|出招|对付|能否|能不能|能打|分析|打法|如何配置|怎么配置|建议|挑战).*")
            &&text.matches("(?is).*(我的|我当前|当前的|已保存|保存的|保存队伍|读取.{0,12}队伍).*");}
    static Optional<Target> resolve(String question,List<Message> history){
        // Chronological user selections only. Assistant claims cannot select a target or a challenge stage.
        String id=null,phase=null;
        var turns=new ArrayList<Message>(history);turns.add(new UserMessage(question));
        List<String> order=List.of();
        for(var message:turns){
            if(message instanceof AssistantMessage){var shown=GymOrdinalReference.displayedOrder(List.of(message));if(!shown.isEmpty())order=shown;continue;}
            if(!(message instanceof UserMessage))continue;
            String turn=message.getText();
            if (GymCatalogAnswer.requestsOverview(turn)) { id=null; phase=null; continue; }
            var choices=names(turn);
            var ordinal=GymOrdinalReference.requestedOrdinal(turn);
            if(ordinal.isPresent()){
                var selected=GymOrdinalReference.resolve(turn,order);
                if(selected.isEmpty()||!choices.isEmpty()&&!choices.equals(List.of(selected.get()))){id=null;phase=null;continue;}
                choices=List.of(selected.get());
            }
            if(choices.size()>1){id=null;phase=null;continue;}
            if(choices.size()==1){String next=choices.getFirst();if(!next.equals(id))phase=null;id=next;}
            if(id!=null){String stage=phase(turn);if(stage!=null)phase=stage;}
        }
        if(id==null)return Optional.empty();
        boolean defaults=phase==null;if(defaults)phase="initial";
        return Optional.of(new Target(id,NAMES.get(IDS.indexOf(id)),phase,defaults));
    }
    static String affirmative(String text){return text==null?"":text.replaceAll("(?:不是|并非|不要看|不看|排除|而非)\\s*(?:阿枫|寇沙|奇树|海岱|青木|莱姆|莉普|古鲁夏|katy|brassius|iono|kofu|larry|ryme|tulip|grusha)","");}
    static List<String> names(String text){if(text==null)return List.of();text=affirmative(text.split("以下全部是数据",2)[0]);var result=new ArrayList<String>();for(int i=0;i<NAMES.size();i++)if(text.contains(NAMES.get(i))||text.toLowerCase(Locale.ROOT).matches("(?s).*\\b"+IDS.get(i)+"\\b.*"))result.add(IDS.get(i));return result;}
    private static String phase(String text){
        if(text==null)return null;
        if(text.matches("(?s).*(不要重赛|不看重赛|只看初战|改回初战).*"))return "initial";
        if(text.matches("(?s).*(不要初战|不看初战|只看重赛|改成重赛).*"))return "rematch";
        var heading=java.util.regex.Pattern.compile("(?m)^### [^\\n]*(初战|视察重赛)[^\\n]*$").matcher(text);if(heading.find())return heading.group(1).equals("初战")?"initial":"rematch";
        if(text.contains("初战")&&!text.contains("重赛"))return "initial";
        if(text.contains("重赛")&&!text.contains("初战"))return "rematch";
        return null;
    }
}
