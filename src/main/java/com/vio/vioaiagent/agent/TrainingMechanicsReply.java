package com.vio.vioaiagent.agent;

import java.util.Optional;

/** Bounded explanation of the engine's stat formula, not a new battle calculation. */
public final class TrainingMechanicsReply {
    private TrainingMechanicsReply() { }
    public static Optional<String> reply(String question) {
        if (question == null || question.length() > 500 || question.contains("\n")
                || question.matches("(?s).*(读取|保存|修改|输入：|本地原文|引用|项目说明|当前项目|本项目|这个项目).*")) return Optional.empty();
        boolean comparison=question.contains("种族值") && question.contains("努力值")
                && question.matches("(?s).*(区别|差异|不同|区分|解释|说明|什么|怎么|如何).*" );
        boolean conversion=question.contains("努力值") && question.matches("(?s).*(每4|每四|换算|取整).*" );
        if (!comparison && !conversion) return Optional.empty();
        return Optional.of("种族值是物种或形态的基础参数，不是某只宝可梦在当前等级的实际能力；同一形态通常相同，不因培养而增加。努力值是个体可分配的培养数值，朱／紫单项上限252、总和上限510。\n\n"
                + "实际能力还受等级、个体值、性格与取整影响。努力值先按 floor(EV/4) 折算，再参与等级缩放和后续取整，不能泛称‘每4点努力值一定增加1点实际能力’；50级时有些相邻的4点投入不会改变最终数值。具体配置应按完整公式核对。\n\n"
                + "依据：本地 @smogon/calc 0.12.0 使用的第九世代能力值公式。这是机制说明，本次没有进行伤害计算或修改队伍。");
    }
}
