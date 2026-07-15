package com.nice.agentic.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.query.QueryDescriptor;
import com.nice.agentic.query.QueryExecutor;
import com.nice.agentic.query.QueryGenerator;
import com.nice.agentic.query.QueryValidationException;
import com.nice.agentic.query.QueryValidator;
import com.nice.agentic.query.SnowflakeExecutor;
import com.nice.agentic.query.SqlGenerator;
import com.nice.agentic.query.TabularResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent tool: text-to-SQL pipeline. LLM generates complete SQL directly,
 * validated and executed against Snowflake. Falls back to the descriptor-based
 * path if SQL generation fails.
 */
@ToolScope("rca")
@Component
public class AdHocQueryTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(AdHocQueryTool.class);

    private final SqlGenerator sqlGenerator;
    private final SnowflakeExecutor snowflakeExecutor;
    private final QueryGenerator queryGenerator;
    private final QueryValidator queryValidator;
    private final QueryExecutor queryExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final java.util.concurrent.ConcurrentLinkedQueue<Map<String, Object>> rawResults =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static java.util.List<Map<String, Object>> getAndClearResults() {
        java.util.List<Map<String, Object>> results = new java.util.ArrayList<>();
        Map<String, Object> item;
        while ((item = rawResults.poll()) != null) {
            results.add(item);
        }
        return results;
    }

    public static void clearResults() {
        rawResults.clear();
    }

    public AdHocQueryTool(SqlGenerator sqlGenerator,
                          SnowflakeExecutor snowflakeExecutor,
                          QueryGenerator queryGenerator,
                          QueryValidator queryValidator,
                          QueryExecutor queryExecutor) {
        this.sqlGenerator = sqlGenerator;
        this.snowflakeExecutor = snowflakeExecutor;
        this.queryGenerator = queryGenerator;
        this.queryValidator = queryValidator;
        this.queryExecutor  = queryExecutor;
    }

    @Override
    public String name() {
        return "ad_hoc_query";
    }

    @Override
    public String description() {
        return "Use when no standard widget covers the question. Accepts a natural-language " +
               "sub-question, generates a safe query descriptor, validates it, and returns " +
               "tabular results.";
    }

    @Override
    public Document inputSchema() {
        Map<String, Document> props = new LinkedHashMap<>();
        props.put("question", DocumentUtil.toDocument(Map.of(
                "type", "string",
                "description", "Natural-language sub-question to answer from raw data")));
        return DocumentUtil.toDocument(Map.of(
                "type", "object",
                "properties", props,
                "required", java.util.List.of("question")));
    }

    private static final int MAX_RESULT_CHARS = 50_000;
    private static final int DEFAULT_LIMIT = 50;

    @Override
    public String invoke(Document arguments) {
        String question = DocumentUtil.stringArg(arguments, "question", "");
        if (question.isBlank()) {
            return "{\"error\":\"'question' argument is required\"}";
        }

        // Primary path: text-to-SQL (LLM generates complete SQL directly)
        if (snowflakeExecutor.isConfigured()) {
            try {
                String sql = sqlGenerator.generateSql(question);
                log.info("ad_hoc_query [text-to-sql]: {}", sql);

                List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
                List<String> columns = rows.isEmpty()
                        ? List.of()
                        : new ArrayList<>(rows.get(0).keySet());

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("columns", columns);
                response.put("rows", rows.size() > DEFAULT_LIMIT ? rows.subList(0, DEFAULT_LIMIT) : rows);
                response.put("rowCount", rows.size());
                response.put("sql", sql);

                rawResults.add(response);

                String json = mapper.writeValueAsString(response);
                if (json.length() > MAX_RESULT_CHARS) {
                    json = json.substring(0, MAX_RESULT_CHARS) + "...(truncated, " + rows.size() + " total rows)";
                }
                return json;

            } catch (Exception e) {
                log.warn("ad_hoc_query text-to-sql failed ({}), falling back to descriptor path", e.getMessage());
            }
        }

        // Fallback: descriptor-based path (LLM → QueryDescriptor → Java SQL builder)
        try {
            QueryDescriptor descriptor = queryGenerator.generate(question);
            int effectiveLimit = descriptor.limit() > 0
                    ? Math.min(descriptor.limit(), DEFAULT_LIMIT) : DEFAULT_LIMIT;
            QueryDescriptor capped = new QueryDescriptor(
                    descriptor.store(), descriptor.indexOrTable(),
                    descriptor.filters(), descriptor.aggs(),
                    descriptor.timeRange(), effectiveLimit,
                    descriptor.select(), descriptor.groupBy());

            log.info("ad_hoc_query [fallback]: store={} table={} timeRange={} limit={}",
                    capped.store(), capped.indexOrTable(), capped.timeRange(), effectiveLimit);

            queryValidator.validate(capped);
            TabularResult result = queryExecutor.execute(capped);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("columns", result.columns());
            response.put("rows", result.rows());
            response.put("rowCount", result.rows().size());
            response.put("table", capped.indexOrTable());

            rawResults.add(response);

            String json = mapper.writeValueAsString(response);
            if (json.length() > MAX_RESULT_CHARS) {
                json = json.substring(0, MAX_RESULT_CHARS) + "...(truncated, " + result.rows().size() + " total rows)";
            }
            return json;

        } catch (QueryValidationException e) {
            log.warn("ad_hoc_query validation failed [{}]: {}", e.getErrorCode(), e.getMessage());
            return String.format("{\"error\":\"%s\",\"code\":\"%s\"}",
                    e.getMessage().replace("\"", "'"), e.getErrorCode());
        } catch (Exception e) {
            log.error("ad_hoc_query error", e);
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
