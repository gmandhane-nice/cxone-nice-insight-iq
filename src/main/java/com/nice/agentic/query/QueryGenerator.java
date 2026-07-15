package com.nice.agentic.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.tools.DocumentUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolChoice;
import software.amazon.awssdk.services.bedrockruntime.model.SpecificToolChoice;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates a natural-language question into a {@link QueryDescriptor} by making one
 * Bedrock {@code converse} call with forced tool use.
 *
 * The model is required to call the {@code generate_descriptor} tool, whose JSON schema
 * matches {@link QueryDescriptor} field-for-field.  The tool input document is then
 * mapped back to a record.
 */
@Service
public class QueryGenerator {

    private static final Logger log = LoggerFactory.getLogger(QueryGenerator.class);

    private static final String TOOL_NAME = "generate_descriptor";

    private final BedrockRuntimeClient bedrock;
    private final String systemPrompt;
    private final ToolConfiguration toolConfiguration;
    private final ObjectMapper mapper = new ObjectMapper();

    public QueryGenerator(BedrockRuntimeClient bedrock) throws IOException {
        this.bedrock = bedrock;
        this.systemPrompt = new String(
                new ClassPathResource("prompts/query-generator-v1.txt").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        this.toolConfiguration = buildToolConfiguration();
    }

    /**
     * Generates a {@link QueryDescriptor} from a natural-language question.
     *
     * @param naturalLanguageQuestion the question to translate
     * @return the generated descriptor
     * @throws IllegalStateException if the model does not call the expected tool
     */
    public QueryDescriptor generate(String naturalLanguageQuestion) {
        ConverseRequest request = ConverseRequest.builder()
                .modelId("us.anthropic.claude-sonnet-4-5-20250929-v1:0")
                .system(SystemContentBlock.fromText(systemPrompt))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(naturalLanguageQuestion))
                        .build())
                .toolConfig(toolConfiguration)
                .build();

        ConverseResponse response = bedrock.converse(request);

        for (ContentBlock block : response.output().message().content()) {
            if (block.toolUse() != null) {
                ToolUseBlock use = block.toolUse();
                if (TOOL_NAME.equals(use.name())) {
                    log.debug("QueryGenerator received tool call: {}", use.name());
                    return mapToDescriptor(use.input());
                }
            }
        }
        throw new IllegalStateException(
                "QueryGenerator: model did not call '" + TOOL_NAME + "'. Response: "
                + response.output().message().content());
    }

    // -------------------------------------------------------------------------
    // Tool schema
    // -------------------------------------------------------------------------

    private ToolConfiguration buildToolConfiguration() {
        Map<String, Document> props = new LinkedHashMap<>();
        props.put("store", DocumentUtil.toDocument(Map.of(
                "type", "string",
                "description", "Backing store — one of: opensearch, snowflake")));
        props.put("indexOrTable", DocumentUtil.toDocument(Map.of(
                "type", "string",
                "description", "Table name (Snowflake) or index name (OpenSearch)")));
        props.put("filters", DocumentUtil.toDocument(Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "key=value filter strings, e.g. AGENT_NO=12345")));
        props.put("aggs", DocumentUtil.toDocument(Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Aggregation expressions, e.g. COUNT(*) as TOTAL, COUNT(DISTINCT AGENT_NO) as AGENT_COUNT, AVG(HANDLE_SECONDS) as AVG_AHT")));
        props.put("timeRange", DocumentUtil.toDocument(Map.of(
                "type", "string",
                "description", "Duration string, e.g. last_3h, last_7d, last_720d")));
        props.put("limit", DocumentUtil.toDocument(Map.of(
                "type", "integer",
                "description", "Maximum number of rows to return (max 500)")));
        props.put("select", DocumentUtil.toDocument(Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Columns to SELECT. Empty array means SELECT *. Use specific columns for cleaner output, e.g. [AGENT_NO, USER_ID, START_TIMESTAMP]")));
        props.put("groupBy", DocumentUtil.toDocument(Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Columns to GROUP BY. Use when counting or aggregating, e.g. [AGENT_NO, USER_ID]")));

        Document schema = DocumentUtil.toDocument(Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("store", "indexOrTable", "filters", "aggs", "timeRange", "limit", "select", "groupBy")));

        Tool tool = Tool.builder()
                .toolSpec(ToolSpecification.builder()
                        .name(TOOL_NAME)
                        .description("Generate a query descriptor from a natural-language question")
                        .inputSchema(ToolInputSchema.builder().json(schema).build())
                        .build())
                .build();

        return ToolConfiguration.builder()
                .tools(tool)
                .toolChoice(ToolChoice.fromTool(
                        SpecificToolChoice.builder().name(TOOL_NAME).build()))
                .build();
    }

    // -------------------------------------------------------------------------
    // Map Document → QueryDescriptor
    // -------------------------------------------------------------------------

    private QueryDescriptor mapToDescriptor(Document input) {
        Map<String, Document> map = input.asMap();

        String store       = stringField(map, "store", "snowflake");
        String indexOrTable = stringField(map, "indexOrTable", "agent_session_activity");
        List<String> filters = stringListField(map, "filters");
        List<String> aggs    = stringListField(map, "aggs");
        String timeRange    = stringField(map, "timeRange", "last_7d");
        int limit           = intField(map, "limit", 100);
        List<String> select  = stringListField(map, "select");
        List<String> groupBy = stringListField(map, "groupBy");

        return new QueryDescriptor(store, indexOrTable, filters, aggs, timeRange, limit, select, groupBy);
    }

    private String stringField(Map<String, Document> map, String key, String defaultVal) {
        Document d = map.get(key);
        return (d != null && d.isString()) ? d.asString() : defaultVal;
    }

    private List<String> stringListField(Map<String, Document> map, String key) {
        Document d = map.get(key);
        if (d == null || !d.isList()) return Collections.emptyList();
        return d.asList().stream()
                .map(item -> item.isString() ? item.asString() : item.toString())
                .toList();
    }

    private int intField(Map<String, Document> map, String key, int defaultVal) {
        Document d = map.get(key);
        if (d == null) return defaultVal;
        if (d.isNumber()) return d.asNumber().intValue();
        if (d.isString()) {
            try { return Integer.parseInt(d.asString()); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }
}
