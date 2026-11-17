package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Bundled, read-only task knowledge. No user paths, scripts, delegation or authority. */
public final class TaskSkillRegistry {
    public record Metadata(String id,String version,String description,String role,Set<String> allowedTools) {
        public Metadata {
            if(id==null||!id.matches("[a-z][a-z-]{0,60}")||!"1".equals(version)||description==null||description.isBlank()||description.length()>200||allowedTools==null||allowedTools.isEmpty())throw new IllegalArgumentException("Invalid skill metadata");
            SpecialistRole.valueOf(role);allowedTools=Set.copyOf(allowedTools);
        }
    }
    private final Map<String,Metadata> entries;
    public TaskSkillRegistry() {
        try {
            var list=new ObjectMapper().readValue(read("agent-skills/manifest.json",16000),Metadata[].class);
            var map=new LinkedHashMap<String,Metadata>();
            for(var m:list)if(map.putIfAbsent(m.id(),m)!=null)throw new IllegalArgumentException("Duplicate skill");
            if(map.size()>20)throw new IllegalArgumentException("Too many skills");entries=Map.copyOf(map);
        }catch(Exception e){throw new IllegalStateException("SKILL_CATALOG_INVALID");}
    }
    public List<Metadata> metadata(){return entries.values().stream().sorted(Comparator.comparing(Metadata::id)).toList();}
    public Optional<Metadata> select(String question,SpecialistRole role) {
        return select(question,role,null);
    }
    public Optional<Metadata> select(String question,SpecialistRole role,GymTurnPlan plan) {
        if(question==null||question.isBlank()||question.length()>8000||role==null)return Optional.empty();
        String id;
        switch(role){
            case DAMAGE_CALCULATION -> id="damage-verification";
            case TEAM_PLANNING -> {
                boolean boundFollowup=plan!=null&&"pokemon-scarlet-violet".equals(plan.gameReleaseId())&&plan.target().isPresent()
                        &&Set.of(GymTurnPlan.Intent.SAVED_TEAM_ANALYSIS,GymTurnPlan.Intent.RECOMMEND_TEAM,GymTurnPlan.Intent.GENERAL_ADVICE).contains(plan.intent());
                boolean gym=question.matches("(?s).*(馆主|道馆).*")||!GymConversationContext.names(question).isEmpty()||boundFollowup;
                boolean saved=question.matches("(?s).*(我的(?:当前|已保存)?(?:队伍|阵容|配置)|当前(?:的)?队伍|已保存|保存的|队伍ID|teamId|这个队伍|这套阵容).*")
                        ||java.util.regex.Pattern.compile("(?i)\\bmy (?:saved )?team\\b").matcher(question).find();
                id=gym?"gym-challenge":saved?"saved-team-review":"team-core-planning";
            }
            case POKEMON_RESEARCH -> {
                boolean competitive=question.matches("(?s).*(热门|使用率|竞技|赛事).*")
                        ||java.util.regex.Pattern.compile("(?i)(?<![a-z0-9_-])(?:gen9ou|ou)(?![a-z0-9_-])").matcher(question).find();
                if(competitive)id="competitive-team-reference";
                else if(question.matches("(?s).*(培养|努力值|个体值|性格|学习器|养成).*"))id="training-build-review";
                else return Optional.empty();
            }
            default -> {return Optional.empty();}
        }
        return Optional.ofNullable(entries.get(id)).filter(m->m.role().equals(role.name()));
    }
    public String instruction(Metadata m) {
        if(!Objects.equals(entries.get(m.id()),m))throw new IllegalArgumentException("Unregistered skill");
        return new String(read("agent-skills/"+m.id()+"/SKILL.md",6000),StandardCharsets.UTF_8);
    }
    public Set<String> permitted(Metadata m,SpecialistRole role) {
        if(!Objects.equals(entries.get(m.id()),m)||!m.role().equals(role.name()))return Set.of();
        var result=new HashSet<>(role.tools());result.retainAll(m.allowedTools());return Set.copyOf(result);
    }
    private static byte[] read(String name,int limit) {
        try(var in=TaskSkillRegistry.class.getClassLoader().getResourceAsStream(name)) {
            if(in==null)throw new IllegalArgumentException("Missing skill resource");
            var bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw new IllegalArgumentException("Skill too large");return bytes;
        }catch(java.io.IOException e){throw new IllegalStateException("SKILL_READ_FAILED");}
    }
}
