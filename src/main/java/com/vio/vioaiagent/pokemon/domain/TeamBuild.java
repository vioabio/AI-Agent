package com.vio.vioaiagent.pokemon.domain;

import java.util.List;
import java.util.Set;

/** A user's battle assumptions; storing a set does not assert that it is legal in a competition. */
public record TeamBuild(List<String> moves, String ability, String item, String nature,
                        Integer level, Stats evs, Stats ivs, @org.springframework.ai.tool.annotation.ToolParam(required=false)
                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String teraType) {
    public TeamBuild(List<String> moves,String ability,String item,String nature,Integer level,Stats evs,Stats ivs) {
        this(moves,ability,item,nature,level,evs,ivs,null);
    }
    private static final Set<String> NATURES = Set.of("hardy","lonely","brave","adamant","naughty","bold","docile","relaxed","impish","lax","timid","hasty","serious","jolly","naive","modest","mild","quiet","bashful","rash","calm","gentle","sassy","careful","quirky");
    public TeamBuild {
        teraType=teraType==null||teraType.isBlank()?null:teraType.strip().toLowerCase(java.util.Locale.ROOT);
        if(teraType!=null&&!Set.of("normal","fire","water","electric","grass","ice","fighting","poison","ground","flying","psychic","bug","rock","ghost","dragon","dark","steel","fairy","stellar").contains(teraType)) throw new IllegalArgumentException("未知太晶属性");
        moves = moves == null ? List.of() : moves.stream().map(TeamBuild::name).toList();
        if (moves.size() > 4 || moves.stream().map(v->v.toLowerCase(java.util.Locale.ROOT).replace('-', ' ')).distinct().count()!=moves.size()) throw new IllegalArgumentException("招式最多四个且不能重复");
        ability = optionalName(ability); item = optionalName(item);
        nature = nature == null || nature.isBlank() ? "hardy" : nature.trim().toLowerCase(java.util.Locale.ROOT);
        if (!NATURES.contains(nature)) throw new IllegalArgumentException("未知英文性格名称");
        level = level == null ? 50 : level;
        if (level < 1 || level > 100) throw new IllegalArgumentException("等级必须为 1..100");
        evs = evs == null ? Stats.filled(0) : evs;
        ivs = ivs == null ? Stats.filled(31) : ivs;
        evs.requireRange(252); ivs.requireRange(31);
        if (evs.total() > 510) throw new IllegalArgumentException("努力值总和不能超过 510");
    }
    private static String name(String value) {
        if (value == null || value.isBlank() || value.length() > 80 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("配置名称长度必须为 1..80，不能包含控制字符");
        return value.trim();
    }
    private static String optionalName(String value) { return value == null || value.isBlank() ? "" : name(value); }
    public static TeamBuild defaults() { return new TeamBuild(List.of(),"","","hardy",50,null,null); }
    public record Stats(int hp, int atk, int def, int spa, int spd, int spe) {
        public static Stats filled(int value) { return new Stats(value,value,value,value,value,value); }
        public int total() { return hp+atk+def+spa+spd+spe; }
        public void requireRange(int maximum) {
            if (java.util.stream.IntStream.of(hp,atk,def,spa,spd,spe).anyMatch(v -> v<0 || v>maximum)) throw new IllegalArgumentException("个体值或努力值超出范围");
        }
    }
}
