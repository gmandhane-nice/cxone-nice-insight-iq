package com.nice.agentic.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.widget.WidgetPayloadResolver;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes real-time staffing data for a scope (agents on shift, available, in-call,
 * new-hire count, etc.). Backed by {@link WidgetPayloadResolver#resolve} "realtime_staffing".
 */
@ToolScope("rca")
@Component
public class StaffingSnapshotTool implements AgentTool {

    private final WidgetPayloadResolver resolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public StaffingSnapshotTool(WidgetPayloadResolver resolver) {
        this.resolver = resolver;
    }

    @Override public String name() { return "staffing_snapshot"; }

    @Override public String description() {
        return "Fetch the real-time staffing snapshot for a scope: how many agents are on shift, " +
                "available, in-call, and whether a new-hire batch recently joined. " +
                "Use to correlate a metric change with a staffing event.";
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
                    "realtime_staffing",
                    Map.of("scope", scope));
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
