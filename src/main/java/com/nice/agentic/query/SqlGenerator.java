package com.nice.agentic.query;

import com.nice.agentic.TenantContext;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Text-to-SQL generator: takes a natural-language question and produces a safe
 * Snowflake SELECT statement with JOINs, formatting, and aggregations.
 */
@Service
public class SqlGenerator {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerator.class);
    private static final String TOOL_NAME = "generate_sql";

    private static final Set<String> DANGEROUS_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE",
            "TRUNCATE", "EXEC", "MERGE", "GRANT", "REVOKE");

    private static final Pattern SAFE_SQL = Pattern.compile(
            "^\\s*(SELECT|WITH)\\s.+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final BedrockRuntimeClient bedrock;
    private final TenantContext tenantContext;
    private final SchemaDiscovery schemaDiscovery;
    private final String basePrompt;
    private final ToolConfiguration toolConfiguration;

    public SqlGenerator(BedrockRuntimeClient bedrock, TenantContext tenantContext,
                        SchemaDiscovery schemaDiscovery) throws IOException {
        this.bedrock = bedrock;
        this.tenantContext = tenantContext;
        this.schemaDiscovery = schemaDiscovery;
        String raw = new String(
                new ClassPathResource("prompts/sql-generator-system.txt").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        this.basePrompt = raw;
        this.toolConfiguration = buildToolConfiguration();
    }

    /**
     * Generate a validated SQL SELECT statement from a natural-language question.
     */
    public String generateSql(String question) {
        String dynamicSchema = schemaDiscovery.getSchemaReference();
        String prompt = basePrompt.replace("{TENANT_ID}", tenantContext.getTenantId())
                .replace("{SCHEMA_REFERENCE}", dynamicSchema);

        ConverseRequest request = ConverseRequest.builder()
                .modelId("us.anthropic.claude-sonnet-4-5-20250929-v1:0")
                .system(SystemContentBlock.fromText(prompt))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(question))
                        .build())
                .toolConfig(toolConfiguration)
                .build();

        ConverseResponse response = bedrock.converse(request);

        for (ContentBlock block : response.output().message().content()) {
            if (block.toolUse() != null) {
                ToolUseBlock use = block.toolUse();
                if (TOOL_NAME.equals(use.name())) {
                    String sql = extractSql(use.input());
                    validateSql(sql);
                    return sql;
                }
            }
        }
        throw new IllegalStateException("SqlGenerator: model did not produce SQL");
    }

    private String extractSql(Document input) {
        Map<String, Document> map = input.asMap();
        Document sqlDoc = map.get("sql");
        if (sqlDoc != null && sqlDoc.isString()) {
            return sqlDoc.asString().trim();
        }
        throw new IllegalStateException("SqlGenerator: no 'sql' field in tool output");
    }

    private void validateSql(String sql) {
        if (!SAFE_SQL.matcher(sql).matches()) {
            throw new QueryValidationException("DANGEROUS_KEYWORD",
                    "Generated SQL does not start with SELECT or WITH: " + sql.substring(0, Math.min(50, sql.length())));
        }
        String upper = sql.toUpperCase();
        for (String keyword : DANGEROUS_KEYWORDS) {
            int idx = upper.indexOf(keyword);
            while (idx >= 0) {
                boolean prefixOk = idx == 0 || !Character.isLetterOrDigit(upper.charAt(idx - 1));
                boolean suffixOk = (idx + keyword.length()) >= upper.length()
                        || !Character.isLetterOrDigit(upper.charAt(idx + keyword.length()));
                if (prefixOk && suffixOk) {
                    throw new QueryValidationException("DANGEROUS_KEYWORD",
                            "Generated SQL contains forbidden keyword: " + keyword);
                }
                idx = upper.indexOf(keyword, idx + 1);
            }
        }
    }

    private ToolConfiguration buildToolConfiguration() {
        Map<String, Document> props = new LinkedHashMap<>();
        props.put("sql", DocumentUtil.toDocument(Map.of(
                "type", "string",
                "description", "The complete Snowflake SELECT SQL query")));

        Document schema = DocumentUtil.toDocument(Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("sql")));

        Tool tool = Tool.builder()
                .toolSpec(ToolSpecification.builder()
                        .name(TOOL_NAME)
                        .description("Generate a safe Snowflake SELECT SQL query from a natural-language question")
                        .inputSchema(ToolInputSchema.builder().json(schema).build())
                        .build())
                .build();

        return ToolConfiguration.builder()
                .tools(tool)
                .toolChoice(ToolChoice.fromTool(
                        SpecificToolChoice.builder().name(TOOL_NAME).build()))
                .build();
    }
}
