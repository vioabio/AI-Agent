package com.vio.vioaiagent.pokemon.guides;

import java.util.List;

/** A provider may only score supplied candidates, never replace their source payload. */
public interface GuideReranker {
    record Candidate(String id, String content) { }
    record Score(int index, double score) { }
    List<Score> rank(String query, List<Candidate> candidates);
}
