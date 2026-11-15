package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.guides.LocalGuideSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProjectGuideFullSourceTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    LocalGuideSearch index(){return new LocalGuideSearch(mapper,new DefaultResourceLoader(),"classpath:pokemon-guides/expanded-v1.json",directory.resolve("guides.sqlite").toString());}
    @Test void defaultsQuestionIncludesTheReturnedSecondParagraphWithoutUnrelatedDocuments() throws Exception {
        String question="请说明本项目图鉴种族值与50级实际能力的区别，以及没有填写等级、性格、努力值和个体值时采用的默认值；找不到本地来源时说明，不要编造。";
        var hits=index().search(question,"pokemon-scarlet-violet");
        var evidence=new GuideAnswerEvidence();evidence.begin(question);evidence.observe("searchPokemonGuides",mapper.writeValueAsString(hits));
        String answer=evidence.verifiedAnswer();assertTrue(answer.contains(EvidenceText.quote(hits.getFirst().document().content())));
        for(String phrase:List.of("等级50","性格hardy","努力值全0","个体值全31","不证明用户主动选择","不是最优培养方案"))assertTrue(answer.contains(phrase),phrase);
        assertTrue(answer.contains("原文范围：完整文档"));assertFalse(answer.contains("原文位置：第"));
        for(var extra:hits.subList(1,hits.size()))assertFalse(answer.contains("**"+extra.document().title()+"**"));
    }
    @Test void workflowQuestionCoversCancelConflictReadbackAndRestartBoundary() throws Exception {
        String question=ProjectGuidePreflightTest.QUESTION;
        var hits=index().search(question,"pokemon-scarlet-violet");var evidence=new GuideAnswerEvidence();evidence.begin(question);
        evidence.observe("searchPokemonGuides",mapper.writeValueAsString(hits));String answer=evidence.verifiedAnswer();
        for(String phrase:List.of("用户取消","预览过期","旧预览不能覆盖新状态","并发变化应提示冲突","确认成功后应回读队伍","不承诺服务重启后自动续跑"))assertTrue(answer.contains(phrase),phrase);
        assertTrue(answer.contains(EvidenceText.quote(hits.getFirst().document().content())));
        assertFalse(answer.contains("**查询资料时区分物种、形态与编号**"));
    }
    @Test void genericGuideRequestsKeepTheirExistingFragmentSelection() throws Exception {
        var hits=index().search(ProjectGuidePreflightTest.QUESTION,"pokemon-scarlet-violet");
        var evidence=new GuideAnswerEvidence();evidence.begin("请检索队伍确认的攻略原文");
        evidence.observe("searchPokemonGuides",mapper.writeValueAsString(hits));String answer=evidence.verifiedAnswer();
        assertTrue(answer.contains("原文位置：第"));assertFalse(answer.contains("原文范围：完整文档"));
        for(var hit:hits)assertTrue(answer.contains("**"+hit.document().title()+"**"));
    }
    @Test void conciseRenderingRetainsEachSourceAndSharesItsScopeNotice() throws Exception {
        var hits=index().search(ProjectGuidePreflightTest.QUESTION,"pokemon-scarlet-violet");
        var evidence=new GuideAnswerEvidence();evidence.begin("请检索队伍确认的攻略原文");
        evidence.observe("searchPokemonGuides",mapper.writeValueAsString(hits));String answer=evidence.verifiedAnswer();
        for(var hit:hits) {
            assertTrue(answer.contains("**"+hit.document().title()+"**"));
            assertTrue(answer.contains(hit.document().author()));assertTrue(answer.contains(hit.document().updatedAt()));
            String returned=hit.citation()==null?hit.document().content():hit.citation().text();
            if(returned.codePointCount(0,returned.length())<=700)assertTrue(answer.contains(EvidenceText.quote(returned)));
        }
        assertEquals(1,answer.split("引用以所列原文为范围",-1).length-1);
        assertTrue(answer.contains("实际配置"));assertTrue(answer.contains("计算核对"));assertTrue(answer.contains("赛事适用性"));
        assertFalse(answer.contains("以下是本次检索到的项目原创笔记"));
        assertFalse(answer.contains("资料说明：项目原创"));
    }
    @Test void oversizedProjectSourceCannotBeSilentlyTruncatedIntoAnApparentlyCompleteAnswer() throws Exception {
        var doc=new LocalGuideSearch.Document("long-source","长文","pokemon-scarlet-violet","","project","2026-10-23","","内容".repeat(1100));
        var evidence=new GuideAnswerEvidence();evidence.begin(ProjectGuidePreflightTest.QUESTION);
        evidence.observe("searchPokemonGuides",mapper.writeValueAsString(List.of(new LocalGuideSearch.Hit(doc,3,LocalGuideSearch.checksum(doc)))));
        assertTrue(evidence.verifiedAnswer().contains("原文过长"));assertFalse(evidence.verifiedAnswer().contains("内容内容"));
    }
}
