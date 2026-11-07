package com.vio.vioaiagent.pokemon.reference;

import com.vio.vioaiagent.run.RunRepository;
import com.vio.vioaiagent.tools.governance.ToolExecutionContextHolder;
import org.springframework.stereotype.Component;

/** The model cannot change the version of a persisted Run by choosing another tool argument. */
@Component
public class RunToolScope {
    private final RunRepository runs;
    public RunToolScope(RunRepository runs){this.runs=runs;}
    public void check(String dataReleaseId,String gameReleaseId) {
        var context=ToolExecutionContextHolder.peek();
        if(context==null || !context.runId().startsWith("run-"))return;
        var run=runs.findByIdForUser(context.runId(),context.userId()).orElseThrow(()->new IllegalStateException("工具 Run 不存在或无权访问"));
        if(dataReleaseId!=null&&!dataReleaseId.equals(run.dataReleaseId()) || gameReleaseId!=null&&!gameReleaseId.equals(run.gameReleaseId()))throw new IllegalArgumentException("工具版本参数与当前 Run 不一致，拒绝跨版本查询");
    }
    /** Guide filters come from the authenticated Run, never invented model scope arguments. */
    public com.vio.vioaiagent.rag.retrieval.KnowledgeRetrievalQuery guideQuery(String text,String gameReleaseId) {
        check(null,gameReleaseId);
        var context=ToolExecutionContextHolder.peek();
        if(context==null || !context.runId().startsWith("run-"))
            return new com.vio.vioaiagent.rag.retrieval.KnowledgeRetrievalQuery(text,"zh-CN",gameReleaseId,"casual-singles","casual-v1",20);
        var run=runs.findByIdForUser(context.runId(),context.userId()).orElseThrow(()->new IllegalStateException("工具 Run 不存在或无权访问"));
        return new com.vio.vioaiagent.rag.retrieval.KnowledgeRetrievalQuery(text,run.language(),run.gameReleaseId(),run.battleFormat(),run.rulesetVersionId(),20);
    }
}
