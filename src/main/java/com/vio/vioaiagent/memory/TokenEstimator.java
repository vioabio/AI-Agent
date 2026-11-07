package com.vio.vioaiagent.memory;

/**
 * Token 估算工具 — 粗略估算文本的 Token 数量。
 *
 * <p>英文约 4 字符/token，中文约 1.5 字符/token。
 * 这是近似估算，非精确计算（精确计算需要 tokenizer）。
 *
 * @author vio
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    /** 估算文本的 Token 数量 */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        long chineseChars = text.codePoints()
                .filter(c -> (c >= 0x4E00 && c <= 0x9FFF)
                        || (c >= 0x3400 && c <= 0x4DBF)).count();
        long otherChars = text.length() - chineseChars;
        return (int) (chineseChars / 1.5 + otherChars / 4);
    }

    /** 估算多条文本的总 Token 数 */
    public static int estimateTotal(Iterable<String> texts) {
        int total = 0;
        for (String t : texts) total += estimate(t);
        return total;
    }
}
