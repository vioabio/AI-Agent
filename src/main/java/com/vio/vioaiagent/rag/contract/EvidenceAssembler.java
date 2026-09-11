package com.vio.vioaiagent.rag.contract;

import com.vio.vioaiagent.rag.retrieval.RetrievedKnowledgeChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 将检索结果转换为可引用回答，集中处理空证据和证据截断。 */
@Service
public class EvidenceAssembler {

    private static final int MAX_EVIDENCE_LENGTH = 800;

    public AnswerContract assemble(String answer, String gameVersion, String battleFormat,
                                   String rulesetVersion, String traceId,
                                   List<RetrievedKnowledgeChunk> retrieved) {
        if (gameVersion == null || gameVersion.isBlank()) {
            return AnswerContract.clarification("请先确认游戏版本（例如朱/紫）。", traceId,
                    "缺少 game_release_id，无法保证版本适用性");
        }
        if (retrieved == null || retrieved.isEmpty()) {
            return AnswerContract.refused("当前知识库没有足够依据支持该回答。", traceId,
                    "未召回可核查证据");
        }
        if (answer == null || answer.isBlank()) {
            return AnswerContract.refused("当前模型未生成可核查回答。", traceId,
                    "模型输出为空或依赖不可用");
        }
        List<Citation> citations = new ArrayList<>();
        for (RetrievedKnowledgeChunk item : retrieved) {
            if (item == null || item.view() == null || item.view().chunk() == null) {
                continue;
            }
            var view = item.view();
            String evidence = view.chunk().content();
            if (isBlank(view.documentId()) || isBlank(view.documentVersion())
                    || isBlank(view.sourceTitle()) || isBlank(view.sourceUrl()) || isBlank(evidence)) {
                continue;
            }
            if (evidence.length() > MAX_EVIDENCE_LENGTH) {
                evidence = evidence.substring(0, MAX_EVIDENCE_LENGTH) + "…";
            }
            citations.add(new Citation(view.documentId(), view.documentVersion(), view.sourceTitle(),
                    view.sourceUrl(), view.chunk().id(), evidence, item.retrievalScore()));
        }
        if (citations.isEmpty()) {
            return AnswerContract.refused("当前知识库没有足够依据支持该回答。", traceId,
                    "召回片段缺少完整来源或正文");
        }
        return AnswerContract.answered(answer, gameVersion, battleFormat, rulesetVersion,
                citations, List.of(), traceId);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
