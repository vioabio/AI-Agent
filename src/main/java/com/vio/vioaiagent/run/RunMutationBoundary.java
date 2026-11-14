package com.vio.vioaiagent.run;

import java.util.function.Supplier;

/** Only the Run worker may grant a bounded local mutation; standalone contexts deny it. */
public interface RunMutationBoundary {
    <T> T write(Supplier<T> action);
    void check();
    default void publishFact(com.vio.vioaiagent.agent.PokemonAnswerBlock block) { }
    default void publishDamage(com.vio.vioaiagent.agent.DamageAnswerBlock block) { }
    default void publishTeam(com.vio.vioaiagent.agent.TeamSuggestionAnswerBlock block) { }
    default void publishTraining(long requestedId,com.vio.vioaiagent.pokemon.team.TeamWorkflowService.ReadTeam read) { }
    static RunMutationBoundary denied() {
        return new RunMutationBoundary() {
            public <T> T write(Supplier<T> action) { check(); return null; }
            public void check() { throw new IllegalStateException("缺少受控 Run 写入边界"); }
        };
    }
}
