package com.vio.vioaiagent.agent;
import com.vio.vioaiagent.pokemon.reference.MachineReferenceFacts;
import com.vio.vioaiagent.pokemon.reference.MachineCraftingGuide;
import com.vio.vioaiagent.pokemon.reference.MachineRecipeCatalog;
import com.vio.vioaiagent.pokemon.reference.MachineAcquisitionCatalog;
import java.util.*;

/** Source-bound identifiers, not acquisition instructions. */
public final class MachineAnswerEvidence {
    public static final String VERSION="bound-machine-identifiers-with-regions-v4";
    private final Map<String,MachineReferenceFacts.Report> records=new LinkedHashMap<>();
    private boolean attempted;
    public boolean attempted(){return attempted;}
    public static boolean requested(String q){return q!=null&&q.length()<3000&&q.matches("(?is).*(?:学习器(?:编号|号码|配方|材料|获取|位置|地点|解锁)|TM(?:编号|号码|配方|材料|获取|位置|地点|解锁)).*" );}
    public static String instruction(String q){return requested(q)?"\n用户需要学习器编号、配方或获取区域参考。工具开放时使用lookupPokemonMachine核对用户指定的真实成员、招式和当前Run版本；成员不明时先询问，不代猜。只用MATCHED的编号，编号匹配后由程序附固定社区配方与共同大区域参考；来源差异不补造材料或数量。未收录、成员未记录与读取失败分别说明，不补造精确地点、解锁或取得操作。":"";}
    public void observe(String output,String arguments){attempted=true;try{var r=MachineReferenceFacts.bound(output,arguments);if(records.size()<4||records.containsKey(r.pokemonId()+":"+r.moveSlug()))records.put(r.pokemonId()+":"+r.moveSlug(),r);}catch(IllegalArgumentException invalid){records.clear();}}
    public String summary(String q){
        if(records.isEmpty())return attempted||requested(q)?"### 学习器编号\n本次未取得与成员和招式绑定的编号资料，请明确成员及招式后重查；不从模型记忆补造编号。\n\n":"";
        StringBuilder text=new StringBuilder("### 学习器编号（朱／紫参考）\n");
        for(var r:records.values()){
            text.append("\n**").append(EvidenceText.inline(r.moveSlug())).append("**：");
            switch(r.status()){
                case "MATCHED" -> text.append(r.machine().label()).append("，本成员快照记录学习器途径。\n\n来源：[招式](").append(r.machine().moveUrl()).append(") · [学习器](").append(r.machine().machineUrl()).append(") · [道具](").append(r.machine().itemUrl()).append(")\n").append(MachineRecipeCatalog.answer(r.moveSlug())).append(MachineAcquisitionCatalog.answer(r.moveSlug()));
                case "MEMBER_MACHINE_NOT_RECORDED" -> text.append("该成员快照未记录学习器途径，不能据此断言无法学习。\n");
                case "NOT_CATALOGUED" -> text.append("成员有学习器记录，但本批编号资料未收录。\n");
                default -> text.append("成员学习记录未读取成功，本次不提供编号。\n");
            }
        }
        text.append("\n编号来自固定公开资料与成员记录的交叉核对；未核验获取地点的精确位置、游戏内配方与解锁状态、使用或回忆操作、四招共同可得及赛事合法性。本次未修改或保存队伍。\n\n");
        if(records.values().stream().anyMatch(r->"MATCHED".equals(r.status())))text.append(MachineCraftingGuide.answer());
        return text.toString();
    }
}
