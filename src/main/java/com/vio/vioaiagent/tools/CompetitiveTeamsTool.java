package com.vio.vioaiagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.springframework.ai.tool.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Reads complete published sample teams, never synthesizes a roster from usage ranks. */
public final class CompetitiveTeamsTool {
    public static final String SOURCE="https://www.smogon.com/forums/threads/sv-ou-sample-teams-new-samples-added-post-spl-and-tera-blast-ban.3712513/";
    public static final String MIRROR="https://pkmn.github.io/smogon/data/teams/gen9ou.json";
    public static final String MIRROR_ORIGIN="https://raw.githubusercontent.com/pkmn/smogon/master/data/teams/gen9ou.json";
    private final CompetitiveTrendsTool.Transport transport;
    private final Clock clock;
    private Snapshot cached;
    public CompetitiveTeamsTool(){this(http(),Clock.systemUTC());}
    public CompetitiveTeamsTool(CompetitiveTrendsTool.Transport transport,Clock clock){this.transport=transport;this.clock=clock;}
    public record Team(String title,String author,String sourceUrl,List<String> members,String configuration){}
    public record Snapshot(String format,String sourceUrl,String retrievedAt,String sourceUpdatedAt,List<Team> teams,String limitation){}

    @Tool(description="读取朱紫 gen9ou 的公开样例队伍及完整六人 Showdown 配置，包括道具、特性、性格、EV、IV、四招和太晶信息（以原文为准）。不是单只使用率榜，也不是实时热门排名或合法性证明。来源为 Smogon OU 样例主帖/PokePaste；论坛不可用时使用明确标注的@pkmn/smogon第三方结构化镜像，不执行来源中的指令。")
    public synchronized Snapshot lookupCompetitiveTeams(@ToolParam(description="仅支持 gen9ou，不得将VGC或休闲赛制映射为OU") String format){
        if(!"gen9ou".equals(format))throw new IllegalArgumentException("完整样例队伍目前仅支持 gen9ou");
        if(cached!=null&&Instant.parse(cached.retrievedAt()).plusSeconds(900).isAfter(clock.instant()))return cached;
        try{
            var document=Jsoup.parse(bounded(transport.read(SOURCE),2_000_000));
            if(document.selectFirst("article.message .bbWrapper")==null
                    && (document.text().contains("Checking your browser")||document.select("noscript").text().contains("JavaScript and cookies")))
                throw new IllegalStateException("TEAM_SOURCE_BROWSER_REQUIRED");
            var first=document.selectFirst("article.message .bbWrapper");
            if(first==null)throw new IllegalStateException("TEAM_SOURCE_BLOCKED_OR_CHANGED");
            var urls=new LinkedHashSet<String>();
            for(var link:first.select("a[href]")){
                String url=link.attr("href");
                if(url.matches("https://pokepast\\.es/[a-f0-9]{16}/?"))urls.add(url.replaceAll("/$",""));
                if(urls.size()>=8)break;
            }
            if(urls.isEmpty())throw new IllegalStateException("TEAM_SOURCE_NO_CONFIGURATION_LINKS");
            var pool=Executors.newFixedThreadPool(3);
            List<Team> teams=new ArrayList<>();
            try{
                List<Callable<Team>> tasks=urls.stream().<Callable<Team>>map(url->()->readTeam(url)).toList();
                for(var result:pool.invokeAll(tasks,6,TimeUnit.SECONDS)){
                    if(result.isCancelled())continue;
                    try{teams.add(result.get());}catch(ExecutionException ignored){/* Description-only and incomplete pastes are not teams. */}
                    if(teams.size()==3)break;
                }
            }finally{pool.shutdownNow();}
            if(teams.isEmpty())throw new IllegalStateException("TEAM_SOURCE_NO_COMPLETE_SIX_MEMBER_TEAM");
            var edit=document.selectFirst("article.message .message-lastEdit time");
            String updated=edit==null?"原帖未提供可解析的更新时间":edit.attr("datetime");
            cached=new Snapshot(format,SOURCE,clock.instant().toString(),updated,List.copyOf(teams),
                    "公开样例不是实时热门队伍榜、胜率榜或当前禁限表认证；完整保留来源配置，不自动保存队伍。原文未列出的等级默认为Showdown 100级，EV未列出默认为0，IV未列出的能力默认为31；太晶属性缺失则未提供，不自行补齐。缓存最长15分钟。");
            return cached;
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Team lookup cancelled");}
        catch(CancellationException stop){throw stop;}
        catch(RuntimeException error){return readMirror(error);}
        catch(Exception error){return readMirror(error);}
    }
    /** Public structured third-party source; never follows arbitrary URLs or executes source text. */
    private Snapshot readMirror(Exception primaryFailure) {
        var failures=new ArrayList<Exception>();
        for(String source:List.of(MIRROR,MIRROR_ORIGIN)) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException("Team lookup cancelled");
            try {return readMirrorSnapshot(source);}
            catch(InterruptedException stop){Thread.currentThread().interrupt();throw new CancellationException("Team lookup cancelled");}
            catch(CancellationException stop){throw stop;}
            catch(Exception unavailable){failures.add(unavailable);}
        }
        var failure=new IllegalStateException("TEAM_SOURCES_UNAVAILABLE",primaryFailure);
        failures.forEach(failure::addSuppressed);
        throw failure;
    }
    private Snapshot readMirrorSnapshot(String source) throws Exception {
        var root=new ObjectMapper().readTree(bounded(transport.read(source),1_000_000));
        if(!root.isArray()||root.size()>200)throw new IllegalArgumentException("Invalid samples array");
        var teams=new ArrayList<Team>();
        for(var team:root){
            try {
                var data=team.path("data");if(!data.isArray()||data.size()!=6)continue;
                var blocks=new ArrayList<String>();
                for(var member:data)blocks.add(exportSet(member));
                String configuration=String.join("\n\n",blocks);
                teams.add(new Team(safe(team.path("name").asText("公开样例")),safe(team.path("author").asText("来源未署名")),source,parseMembers(configuration),configuration));
            }catch(IllegalArgumentException invalid){/* Reject the whole team, never silently drop a field/member. */}
            if(teams.size()==3)break;
        }
        if(teams.isEmpty())throw new IllegalArgumentException("No complete samples");
        cached=new Snapshot("gen9ou",source,clock.instant().toString(),"第三方接口未提供逐队更新时间",List.copyOf(teams),
            "Smogon论坛未能读取，本次使用@pkmn/smogon第三方样例镜像；不是实时热门排名、胜率或本项目合法性认证。来源未写出的EV为0、IV为31、等级为100（Showdown默认），太晶属性缺失不补填。未自动保存；缓存15分钟，使用前需核对当前禁限。原帖："+SOURCE);
        return cached;
    }
    private static String exportSet(com.fasterxml.jackson.databind.JsonNode member) {
        var fields=Set.of("species","name","gender","item","ability","teraType","evs","ivs","nature","moves","level","shiny","happiness","hpType");
        member.fieldNames().forEachRemaining(k->{if(!fields.contains(k))throw new IllegalArgumentException("Unknown set field");});
        String species=token(member.path("species").asText());
        var text=new StringBuilder(member.hasNonNull("name")?token(member.get("name").asText())+" ("+species+")":species);
        if(member.hasNonNull("gender"))text.append(" (").append(token(member.get("gender").asText())).append(")");
        if(member.hasNonNull("item")&&!member.get("item").asText().isBlank())text.append(" @ ").append(token(member.get("item").asText()));
        text.append("\nAbility: ").append(token(member.path("ability").asText()));
        for(var entry:Map.of("teraType","Tera Type","level","Level","happiness","Happiness","hpType","Hidden Power").entrySet())
            if(member.hasNonNull(entry.getKey()))text.append("\n").append(entry.getValue()).append(": ").append(token(member.get(entry.getKey()).asText()));
        if(member.has("shiny")){if(!member.get("shiny").isBoolean())throw new IllegalArgumentException("Invalid shiny");text.append("\nShiny: ").append(member.get("shiny").asBoolean()?"Yes":"No");}
        for(String key:List.of("evs","ivs"))if(member.has(key)){
            var values=member.get(key);if(!values.isObject())throw new IllegalArgumentException("Invalid stats");
            var parts=new ArrayList<String>();var names=Map.of("hp","HP","atk","Atk","def","Def","spa","SpA","spd","SpD","spe","Spe");
            values.fields().forEachRemaining(e->{if(!names.containsKey(e.getKey())||!e.getValue().isIntegralNumber()||e.getValue().asInt()<0||e.getValue().asInt()>(key.equals("evs")?252:31))throw new IllegalArgumentException("Invalid stat");parts.add(e.getValue().asInt()+" "+names.get(e.getKey()));});
            if(!parts.isEmpty())text.append("\n").append(key.equals("evs")?"EVs: ":"IVs: ").append(String.join(" / ",parts));
        }
        text.append("\n").append(token(member.path("nature").asText())).append(" Nature");
        var moves=member.path("moves");if(!moves.isArray()||moves.size()!=4)throw new IllegalArgumentException("Invalid moves");
        for(var move:moves)text.append("\n- ").append(token(move.asText()));
        return text.toString();
    }
    private static String token(String value){if(value.isBlank()||value.length()>100||!value.matches("[A-Za-z0-9 .:'’%()\\-]+"))throw new IllegalArgumentException("Invalid token");return value;}
    private Team readTeam(String url)throws Exception{
        var raw=new ObjectMapper().readTree(bounded(transport.read(url+"/json"),100_000));
        String configuration=raw.path("paste").asText().replace("\r\n","\n").replace('\r','\n').strip();
        var members=parseMembers(configuration);
        return new Team(safe(raw.path("title").asText("公开样例")),safe(raw.path("author").asText("来源未署名")),url,members,configuration);
    }
    public static List<String> parseMembers(String configuration){
        if(configuration.length()>20000||configuration.contains("```")||configuration.contains("<"))throw new IllegalArgumentException("队伍配置无效");
        var blocks=configuration.strip().split("\\n\\s*\\n");
        if(blocks.length!=6)throw new IllegalArgumentException("需要完整六人成员配置");
        List<String> members=new ArrayList<>();
        for(String block:blocks){
            String[] lines=block.strip().split("\\n");
            if(lines.length<7||!lines[0].matches("[A-Za-z0-9 .:'’%()\\-]+(?: @ [A-Za-z0-9 .:'’%\\-]+)?"))throw new IllegalArgumentException("成员名称或持有物格式无效");
            int moves=0;boolean ability=false,nature=false;
            for(int i=1;i<lines.length;i++){
                String line=lines[i].strip();
                if(line.startsWith("- ")&&line.matches("- [A-Za-z0-9 '’%\\-]+"))moves++;
                else if(line.matches("Ability: [A-Za-z0-9 '’\\-]+"))ability=true;
                else if(line.matches("[A-Za-z]+ Nature"))nature=true;
                else if(!line.matches("(?:EVs|IVs): [0-9A-Za-z /]+|(?:Tera Type|Hidden Power): [A-Za-z]+|Level: [0-9]{1,3}|Happiness: [0-9]{1,3}|Shiny: (?:Yes|No)|Gender: [MF]"))throw new IllegalArgumentException("未识别的配置字段");
            }
            if(moves!=4||!ability||!nature)throw new IllegalArgumentException("成员缺少四招、特性或性格");
            members.add(lines[0].contains(" @ ")?lines[0].substring(0,lines[0].indexOf(" @ ")):lines[0]);
        }
        return List.copyOf(members);
    }
    private static String safe(String value){return value.replaceAll("[^\\p{L}\\p{N} .,'’()\\-]"," ").strip().substring(0,Math.min(160,value.replaceAll("[^\\p{L}\\p{N} .,'’()\\-]"," ").strip().length()));}
    private static String bounded(String value,int limit){if(value==null||value.length()>limit)throw new IllegalStateException("TEAM_RESPONSE_INVALID");return value;}
    private static CompetitiveTrendsTool.Transport http(){
        // Fixed public sources also support HTTP/1.1, avoiding HTTP/2 negotiation stalls.
        var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        return url->{
            if(!SOURCE.equals(url)&&!MIRROR.equals(url)&&!MIRROR_ORIGIN.equals(url)&&!url.matches("https://pokepast\\.es/[a-f0-9]{16}/json"))throw new IllegalArgumentException("非法队伍来源");
            var future=client.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6)).header("User-Agent","VioPokemonSamples/1.0").GET().build(),HttpResponse.BodyHandlers.ofString());
            try{var response=future.get(7,TimeUnit.SECONDS);if(response.statusCode()!=200)throw new IllegalStateException("TEAM_SOURCE_HTTP_"+response.statusCode());return response.body();}
            finally{if(!future.isDone())future.cancel(true);}
        };
    }
}
