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
        if(question==null||question.length()>8000)return Optional.empty();
        String id=role==SpecialistRole.DAMAGE_CALCULATION?"damage-verification":role==SpecialistRole.TEAM_PLANNING
            ?question.matches("(?s).*(馆主|道馆).*" )?"gym-challenge":"saved-team-review"
            :question.matches("(?is).*(热门|OU|使用率|竞技|赛事).*" )?"competitive-team-reference"
            :question.matches("(?s).*(培养|努力值|个体值|性格|学习器|养成).*" )?"training-build-review":null;
        return Optional.ofNullable(entries.get(id==null?"":id)).filter(m->m.role().equals(role.name()));
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
