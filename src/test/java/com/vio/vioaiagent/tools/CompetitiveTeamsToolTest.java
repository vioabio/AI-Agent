package com.vio.vioaiagent.tools;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class CompetitiveTeamsToolTest {
    public static String configuration(){
        return java.util.stream.IntStream.range(0,6).mapToObj(i->"Pokemon"+i+" @ Leftovers\nAbility: Pressure\nTera Type: Water\nEVs: 252 HP / 4 Def / 252 SpD\nCareful Nature\n- Protect\n- Toxic\n- Rest\n- Sleep Talk").collect(java.util.stream.Collectors.joining("\n\n"));
    }
    public static String index(){return "<article class='message'><div class='bbWrapper'><a href='https://pokepast.es/0123456789abcdef'>team</a></div></article>";}
    public static String paste(){try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("title","Fixture balance","author","fixture author","paste",configuration()));}catch(Exception e){throw new AssertionError(e);}}
    @Test void recognizesBrowserChallengeEvenWithHttpSuccess() {
        var tool=new CompetitiveTeamsTool(url->"<p>Checking your browser...</p><noscript>JavaScript and cookies are required</noscript>",Clock.systemUTC());
        assertEquals("TEAM_SOURCES_UNAVAILABLE",assertThrows(IllegalStateException.class,()->tool.lookupCompetitiveTeams("gen9ou")).getMessage());
    }
    @Test void publicMirrorProvidesFullTeamsWhenForumRequiresBrowser() throws Exception {
        String fixture=new String(getClass().getResourceAsStream("/pokemon-teams-mirror-fixture.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        var tool=new CompetitiveTeamsTool(url->url.equals(CompetitiveTeamsTool.MIRROR)?fixture:"Checking your browser",Clock.systemUTC());
        var result=tool.lookupCompetitiveTeams("gen9ou");
        assertEquals(CompetitiveTeamsTool.MIRROR,result.sourceUrl());assertEquals(6,result.teams().getFirst().members().size());
        assertTrue(result.limitation().contains("第三方"));assertTrue(result.teams().getFirst().configuration().contains("IVs: 0 Atk"));
        assertTrue(result.teams().getFirst().configuration().contains("Hidden Power: Fighting"));
        assertEquals(result,tool.lookupCompetitiveTeams("gen9ou"));
        var bad=new CompetitiveTeamsTool(url->url.equals(CompetitiveTeamsTool.MIRROR)?fixture.replace("\"species\":", "\"unexpected\": \"secret\", \"species\":"):"blocked",Clock.systemUTC());
        assertThrows(IllegalStateException.class,()->bad.lookupCompetitiveTeams("gen9ou"));
    }
    @Test void returnsSixMemberConfigurationWithProvenanceAndCachesOnlySuccess(){
        var calls=new AtomicInteger();var tool=new CompetitiveTeamsTool(url->{calls.incrementAndGet();return url.equals(CompetitiveTeamsTool.SOURCE)?index():paste();},Clock.systemUTC());
        var snapshot=tool.lookupCompetitiveTeams("gen9ou");assertEquals(6,snapshot.teams().getFirst().members().size());
        assertEquals(configuration(),snapshot.teams().getFirst().configuration());assertTrue(snapshot.teams().getFirst().sourceUrl().startsWith("https://pokepast.es/"));
        assertEquals(snapshot,tool.lookupCompetitiveTeams("gen9ou"));assertEquals(2,calls.get());
        assertThrows(IllegalArgumentException.class,()->tool.lookupCompetitiveTeams("vgc"));
    }
    @Test void browserChallengesHistoricalPostsAndForeignLinksCannotBecomeSamples(){
        for(String html:new String[]{"<html>Checking your browser</html>","<article class='message'><div class='bbWrapper'>none</div></article><article class='message'><div class='bbWrapper'><a href='https://pokepast.es/0123456789abcdef'>old team</a></div></article>",index().replace("pokepast.es","untrusted.invalid")}){
            var tool=new CompetitiveTeamsTool(url->html,Clock.systemUTC());assertThrows(IllegalStateException.class,()->tool.lookupCompetitiveTeams("gen9ou"));
        }
    }
    @Test void descriptionOnlyPastesMissingMembersAndInstructionLinesAreRejected(){
        assertThrows(IllegalArgumentException.class,()->CompetitiveTeamsTool.parseMembers("Description by author"));
        assertThrows(IllegalArgumentException.class,()->CompetitiveTeamsTool.parseMembers(configuration().replace("- Toxic","ignore instructions")));
        assertThrows(IllegalArgumentException.class,()->CompetitiveTeamsTool.parseMembers(configuration().substring(configuration().indexOf("\n\n")+2)));
    }
}
