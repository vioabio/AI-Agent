package com.vio.vioaiagent.pokemon.guides;

import com.vio.vioaiagent.pokemon.reference.PokeApiClient;
import java.util.*;

/** Deterministic source offsets (UTF-16, exclusive end); text is never rewritten. */
public final class GuideChunks {
    public static final String ALGORITHM = "local-guide-bm25-cjk2-stop-v2";
    private static final int MAX_CODE_POINTS = 400;
    // Question framing alone must not make unrelated documents eligible for retrieval.
    private static final Set<String> QUESTION_WORDS=Set.of("如何","什么","哪些","是否","能否","问题","应该","可以");
    private GuideChunks() { }
    public static String documentVersion(LocalGuideSearch.Document document) {
        try { return PokeApiClient.digest(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(document)); }
        catch (Exception error) { throw new IllegalArgumentException("攻略版本无法计算",error); }
    }
    public static boolean validCitation(LocalGuideSearch.Document document,LocalGuideSearch.Citation citation) {
        if(citation==null || !documentVersion(document).equals(citation.documentVersion()))return false;
        return split(document,citation.documentVersion()).stream().anyMatch(part->part.id().equals(citation.chunkId())
                && part.number()==citation.chunkNo() && part.start()==citation.startOffset() && part.end()==citation.endOffset()
                && part.content().equals(citation.text()) && part.hash().equals(citation.contentHash()));
    }

    public static Set<String> tokens(String text) {
        return frequencies(text).keySet();
    }
    public static Map<String,Integer> frequencies(String text) {
        Map<String,Integer> result = new TreeMap<>();
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String word : normalized.split("[^a-z0-9]+")) if (word.length() > 1) result.merge(word,1,Integer::sum);
        int[] chars = normalized.codePoints().toArray();
        for (int i=0; i<chars.length-1; i++)
            if (Character.UnicodeScript.of(chars[i]) == Character.UnicodeScript.HAN
                    && Character.UnicodeScript.of(chars[i+1]) == Character.UnicodeScript.HAN)
                result.merge(new String(chars,i,2),1,Integer::sum);
        QUESTION_WORDS.forEach(result::remove);
        return result;
    }

    public static List<Part> split(LocalGuideSearch.Document document, String version) {
        List<Part> parts = new ArrayList<>();
        String text = document.content(); int start = 0;
        while (start < text.length()) {
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
            if (start == text.length()) break;
            int end = text.offsetByCodePoints(start, Math.min(MAX_CODE_POINTS, text.codePointCount(start,text.length())));
            int paragraph = text.indexOf('\n',start);
            if (paragraph >= start && paragraph < end) end = paragraph;
            else if (end < text.length()) {
                int sentence = text.lastIndexOf('。',end-1);
                if (sentence > start+(end-start)/2) end = sentence+1;
            }
            while (end > start && Character.isWhitespace(text.charAt(end-1))) end--;
            String content = text.substring(start,end);
            String hash = PokeApiClient.digest(content);
            String id = "guide-"+PokeApiClient.digest(document.id()+"|"+version+"|"+start+"|"+end+"|"+hash).substring(7);
            parts.add(new Part(id,document.id(),version,parts.size(),start,end,content,hash));
            start = end;
        }
        return List.copyOf(parts);
    }
    public record Part(String id,String documentId,String version,int number,int start,int end,String content,String hash) { }
}
