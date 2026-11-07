package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.ContextBudgetProperties;
import com.vio.vioaiagent.observability.ContextTokenEstimator;
import org.springframework.ai.chat.messages.*;
import java.util.*;

/** Owner-scoped completed pairs only; stored text is never promoted to system messages. */
public final class ConversationHistory {
    private ConversationHistory() { }

    static List<Message> window(AgentRun run, List<ChatMessage> stored) {
        return select(run,stored,ContextBudgetProperties.defaults()).messages();
    }
    static Window select(AgentRun run,List<ChatMessage> stored,ContextBudgetProperties limits) {
        var estimator=new ContextTokenEstimator(limits.safetyPercent());
        Map<String,List<ChatMessage>> grouped=new LinkedHashMap<>();
        if(stored!=null)stored.stream().filter(m->run.userId().equals(m.userId()) && run.conversationId().equals(m.conversationId())
                && m.runId()!=null && !run.id().equals(m.runId()) && !m.createdAt().isAfter(run.createdAt())
                && ("user".equals(m.role()) || "assistant".equals(m.role())))
                .sorted(Comparator.comparing(ChatMessage::createdAt).thenComparing(ChatMessage::id))
                .forEach(m->grouped.computeIfAbsent(m.runId(),ignored->new ArrayList<>()).add(m));
        List<List<ChatMessage>> pairs=grouped.values().stream().filter(rows->rows.size()==2
                && "user".equals(rows.getFirst().role()) && "assistant".equals(rows.getLast().role())
                && !rows.getFirst().id().equals(rows.getLast().id()))
                .sorted(Comparator.comparing(rows->rows.getLast().createdAt())).toList();
        List<Message> selected=new ArrayList<>();int tokens=0,characters=0,turns=0;
        for(int i=pairs.size()-1;i>=0;i--) {
            var pair=pairs.get(i);var user=new UserMessage(pair.getFirst().content());var answer=new AssistantMessage(pair.getLast().content());
            int chars=user.getText().length()+answer.getText().length(),cost;
            try {cost=estimator.message(user)+estimator.message(answer);}catch(com.vio.vioaiagent.observability.ContextBudgetExceededException tooLarge){break;}
            if(turns>=limits.historyTurns() || chars>12000-characters || cost>limits.historyTokens()-tokens)break;
            selected.addFirst(answer);selected.addFirst(user);characters+=chars;tokens+=cost;turns++;
        }
        return new Window(List.copyOf(selected),Map.of("availableTurns",pairs.size(),"selectedTurns",turns,
                "omittedTurns",pairs.size()-turns,"estimatedTokens",tokens));
    }
    record Window(List<Message> messages,Map<String,Object> summary) { }
}
