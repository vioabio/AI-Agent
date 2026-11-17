package com.vio.vioaiagent.agent;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

/** An ordinal addresses a displayed list, never a fixed in-game challenge order. */
public final class GymOrdinalReference {
    private static final List<String> ORDER = List.of("katy", "brassius", "iono", "kofu", "larry", "ryme", "tulip", "grusha");
    private static final List<String> NAMES = List.of("阿枫", "寇沙", "奇树", "海岱", "青木", "莱姆", "莉普", "古鲁夏");
    private static final Pattern ORDINAL = Pattern.compile("第\\s*([0-9]{1,3}|[一二三四五六七八九十]+)\\s*(?:位|个|名)?\\s*(?:道馆馆主|道馆|馆主)");
    private GymOrdinalReference() { }

    public static List<String> catalogOrder() { return ORDER; }

    /** Reconstruct only our canonical numbered list, not arbitrary model-proposed rankings. */
    public static List<String> displayedOrder(List<Message> history) {
        if(history == null) return List.of();
        for(int i=history.size()-1;i>=0;i--) {
            if(!(history.get(i) instanceof AssistantMessage)) continue;
            String text=history.get(i).getText();
            if(!text.startsWith("### 朱／紫馆主资料") || !text.contains("不是固定挑战顺序")
                    || !text.contains("https://www.serebii.net/scarletviolet/")) continue;
            var rows=Pattern.compile("(?m)^\\|\\s*([1-8])\\s*\\|\\s*(阿枫|寇沙|奇树|海岱|青木|莱姆|莉普|古鲁夏)\\s*\\|").matcher(text);
            int count=0;boolean valid=true;
            while(rows.find()) {
                if(count>=ORDER.size() || Integer.parseInt(rows.group(1))!=count+1 || !NAMES.get(count).equals(rows.group(2))) {valid=false;break;}
                count++;
            }
            if(valid && count==ORDER.size()) return ORDER;
        }
        return List.of();
    }

    /** -1 means unsupported/ambiguous ordinal, not an arbitrary selection. */
    public static OptionalInt requestedOrdinal(String question) {
        if (question == null) return OptionalInt.empty();
        if(question.matches("(?s).*第\\s*[0-9一二三四五六七八九十]+\\s*(?:位|个|名)?\\s*(?:和|与|及|或|、)\\s*第.*(?:馆主|道馆).*"))return OptionalInt.of(-1);
        var matcher = ORDINAL.matcher(question.split("以下全部是数据", 2)[0]);
        if (!matcher.find()) matcher = Pattern.compile("第\\s*([0-9]{1,3}|[一二三四五六七八九十]+)\\s*位(?=的|[，,。？?]|$)")
                .matcher(question.split("以下全部是数据", 2)[0]);
        else matcher.reset();
        var ordinals = new HashSet<Integer>();
        var reverse = Pattern.compile("倒数第\\s*([0-9]{1,3}|[一二三四五六七八九十]+)\\s*(?:位|个|名)").matcher(question.split("以下全部是数据", 2)[0]);
        while (reverse.find()) {
            String token = reverse.group(1);
            int value = token.matches("[0-9]+") ? Integer.parseInt(token) : token.length() == 1 ? "一二三四五六七八九十".indexOf(token) + 1 : -1;
            ordinals.add(value > 0 ? -100 - value : -1);
        }
        while (matcher.find()) {
            if (matcher.start() >= 2 && question.substring(matcher.start()-2, matcher.start()).equals("倒数")) continue;
            String token = matcher.group(1);
            int value = token.matches("[0-9]+") ? Integer.parseInt(token) : token.length() == 1 ? "一二三四五六七八九十".indexOf(token) + 1 : -1;
            ordinals.add(value > 0 ? value : -1);
        }
        return ordinals.isEmpty() ? OptionalInt.empty() : OptionalInt.of(ordinals.size() == 1 ? ordinals.iterator().next() : -1);
    }

    public static Optional<String> resolve(String question, List<String> displayedOrder) {
        var ordinal = requestedOrdinal(question);
        if (ordinal.isEmpty() || displayedOrder == null || displayedOrder.isEmpty()
                || displayedOrder.size() > ORDER.size() || new HashSet<>(displayedOrder).size() != displayedOrder.size()
                || !ORDER.containsAll(displayedOrder)) return Optional.empty();
        int position = ordinal.getAsInt() < -100 ? displayedOrder.size() + 1 + ordinal.getAsInt() + 100 : ordinal.getAsInt();
        if (position < 1 || position > displayedOrder.size()) return Optional.empty();
        return Optional.of(displayedOrder.get(position - 1));
    }

    public static String clarifyOrdinal() {
        return "朱／紫的道馆没有固定挑战顺序。当前没有可唯一对应这个序号的前文名单，请告诉我馆主名字，或先让我列出带编号的馆主名单；不会把‘第四位’擅自当成固定馆主。";
    }

    static boolean countQuestion(String question) {
        return question != null && (question.matches("(?s).*(几位|多少位|几个|多少个|几名|多少名|数量|总数).*(馆主|道馆).*")
                || question.matches("(?s).*(馆主|道馆).*(几位|多少位|几个|多少个|几名|多少名|数量|总数).*"));
    }
}
