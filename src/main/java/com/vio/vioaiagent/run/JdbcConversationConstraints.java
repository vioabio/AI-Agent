package com.vio.vioaiagent.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import java.util.Optional;

/** Shared parameterized SQL for local SQLite and PostgreSQL; ownership is checked in each mutation. */
public final class JdbcConversationConstraints {
    private final JdbcTemplate jdbc;
    private final String table, ownerQuery;
    private final ObjectMapper mapper = new ObjectMapper();
    JdbcConversationConstraints(JdbcTemplate jdbc, boolean local) {
        this.jdbc = jdbc;
        table = local ? "local_conversation_constraints" : "conversation_constraints";
        ownerQuery = "SELECT c.id FROM " + (local ? "local_conversation" : "conversation")
                + " c WHERE c.id=? AND c.user_id=?" + (local ? "" : " AND c.deleted_at IS NULL");
    }
    Optional<ConversationConstraints> read(String id, String owner) {
        return jdbc.query("SELECT revision,settings_json FROM " + table + " WHERE conversation_id IN (" + ownerQuery + ")", (rs, n) -> {
            try {
                var value = ConversationConstraints.fromSnapshot(mapper.readValue(rs.getString("settings_json"), new TypeReference<Map<String,Object>>() {}));
                if (value.revision() != rs.getLong("revision")) throw new IllegalStateException();
                return value;
            } catch (Exception invalid) { throw new IllegalStateException("对话偏好存储无法核实"); }
        }, id, owner).stream().findFirst();
    }
    boolean replace(String id, String owner, ConversationConstraints expected) {
        var next = expected.next();
        try {
            String json = mapper.writeValueAsString(next.snapshot());
            if (expected.revision() == 0) return jdbc.update("INSERT INTO " + table
                    + "(conversation_id,revision,settings_json) SELECT id,?,? FROM (" + ownerQuery + ") owned WHERE 1=1"
                    + " ON CONFLICT(conversation_id) DO NOTHING", next.revision(), json, id, owner) == 1;
            return jdbc.update("UPDATE " + table + " SET revision=?,settings_json=? WHERE revision=? AND conversation_id IN ("
                    + ownerQuery + ")", next.revision(), json, expected.revision(), id, owner) == 1;
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) { throw new IllegalStateException("无法保存对话偏好"); }
    }
}
