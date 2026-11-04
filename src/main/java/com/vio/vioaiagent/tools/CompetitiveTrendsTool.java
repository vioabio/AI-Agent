package com.vio.vioaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Fixed-origin, current published monthly statistics. No API key or arbitrary URL fetch. */
public final class CompetitiveTrendsTool {
    public static final String ORIGIN = "https://www.smogon.com/stats/";
    private static final Set<String> FORMATS = Set.of("gen9ou", "gen9doublesou", "gen9monotype");
    private final Transport transport;
    private final Clock clock;
    private final Map<String, Snapshot> cache = new HashMap<>();
    @FunctionalInterface public interface Transport { String read(String url) throws Exception; }
    public record Usage(int rank, String pokemon, String usagePercent) { }
    public record Snapshot(String format, String month, String rating, long battles, String retrievedAt,
                           String sourceUrl, List<Usage> entries, String limitation) { }
    public CompetitiveTrendsTool() { this(httpTransport(), Clock.systemUTC()); }
    public CompetitiveTrendsTool(Transport transport, Clock clock) { this.transport = transport; this.clock = clock; }

    @Tool(description="联网读取 Smogon 最新已发布月份的第九世代 Pokémon Showdown 使用率，无需搜索Key。仅支持 gen9ou、gen9doublesou、gen9monotype。返回月份、样本对战数、全部分段口径和来源；不是当日排名、胜率、完整六人队伍或官方VGC统计。")
    public synchronized Snapshot lookupCompetitiveTrends(
            @ToolParam(description="必须明确赛制：gen9ou、gen9doublesou 或 gen9monotype；不能把休闲单打或VGC映射为OU") String format) {
        if (!FORMATS.contains(format)) throw new IllegalArgumentException("请明确支持的 Showdown 赛制，不能将休闲或VGC当作OU");
        Snapshot existing = cache.get(format);
        if (existing != null && Instant.parse(existing.retrievedAt()).plusSeconds(900).isAfter(clock.instant())) return existing;
        try {
            String index = bounded(transport.read(ORIGIN));
            var months = Pattern.compile("href=[\"'](20\\d{2}-(?:0[1-9]|1[0-2]))/[\"']").matcher(index);
            var published = new TreeSet<String>();
            while (months.find()) if (!YearMonth.parse(months.group(1)).isAfter(YearMonth.now(clock))) published.add(months.group(1));
            if (published.isEmpty()) throw new IllegalStateException("TRENDS_NO_PUBLISHED_MONTH");
            String month = published.last();
            String url = ORIGIN + month + "/" + format + "-0.txt";
            String body = bounded(transport.read(url));
            var count = Pattern.compile("(?m)^Total battles:\\s*(\\d+)").matcher(body);
            if (!count.find()) throw new IllegalStateException("TRENDS_INVALID_STATISTICS");
            long battles = Long.parseLong(count.group(1));
            var rows = Pattern.compile("(?m)^\\|\\s*(\\d+)\\s*\\|\\s*([A-Za-z0-9 .:'’%\\-]+?)\\s*\\|\\s*(\\d+(?:\\.\\d+)?)%\\s*\\|").matcher(body);
            List<Usage> entries = new ArrayList<>();
            while (rows.find() && entries.size() < 10) {
                int rank = Integer.parseInt(rows.group(1));
                double percent = Double.parseDouble(rows.group(3));
                if (rank != entries.size() + 1 || percent < 0 || percent > 100) throw new IllegalStateException("TRENDS_INVALID_RANK");
                entries.add(new Usage(rank, rows.group(2).strip(), rows.group(3)));
            }
            if (entries.isEmpty() || battles <= 0) throw new IllegalStateException("TRENDS_EMPTY_STATISTICS");
            Snapshot result = new Snapshot(format, month, "0（全部分段）", battles, clock.instant().toString(), url,
                    List.copyOf(entries), "月度单只宝可梦使用率，不是胜率、实时完整队伍榜或VGC；不能将前六名拼成热门队伍，其他游戏和赛制不能混用。");
            cache.put(format, result);
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("TRENDS_INTERRUPTED");
        } catch (IllegalStateException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("TRENDS_UNAVAILABLE: 在线统计暂不可达，请稍后重试"); }
    }
    private static String bounded(String text) {
        if (text == null || text.length() > 2_000_000) throw new IllegalStateException("TRENDS_INVALID_RESPONSE");
        return text;
    }
    private static Transport httpTransport() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        return url -> {
            if (!url.startsWith(ORIGIN)) throw new IllegalArgumentException("非法统计来源");
            var pending = client.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(7))
                    .header("User-Agent", "VioPokemonTrends/1.0").GET().build(), HttpResponse.BodyHandlers.ofString());
            try {
                var response = pending.get(8, TimeUnit.SECONDS);
                if (response.statusCode() != 200) throw new IllegalStateException("TRENDS_PROVIDER_HTTP_" + response.statusCode());
                return response.body();
            } finally { if (!pending.isDone()) pending.cancel(true); }
        };
    }
}
