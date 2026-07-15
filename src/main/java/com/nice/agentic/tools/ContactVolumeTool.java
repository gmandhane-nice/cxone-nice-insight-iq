package com.nice.agentic.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.widget.WidgetPayloadResolver;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes contact-volume breakdown (hourly, per scope) for the requested time range.
 * Backed by {@link WidgetPayloadResolver#resolve} "contact_volume".
 */
@ToolScope("rca")
@Component
public class ContactVolumeTool implements AgentTool {

    private final WidgetPayloadResolver resolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public ContactVolumeTool(WidgetPayloadResolver resolver) {
        this.resolver = resolver;
    }

    @Override public String name() { return "contact_volume_breakdown"; }

    @Override public String description() {
        return "Fetch the hourly contact volume breakdown for a scope over a time range. " +
                "Use to determine whether a metric change is driven by a volume surge or a per-contact effect.";
    }

    @Override public Document inputSchema() {
        Map<String, Document> props = new LinkedHashMap<>();
        props.put("scope", DocumentUtil.toDocument(Map.of(
                "type", "string",
                "description", "Queue, skill, or team name — e.g. 'Banking'")));
        props.put("timeRange", DocumentUtil.toDocument(Map.of(
                "type", "string",
                "description", "Optional time range — one of: today, last_24h, last_7_days. Defaults to 'today'.")));
        return DocumentUtil.toDocument(Map.of(
                "type", "object",
                "properties", props,
                "required", java.util.List.of("scope")));
    }

    @Override public String invoke(Document arguments) {
        String scope     = DocumentUtil.stringArg(arguments, "scope", "");
        String timeRange = DocumentUtil.stringArg(arguments, "timeRange", "today");
        try {
            Map<String, Object> payload = resolver.resolve(
                    "contact_volume",
                    Map.of("scope", scope, "timeRange", timeRange));
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
