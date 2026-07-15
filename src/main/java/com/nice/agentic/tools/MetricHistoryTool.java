package com.nice.agentic.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.widget.WidgetPayloadResolver;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;

import java.util.LinkedHashMap;
import java.util.Map;

@ToolScope("rca")
@Component
public class MetricHistoryTool implements AgentTool {

    private final WidgetPayloadResolver resolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public MetricHistoryTool(WidgetPayloadResolver resolver) {
        this.resolver = resolver;
    }

    @Override public String name() { return "metric_history"; }

    @Override public String description() {
        return "Fetch an hourly time series for a metric over a range (e.g. 'today', 'last_7_days'). " +
                "Use to locate WHEN a change started.";
    }

    @Override public Document inputSchema() {
        Map<String, Document> props = new LinkedHashMap<>();
        props.put("scope",  DocumentUtil.toDocument(Map.of("type", "string")));
        props.put("metric", DocumentUtil.toDocument(Map.of("type", "string")));
        props.put("range",  DocumentUtil.toDocument(Map.of(
                "type", "string",
                "description", "One of: today, last_24h, last_7_days")));
        return DocumentUtil.toDocument(Map.of(
                "type", "object",
                "properties", props,
                "required", java.util.List.of("scope", "metric", "range")));
    }

    @Override public String invoke(Document arguments) {
        String scope  = DocumentUtil.stringArg(arguments, "scope", "");
        String metric = DocumentUtil.stringArg(arguments, "metric", "");
        String range  = DocumentUtil.stringArg(arguments, "range", "today");
        try {
            Map<String, Object> payload = resolver.resolve(
                    "metric_history",
                    Map.of("scope", scope, "metric", metric, "range", range));
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
