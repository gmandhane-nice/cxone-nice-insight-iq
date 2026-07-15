package com.nice.agentic.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.widget.WidgetPayloadResolver;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes current queue state: depth, longest wait, SLA compliance, and at-risk status.
 * Backed by {@link WidgetPayloadResolver#resolve} "queue_state".
 */
@ToolScope("rca")
@Component
public class QueueStateTool implements AgentTool {

    private final WidgetPayloadResolver resolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public QueueStateTool(WidgetPayloadResolver resolver) {
        this.resolver = resolver;
    }

    @Override public String name() { return "queue_state"; }

    @Override public String description() {
        return "Fetch the current queue state for a scope: depth, longest wait in seconds, " +
                "SLA compliance, and risk status. Use to assess the customer-impact of a metric change.";
    }

    @Override public Document inputSchema() {
        Map<String, Document> props = new LinkedHashMap<>();
        props.put("scope", DocumentUtil.toDocument(Map.of(
                "type", "string",
                "description", "Queue, skill, or team name — e.g. 'Banking'")));
        return DocumentUtil.toDocument(Map.of(
                "type", "object",
                "properties", props,
                "required", java.util.List.of("scope")));
    }

    @Override public String invoke(Document arguments) {
        String scope = DocumentUtil.stringArg(arguments, "scope", "");
        try {
            Map<String, Object> payload = resolver.resolve(
                    "queue_state",
                    Map.of("scope", scope));
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
