package com.vio.vioaiagent.agent;

import java.util.Optional;

/** Bounded explanation of the engine's stat formula, not a new battle calculation. */
public final class TrainingMechanicsReply {
    private TrainingMechanicsReply() { }
    public static Optional<String> reply(String question) {
        if (question == null || question.length() > 500 || question.contains("\n")
                || question.matches("(?s).*(读取|保存|修改|输入：|本地原文|引用|项目说明|当前项目|本项目|这个项目).*")) return Optional.empty();
        String statQuestion = question.strip().replaceFirst("[？?]只解释这个问题[。]?$", "？");
        if (statQuestion.matches("^(?:刚才的?|图鉴(?:中的?|里的?)?)?(?:\\d{1,3}(?:攻击|防御|特攻|特防|速度)(?:种族值)?|(?:攻击|防御|特攻|特防|速度)?种族值(?:\\d{1,3})?)(?:能|可以|能否)(?:直接)?(?:当成|作为|等同于)(?:\\d{1,3}级)?(?:实战|实际)(?:攻击|防御|特攻|特防|速度|能力值?)(?:吗)?[？?。]?$"))
            return Optional.of("图鉴种族值不能直接当作该等级的实际能力。实际数值还由等级、个体值、努力值、性格和取整共同决定，应查看游戏内面板或按完整配置计算。依据为本地伤害引擎的能力值公式。");
        if (!question.contains("{") && !question.contains("```")
                && question.matches("(?is).*(EV|努力值).*") && question.matches("(?s).*(配置|输入).*")
                && question.matches("(?s).*(可以|能否|允许|校验|越界|上限).*") && question.contains("计算")) {
            var total = java.util.regex.Pattern.compile("(?:总和|总计|合计|总努力值)\\s*(?:为|是|[：:=])?\\s*(\\d{1,5})(?!\\d)").matcher(question);
            String invalidTotal = total.find() && Integer.parseInt(total.group(1)) > 510
                    ? "你提供的努力值总计" + total.group(1) + "超过510，需先修正才能用于培养配置和伤害计算。\n\n" : "";
            return Optional.of(invalidTotal + "计算配置的等级必须为1–100；努力值单项为0–252、六项总和不超过510，个体值为0–31。超出这些边界的配置会被项目参数校验拒绝，需要先修正，不能以理论配置为由绕过校验。\n\n"
                    + "符合数值边界后，还需核对物种、招式和双方完整条件才能计算。依据为项目TeamBuild配置校验；本次没有执行伤害计算或修改配置。");
        }
        boolean comparison=question.contains("种族值") && question.contains("努力值")
                && question.matches("(?s).*(区别|差异|不同|区分|解释|说明|什么|怎么|如何).*" );
        boolean conversion=question.contains("努力值") && question.matches("(?s).*(每4|每四|换算|取整).*" );
        if (!comparison && !conversion) return Optional.empty();
        return Optional.of("种族值是物种或形态的基础参数，不是某只宝可梦在当前等级的实际能力；同一形态通常相同，不因培养而增加。努力值是个体可分配的培养数值，朱／紫单项上限252、总和上限510。\n\n"
                + "实际能力还受等级、个体值、性格与取整影响。努力值先按 floor(EV/4) 折算，再参与等级缩放和后续取整，不能泛称‘每4点努力值一定增加1点实际能力’；50级时有些相邻的4点投入不会改变最终数值。具体配置应按完整公式核对。\n\n"
                + "依据：本地 @smogon/calc 0.12.0 使用的第九世代能力值公式。这是机制说明，本次没有进行伤害计算或修改队伍。");
    }
}
