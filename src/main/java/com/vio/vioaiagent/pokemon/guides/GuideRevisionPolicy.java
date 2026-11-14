package com.vio.vioaiagent.pokemon.guides;

import java.util.Map;

/** Scopes exact legacy project revisions; leaves frozen sources and citations unchanged. */
public final class GuideRevisionPolicy {
    public static final String VERSION="tool-scoped-guide-revisions-v1";
    public static final Map<String,String> LEGACY_SCOPE=Map.of(
            "damage-assumptions-v1","sha256:065a2df65c589c40b9e6c4dd391f2526bbef8660744f804f2d4a5704b892d328",
            "field-settings-and-unsupported-v1","sha256:5a12eb90d90013676a85eab1da25d615d9c0b7266eed5f6254f97523c5f7f99e");
    private GuideRevisionPolicy() { }
    public static boolean usable(LocalGuideSearch.Document document,String query){
        if(document==null)return false;
        if(!LocalGuideSearch.checksum(document).equals(LEGACY_SCOPE.get(document.id())))return true;
        String q=query==null?"":query;
        // The legacy text describes only the generic bridge, not the gym-specific bridge.
        if(q.contains("calculateBattleDamage")&&!q.contains("calculateGymDamage"))return true;
        boolean damage=java.util.regex.Pattern.compile("伤害|计算|calculateBattleDamage|calculateGymDamage").matcher(q).find();
        boolean capability=java.util.regex.Pattern.compile("工具|桥接|接口|接入|条件|当前|支持|calculateGymDamage").matcher(q).find();
        return !(damage&&capability);
    }
}
