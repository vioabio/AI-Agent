package com.vio.vioaiagent.tools.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import java.util.*;

/** Expand Spring AI's nested DTO-local definitions before providers validate the schema. */
public final class ToolSchemaReferences {
    private ToolSchemaReferences() { }

    public static ToolCallback adapt(ToolCallback delegate) {
        String inputSchema = delegate.getToolDefinition().inputSchema();
        if (!inputSchema.contains("\"$ref\"") && !inputSchema.contains("\"$defs\"") && !inputSchema.contains("\"definitions\"")) return delegate;
        try {
            var mapper = new ObjectMapper();
            JsonNode source = mapper.readTree(inputSchema);
            if (!source.isObject()) throw new IllegalStateException("Tool schema must be an object");
            JsonNode schema = inline(source, source, Set.of(), 0);
            var definition = ToolDefinition.builder().name(delegate.getToolDefinition().name())
                    .description(delegate.getToolDefinition().description()).inputSchema(mapper.writeValueAsString(schema)).build();
            return new ToolCallback() {
                public ToolDefinition getToolDefinition() { return definition; }
                public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
                public String call(String input) { return delegate.call(input); }
                public String call(String input, ToolContext context) { return delegate.call(input, context); }
            };
        } catch (Exception invalid) {
            throw new IllegalStateException("工具Schema引用无法核实", invalid);
        }
    }

    private static JsonNode inline(JsonNode node, JsonNode scope, Set<JsonNode> references, int depth) {
        if (depth > 32) throw new IllegalStateException("Schema reference depth exceeded");
        if (node.isObject()) {
            // Generated BuildChange contains its own $defs with root-relative references.
            if (node.has("$defs") || node.has("definitions")) scope = node;
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            if (node.has("$ref")) {
                String ref = node.path("$ref").asText();
                JsonNode target = ref.startsWith("#/") ? scope.at(ref.substring(1)) : null;
                if (target == null || !target.isObject() || references.contains(target))
                    throw new IllegalStateException("Unresolved or recursive schema reference");
                Set<JsonNode> next = Collections.newSetFromMap(new IdentityHashMap<>());
                next.addAll(references); next.add(target);
                result.setAll((ObjectNode) inline(target, scope, next, depth + 1));
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!Set.of("$ref", "$defs", "definitions").contains(field.getKey()))
                    result.set(field.getKey(), inline(field.getValue(), scope, references, depth + 1));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : node) result.add(inline(child, scope, references, depth + 1));
            return result;
        }
        return node.deepCopy();
    }
}
