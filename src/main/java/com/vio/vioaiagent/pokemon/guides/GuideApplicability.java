package com.vio.vioaiagent.pokemon.guides;

import java.util.*;
import java.util.regex.Pattern;

/** Author-declared retrieval scope, not a semantic quality or game-legality judgment. */
public record GuideApplicability(List<String> subjects,List<String> topics) {
    public static final String POLICY="declared-subject-topic-filter-v1";
    private static final Set<String> TOPICS=Set.of("training","team","resource","reference","damage","project");
    public GuideApplicability {
        subjects=subjects==null?List.of():List.copyOf(subjects);
        topics=topics==null?List.of():List.copyOf(topics);
    }
    public void validate() {
        if(subjects.size()>16||topics.isEmpty()||!TOPICS.containsAll(topics)
                ||new HashSet<>(topics).size()!=topics.size()||new HashSet<>(subjects).size()!=subjects.size()
                ||subjects.stream().anyMatch(s->s==null||!s.matches("[a-z][a-z0-9-]{0,79}|[\\p{IsHan}·]{1,30}")))
            throw new IllegalArgumentException("攻略主题或物种范围无效");
    }
    public boolean matchesSubject(String query) {
        String lower=query.toLowerCase(Locale.ROOT);
        return subjects.stream().anyMatch(s->s.matches("[a-z].*")
                ?Pattern.compile("(?<![a-z0-9-])"+Pattern.quote(s)+"(?![a-z0-9-])").matcher(lower).find():query.contains(s));
    }
    public boolean allows(String query) {
        if(!subjects.isEmpty()&&!matchesSubject(query))return false;
        Set<String> requested=requestedTopics(query);
        return requested.isEmpty()||topics.stream().anyMatch(requested::contains);
    }
    private static Set<String> requestedTopics(String query) {
        Set<String> topics=new HashSet<>();
        if(Pattern.compile("培养|养成|性格|努力值|个体值|配招").matcher(query).find())topics.add("training");
        if(Pattern.compile("搭配|配队|组队|阵容|队友|战术|策略|角色|弱点").matcher(query).find())topics.add("team");
        if(Pattern.compile("特性|道具|招式|机制|ability|item|move",Pattern.CASE_INSENSITIVE).matcher(query).find())topics.add("resource");
        if(Pattern.compile("图鉴|物种|形态|编号|资料事实|合法性|规则|来源").matcher(query).find())topics.add("reference");
        if(Pattern.compile("伤害|计算|天气|场地|击倒|胜率|太晶|强化").matcher(query).find())topics.add("damage");
        if(Pattern.compile("本项目|本地项目|保存|预览|确认流程|取消|并发|存储|默认值").matcher(query).find())topics.add("project");
        return topics;
    }
}
