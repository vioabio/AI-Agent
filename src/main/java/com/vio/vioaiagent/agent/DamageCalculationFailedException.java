package com.vio.vioaiagent.agent;

/** Safe public failure; provider messages and tool arguments are never exposed. */
public final class DamageCalculationFailedException extends RuntimeException {
    public DamageCalculationFailedException() {
        super("本次伤害计算失败，未取得有效结果。请检查计算依赖后重试；不使用历史数值补齐。");
    }
}
