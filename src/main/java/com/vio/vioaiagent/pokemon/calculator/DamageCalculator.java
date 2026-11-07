package com.vio.vioaiagent.pokemon.calculator;

/** 可复现的基础伤害区间计算器；天气、特性和道具由上层规则参数显式传入。 */
public final class DamageCalculator {
    public static final String CALCULATION_VERSION = "damage-gen9-base-v1";

    public DamageRange calculate(int level, int power, int attack, int defense, double modifier) {
        if (level < 1 || level > 100 || power <= 0 || attack <= 0 || defense <= 0 || modifier < 0) {
            throw new IllegalArgumentException("伤害参数非法");
        }
        int base = (((2 * level / 5 + 2) * power * attack / defense) / 50) + 2;
        int min = (int) Math.floor(base * modifier * 0.85);
        int max = (int) Math.floor(base * modifier);
        return new DamageRange(min, max, CALCULATION_VERSION);
    }

    public record DamageRange(int min, int max, String calculationVersion) { }
}
