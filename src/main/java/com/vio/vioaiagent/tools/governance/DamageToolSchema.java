package com.vio.vioaiagent.tools.governance;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import java.util.*;

/** Matches the read-only calculation DTO defaults without changing identity or stat requirements. */
public final class DamageToolSchema {
    private DamageToolSchema() { }
    public static ToolCallback adapt(ToolCallback delegate) {
        if(!"calculateBattleDamage".equals(delegate.getToolDefinition().name()))return delegate;
        try {
            var json=new ObjectMapper();var original=json.readTree(delegate.getToolDefinition().inputSchema());var schema=object(inline(original,original,Set.of(),0));
            var request=object(schema.path("properties").path("request"));optional(request,Set.of("field"));
            for(String side:List.of("attacker","defender")) {
                var combatant=object(request.path("properties").path(side));optional(combatant,Set.of("build"));
                optional(object(combatant.path("properties").path("build")),Set.of("moves","ability","item","nature","level","evs","ivs"));
            }
            optional(object(request.path("properties").path("field")),Set.of("gameType"));
            var definition=ToolDefinition.builder().name(delegate.getToolDefinition().name()).description(delegate.getToolDefinition().description()).inputSchema(json.writeValueAsString(schema)).build();
            return new ToolCallback() {
                public ToolDefinition getToolDefinition(){return definition;}
                public ToolMetadata getToolMetadata(){return delegate.getToolMetadata();}
                public String call(String input){return delegate.call(input);}
                public String call(String input,ToolContext context){return delegate.call(input,context);}
            };
        }catch(Exception invalid){throw new IllegalStateException("伤害工具默认参数Schema无法核实",invalid);}
    }
    private static ObjectNode object(JsonNode node){if(!(node instanceof ObjectNode object))throw new IllegalStateException("无法识别的伤害工具Schema");return object;}
    private static JsonNode inline(JsonNode node,JsonNode root,Set<String> references,int depth) {
        if(depth>24)throw new IllegalStateException("伤害工具Schema引用过深");
        if(node.isObject()) {
            if(node.has("$defs")||node.has("definitions"))root=node;
            ObjectNode result=JsonNodeFactory.instance.objectNode();
            if(node.has("$ref")) {
                String ref=node.path("$ref").asText();
                if(!ref.startsWith("#/")||references.contains(ref)||root.at(ref.substring(1)).isMissingNode())throw new IllegalStateException("伤害工具Schema引用无法核实");
                var next=new HashSet<>(references);next.add(ref);result.setAll(object(inline(root.at(ref.substring(1)),root,next,depth+1)));
            }
            var entries=node.fields();while(entries.hasNext()){var entry=entries.next();if(!Set.of("$ref","$defs","definitions").contains(entry.getKey()))result.set(entry.getKey(),inline(entry.getValue(),root,references,depth+1));}
            return result;
        }
        if(node.isArray()){ArrayNode result=JsonNodeFactory.instance.arrayNode();for(JsonNode child:node)result.add(inline(child,root,references,depth+1));return result;}
        return node.deepCopy();
    }
    private static void optional(ObjectNode schema,Set<String> names) {
        if(schema.path("required") instanceof ArrayNode required) {
            var retained=new ArrayList<String>();required.forEach(v->{if(!names.contains(v.asText()))retained.add(v.asText());});
            required.removeAll();retained.forEach(required::add);
        }
    }
}
