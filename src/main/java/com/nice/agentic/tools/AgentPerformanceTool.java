package com.nice.agentic.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.widget.WidgetPayloadResolver;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;

import java.util.LinkedHashMap;
import java.util.Map;

@ToolScope("rca")
@Component
public class AgentPerformanceTool implements AgentTool {

    private final WidgetPayloadResolver resolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentPerformanceTool(WidgetPayloadResolver resolver) {
        this.resolver = resolver;
    }

    @Override public String name() { return "agent_performance_slice"; }

    @Override public String description() {
        return "Break the metric down by a dimension across agents on the scope. " +
                "Use to find whether the change is concentrated in a sub-population " +
                "(e.g. new hires, particular team). dimension='tenure' is supported.";
    }

    @Override public Document inputSchema() {
        Map<String, Document> props = new LinkedHashMap<>();
        props.put("scope",     DocumentUtil.toDocument(Map.of("type", "string")));
        props.put("dimension", DocumentUtil.toDocument(Map.of(
                "type", "string",
                "description", "Currently supported: tenure")));
        return DocumentUtil.toDocument(Map.of(
                "type", "object",
                "properties", props,
                "required", java.util.List.of("scope", "dimension")));
    }

    @Override public String invoke(Document arguments) {
        String scope     = DocumentUtil.stringArg(arguments, "scope", "");
        String dimension = DocumentUtil.stringArg(arguments, "dimension", "");
        try {
            Map<String, Object> payload = resolver.resolve(
                    "agent_leaderboard",
                    Map.of("scope", scope, "dimension", dimension));
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
