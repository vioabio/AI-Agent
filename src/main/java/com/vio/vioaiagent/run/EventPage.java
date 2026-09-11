package com.vio.vioaiagent.run;

import java.util.List;

/** 事件游标响应；nextEventId 可直接作为下一次 afterEventId。 */
public record EventPage(List<AgentEvent> events, long nextEventId, boolean terminal) {
    public EventPage {
        events = events == null ? List.of() : List.copyOf(events);
    }
}

