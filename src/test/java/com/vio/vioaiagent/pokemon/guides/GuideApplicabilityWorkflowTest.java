package com.vio.vioaiagent.pokemon.guides;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GuideApplicabilityWorkflowTest {
    @Test void colloquialCompletionQuestionsKeepProjectWorkflowSourcesEligible() {
        var project=new GuideApplicability(List.of(),List.of("project"));
        for(String question:List.of("AI建议了新的招式，是不是已经改好队伍", "队伍是不是已经改好了？", "现在的配置是否已经生效？"))
            assertTrue(project.allows(question),question);
    }
    @Test void ordinaryMoveQuestionsAndSpeciesBoundariesRemainScoped() {
        var project=new GuideApplicability(List.of(),List.of("project"));
        for(String question:List.of("队伍的招式怎么搭配更好", "新招式是不是能直接学会", "角色配合已经生效了吗"))
            assertFalse(project.allows(question),question);
        var species=new GuideApplicability(List.of("froslass"),List.of("project"));
        assertFalse(species.allows("squirtle的队伍是不是已经改好了"));
        assertTrue(species.allows("froslass的队伍是不是已经改好了"));
    }
}
