package com.nice.agentic.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.widget.WidgetPayloadResolver;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;

import java.util.LinkedHashMap;
import java.util.Map;

@ToolScope("rca")
@Component
public class MetricSnapshotTool implements AgentTool {

    private final WidgetPayloadResolver resolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public MetricSnapshotTool(WidgetPayloadResolver resolver) {
        this.resolver = resolver;
    }

    @Override public String name() { return "metric_snapshot"; }

    @Override public String description() {
        return "Fetch the current live value of a metric for a scope (e.g. queue name) versus its baseline. " +
                "Use this first to confirm the metric change and its magnitude.";
    }

    @Override public Document inputSchema() {
        Map<String, Document> props = new LinkedHashMap<>();
        props.put("scope", DocumentUtil.toDocument(Map.of(
                "type", "string",
                "description", "Queue, skill, or team name — e.g. 'Banking'")));
        props.put("metric", DocumentUtil.toDocument(Map.of(
                "type", "string",
                "description", "Metric name — one of: AHT, ACW, AbandonmentRate")));
        return DocumentUtil.toDocument(Map.of(
                "type", "object",
                "properties", props,
                "required", java.util.List.of("scope", "metric")));
    }

    @Override public String invoke(Document arguments) {
        String scope  = DocumentUtil.stringArg(arguments, "scope", "");
        String metric = DocumentUtil.stringArg(arguments, "metric", "");
        try {
            Map<String, Object> payload = resolver.resolve(
                    "aht_summary",
                    Map.of("scope", scope, "metric", metric));
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
