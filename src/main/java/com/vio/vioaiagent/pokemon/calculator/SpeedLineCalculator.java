package com.vio.vioaiagent.pokemon.calculator;

/** 速度线纯函数，统一处理等级、能力值、个体值、努力值和性格倍率。 */
public final class SpeedLineCalculator {
    public static final String CALCULATION_VERSION = "speed-gen9-v1";

    public int calculate(int base, int level, int iv, int ev, double natureMultiplier) {
        if (base < 1 || level < 1 || level > 100 || iv < 0 || iv > 31 || ev < 0 || ev > 252
                || natureMultiplier <= 0) throw new IllegalArgumentException("速度参数非法");
        int stat = ((2 * base + iv + ev / 4) * level) / 100 + 5;
        return (int) Math.floor(stat * natureMultiplier);
    }
}
