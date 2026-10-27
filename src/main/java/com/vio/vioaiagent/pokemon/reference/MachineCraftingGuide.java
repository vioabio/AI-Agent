package com.vio.vioaiagent.pokemon.reference;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Project paraphrase of a pinned official page, separate from member learning evidence. */
public final class MachineCraftingGuide {
    public static final String SHA256 = "sha256:026c640972743c5a2187702c18107a8911c963f016cbe32cc8a0366a0403035c";
    public static final String SCHEMA = "pokemon-machine-crafting-guide-v1";
    public static final String SCOPE = "GENERAL_SV_CRAFTING_NOT_SPECIFIC_RECIPE_UNLOCK_OR_LEGALITY";
    public static final String SOURCE_URL = "https://www.pokemon.co.jp/ex/sv/sc/story/220803_03/";

    public record Guide(String schemaVersion, String gameReleaseId, String language, String scope,
                        String checkedAt, String title, String sourceTitle, String sourceUrl,
                        String sourceSection, String sourcePageSha256, String reviewStatus,
                        List<String> facts, String nextCheck, String limitation) {
        public Guide { facts = List.copyOf(facts); }
    }
    private static final class Current {
        static final MachineCraftingGuide VALUE = new MachineCraftingGuide(new DefaultResourceLoader());
    }
    private final Guide guide;
    public MachineCraftingGuide(ResourceLoader loader) {
        try (var stream = loader.getResource("classpath:pokemon-machines/crafting-guide-v1.json").getInputStream()) {
            byte[] bytes = stream.readNBytes(8001);
            if (bytes.length > 8000) throw new IllegalArgumentException("说明过大");
            String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (!SHA256.equals(PokeApiClient.digest(text))) throw new IllegalArgumentException("说明身份不符");
            guide = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(text, Guide.class);
            if (!SCHEMA.equals(guide.schemaVersion()) || !SCOPE.equals(guide.scope())
                    || !SOURCE_URL.equals(guide.sourceUrl()) || guide.facts().size() != 4)
                throw new IllegalArgumentException("说明范围不符");
        } catch (Exception invalid) { throw new IllegalStateException("固定学习器制作说明校验失败", invalid); }
    }
    public static Guide read(String gameReleaseId) {
        if (!"pokemon-scarlet-violet".equals(gameReleaseId)) throw new IllegalArgumentException("制作说明仅支持朱紫");
        return Current.VALUE.guide;
    }
    public static String answer() {
        Guide g = read("pokemon-scarlet-violet");
        StringBuilder text = new StringBuilder("### 通用制作说明（朱／紫）\n\n");
        g.facts().forEach(f -> text.append("- ").append(f).append('\n'));
        return text.append("\n下一步：").append(g.nextCheck()).append("\n\n")
                .append(g.limitation()).append("\n\n来源：[").append(g.sourceTitle()).append("](")
                .append(g.sourceUrl()).append(")，核对日期 ").append(g.checkedAt())
                .append("；以上为项目整理，待人工复核。\n\n").toString();
    }
}
