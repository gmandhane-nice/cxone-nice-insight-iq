package com.nice.agentic.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.query.SnowflakeExecutor;
import com.nice.agentic.query.SqlGenerator;
import com.nice.agentic.tools.AdHocQueryTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Multi-Agent Orchestrator - coordinates the full RCA investigation pipeline.
 * <p>
 * Pipeline:
 * 1. Orchestrator plans (decompose question into sub-tasks)
 * 2. Sub-agents investigate in parallel (realtime, historical, context)
 * 3. Reasoning agent correlates evidence and generates hypotheses
 * 4. Recommendation agent produces actionable recommendations
 * <p>
 * Emits {@link AgentEvent}s at each stage for SSE streaming to the UI.
 */
@Service
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);

    private final OrchestratorAgent orchestrator;
    private final RealTimeAgent realTimeAgent;
    private final HistoricalAgent historicalAgent;
    private final ContextAgent contextAgent;
    private final ReasoningAgent reasoningAgent;
    private final RecommendationAgent recommendationAgent;
    private final SqlGenerator sqlGenerator;
    private final SnowflakeExecutor snowflakeExecutor;
    private final BedrockRuntimeClient bedrock;
    private final String modelId;
    private final ObjectMapper mapper = new ObjectMapper();

    public MultiAgentOrchestrator(OrchestratorAgent orchestrator,
                                  RealTimeAgent realTimeAgent,
                                  HistoricalAgent historicalAgent,
                                  ContextAgent contextAgent,
                                  ReasoningAgent reasoningAgent,
                                  RecommendationAgent recommendationAgent,
                                  SqlGenerator sqlGenerator,
                                  SnowflakeExecutor snowflakeExecutor,
                                  BedrockRuntimeClient bedrock,
                                  @Value("${agentic.bedrock.model-id}") String modelId) {
        this.orchestrator = orchestrator;
        this.realTimeAgent = realTimeAgent;
        this.historicalAgent = historicalAgent;
        this.contextAgent = contextAgent;
        this.reasoningAgent = reasoningAgent;
        this.recommendationAgent = recommendationAgent;
        this.sqlGenerator = sqlGenerator;
        this.snowflakeExecutor = snowflakeExecutor;
        this.bedrock = bedrock;
        this.modelId = modelId;
    }

    /**
     * Runs the full multi-agent investigation pipeline.
     *
     * @param question the user's natural-language question
     * @param onEvent  callback for SSE streaming (invoked for each phase update)
     * @return final investigation result as JSON string
     */
    public String investigate(String question, Consumer<AgentEvent> onEvent) {
        try {
            AdHocQueryTool.clearResults();

            // Phase 1: Planning
            onEvent.accept(new AgentEvent("planning", "orchestrator", "started", "Analyzing question and creating investigation plan", null));
            String planJson = orchestrator.plan(question);
            onEvent.accept(new AgentEvent("planning", "orchestrator", "completed", "Investigation plan created", planJson));

            // Parse plan to get sub-agent tasks and query type
            Plan plan = parsePlan(planJson);
            if (plan.tasks.isEmpty()) {
                log.warn("Orchestrator produced empty task list");
                return "{\"summary\":\"Unable to create investigation plan\",\"recommendations\":[]}";
            }

            boolean isDataQuery = "data_query".equalsIgnoreCase(plan.queryType);
            log.info("Query classified as: {} (tasks={})", plan.queryType, plan.tasks.size());

            // DATA QUERY: LLM generates SQL → execute → LLM summarizes → table + summary
            if (isDataQuery) {
                try {
                    // Step 1: Generate SQL
                    onEvent.accept(new AgentEvent("investigating", "sql-generator", "started",
                            "Generating SQL query from your question", null));

                    String sql = sqlGenerator.generateSql(question);
                    log.info("Data query SQL: {}", sql);
                    onEvent.accept(new AgentEvent("investigating", "sql-generator", "completed",
                            "SQL generated, executing against Snowflake", null));

                    // Step 2: Execute
                    List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
                    List<String> columns = rows.isEmpty()
                            ? List.of()
                            : new ArrayList<>(rows.get(0).keySet());

                    // If we hit the LIMIT, get actual total count
                    int actualTotal = rows.size();
                    if (rows.size() == 200) {
                        try {
                            String countSql = "SELECT COUNT(*) AS TOTAL FROM (" +
                                    sql.replaceAll("(?i)\\bLIMIT\\s+\\d+", "") + ")";
                            List<Map<String, Object>> countResult = snowflakeExecutor.execute(countSql);
                            if (!countResult.isEmpty()) {
                                Object totalVal = countResult.get(0).values().iterator().next();
                                actualTotal = Integer.parseInt(totalVal.toString());
                                log.info("Actual total count: {} (displayed: 200)", actualTotal);
                            }
                        } catch (Exception e) {
                            log.warn("Count query failed, using row count: {}", e.getMessage());
                        }
                    }

                    Map<String, Object> tableData = new LinkedHashMap<>();
                    tableData.put("columns", columns);
                    tableData.put("rows", rows);
                    tableData.put("rowCount", actualTotal);
                    tableData.put("sql", sql);

                    // Step 3: LLM analyzes results — RCA if "why" question, otherwise summary
                    boolean isWhyQuestion = question.toLowerCase().matches(".*\\b(why|cause|reason|root cause|investigate|explain)\\b.*");
                    String analysisLabel = isWhyQuestion ? "Root cause analysis" : "Analyzing query results";
                    onEvent.accept(new AgentEvent("reasoning", "analyzer", "started",
                            analysisLabel, null));

                    String dataJson = mapper.writeValueAsString(rows.size() > 20
                            ? rows.subList(0, 20) : rows);

                    String totalNote = (actualTotal > rows.size())
                            ? "\n\nIMPORTANT: The query returned " + rows.size() + " rows (display limit), but the ACTUAL TOTAL is " + actualTotal + " rows. Always report the actual total (" + actualTotal + "), not the display limit."
                            : "";

                    String summaryPrompt;
                    if (isWhyQuestion) {
                        summaryPrompt = "User asked: \"" + question + "\"\n\n" +
                                "SQL executed: " + sql + "\n\n" +
                                "Results (" + actualTotal + " total rows, showing " + rows.size() + ", columns: " + columns + "):\n" + dataJson +
                                totalNote +
                                "\n\nYou are a contact center RCA analyst. Based on the data above, provide a root cause analysis." +
                                " Structure your response as plain text with:" +
                                "\n1. KEY FINDING: One sentence stating the main observation. Always mention the ACTUAL TOTAL count." +
                                "\n2. ROOT CAUSE: What the data shows is causing the issue (e.g., high hold time, excessive ACW, long talk time)." +
                                "\n3. EVIDENCE: 2-3 specific data points from the results that support your conclusion." +
                                "\n4. RECOMMENDATION: One actionable step the supervisor should take." +
                                "\n\nBe direct and specific with numbers from the data. Do NOT use markdown, code fences, or JSON.";
                    } else {
                        summaryPrompt = "User asked: \"" + question + "\"\n\n" +
                                "SQL executed: " + sql + "\n\n" +
                                "Results (" + actualTotal + " total rows, showing " + rows.size() + ", columns: " + columns + "):\n" + dataJson +
                                totalNote +
                                "\n\nProvide a brief 2-3 sentence plain-text summary of the key findings." +
                                " Always report the ACTUAL TOTAL count (" + actualTotal + "), not the display limit." +
                                " Focus on what a contact center supervisor would care about." +
                                " Be direct and specific with numbers." +
                                " Do NOT use markdown, code fences, JSON, or bullet points — just plain sentences.";
                    }

                    String summary = callBedrockForAnalysis(summaryPrompt);

                    onEvent.accept(new AgentEvent("reasoning", "analyzer", "completed",
                            "Analysis complete", summary));

                    String dataResult = buildDirectDataResponse(tableData, summary);
                    onEvent.accept(new AgentEvent("complete", "orchestrator", "completed", "Data retrieved", dataResult));
                    return dataResult;

                } catch (Exception e) {
                    log.warn("Data query failed: {}", e.getMessage());
                    // Return a user-friendly error with the SQL that was attempted
                    Map<String, Object> errorResponse = new LinkedHashMap<>();
                    errorResponse.put("type", "data_result");
                    errorResponse.put("summary", "Query failed: " + e.getMessage());
                    errorResponse.put("tables", List.of());
                    String errorJson = mapper.writeValueAsString(errorResponse);
                    onEvent.accept(new AgentEvent("complete", "orchestrator", "completed", "Query failed", errorJson));
                    return errorJson;
                }
            }

            // RCA FLOW: Parallel investigation by sub-agents
            onEvent.accept(new AgentEvent("investigating", "all", "started",
                    "Starting parallel investigations", null));

            Map<String, CompletableFuture<String>> futures = new HashMap<>();
            Map<String, Object> context = Map.of("tenantId", "default");

            for (Task task : plan.tasks) {
                SubAgent agent = getSubAgent(task.agent);
                if (agent != null) {
                    onEvent.accept(new AgentEvent("investigating", agent.name(), "started", "Investigating: " + task.briefing, null));
                    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                        String result = agent.investigate(task.briefing, context);
                        onEvent.accept(new AgentEvent("investigating", agent.name(), "completed", "Investigation complete", result));
                        return result;
                    });
                    futures.put(agent.name(), future);
                }
            }

            // Wait for all sub-agents to complete
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();

            // Collect evidence from all agents
            Map<String, String> evidence = futures.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().join()));

            log.info("Collected evidence from {} agents", evidence.size());

            // RCA FLOW: Full reasoning + recommendation pipeline
            // Phase 3: Reasoning - correlate evidence and generate hypotheses
            onEvent.accept(new AgentEvent("reasoning", "reasoning", "started", "Correlating evidence and generating hypotheses", null));
            String hypothesesJson = reasoningAgent.correlate(evidence);
            onEvent.accept(new AgentEvent("reasoning", "reasoning", "completed", "Hypotheses generated", hypothesesJson));

            // Phase 4: Recommendations - produce actionable recommendations
            onEvent.accept(new AgentEvent("recommending", "recommendation", "started", "Generating actionable recommendations", null));
            String recommendationsJson = recommendationAgent.recommend(hypothesesJson);
            onEvent.accept(new AgentEvent("recommending", "recommendation", "completed", "Recommendations generated", recommendationsJson));

            // Phase 5: Complete — include evidence as proof alongside recommendations
            String finalResult = buildRcaResponse(evidence, recommendationsJson);
            onEvent.accept(new AgentEvent("complete", "orchestrator", "completed", "Investigation complete", finalResult));

            return finalResult;

        } catch (Exception e) {
            log.error("Multi-agent investigation failed", e);
            onEvent.accept(new AgentEvent("error", "orchestrator", "error", "Investigation failed: " + e.getMessage(), null));
            return "{\"summary\":\"Investigation failed: " + e.getMessage() + "\",\"recommendations\":[]}";
        }
    }

    private String callBedrockForAnalysis(String prompt) {
        try {
            ConverseRequest request = ConverseRequest.builder()
                    .modelId(modelId)
                    .system(SystemContentBlock.fromText("You are a contact center analyst. Provide clear, concise analysis. Never use markdown, code fences, or JSON. Write plain text only."))
                    .messages(Message.builder()
                            .role(ConversationRole.USER)
                            .content(ContentBlock.fromText(prompt))
                            .build())
                    .inferenceConfig(InferenceConfiguration.builder()
                            .maxTokens(2048)
                            .temperature(0.2f)
                            .build())
                    .build();

            ConverseResponse response = bedrock.converse(request);
            Message assistant = response.output().message();
            StringBuilder text = new StringBuilder();
            for (ContentBlock block : assistant.content()) {
                if (block.text() != null) text.append(block.text());
            }
            String result = text.toString().trim();
            log.info("Analysis LLM returned {} chars", result.length());
            return result;
        } catch (Exception e) {
            log.error("Analysis LLM call failed: {}", e.getMessage());
            return "Analysis could not be generated: " + e.getMessage();
        }
    }

    private String buildDirectDataResponse(Map<String, Object> tableData, String summary) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "data_result");
            response.put("summary", summary);
            response.put("tables", List.of(tableData));
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"data_result\",\"summary\":\"\",\"tables\":[]}";
        }
    }

    private String buildDataResponse(Map<String, String> evidence, List<Map<String, Object>> rawResults) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "data_result");
            response.put("summary", evidence);
            response.put("tables", rawResults);
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"data_result\",\"summary\":{},\"tables\":[]}";
        }
    }

    private String buildRcaResponse(Map<String, String> evidence, String recommendationsJson) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "rca_result");
            response.put("evidence", evidence);
            // Parse recommendations if valid JSON, otherwise wrap as string
            try {
                response.put("analysis", mapper.readTree(recommendationsJson));
            } catch (Exception e) {
                response.put("analysis", recommendationsJson);
            }
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return recommendationsJson;
        }
    }

    private Plan parsePlan(String planJson) {
        try {
            String cleaned = stripCodeFences(planJson);
            JsonNode root = mapper.readTree(cleaned);

            String queryType = root.has("queryType") ? root.get("queryType").asText("rca") : "rca";

            JsonNode tasksNode = root.get("tasks");
            if (tasksNode == null || !tasksNode.isArray()) {
                log.warn("Plan JSON missing 'tasks' array");
                return new Plan(queryType, Collections.emptyList());
            }

            List<Task> tasks = new ArrayList<>();
            for (JsonNode taskNode : tasksNode) {
                String agent = taskNode.get("agent").asText();
                String briefing = taskNode.get("briefing").asText();
                tasks.add(new Task(agent, briefing));
            }
            return new Plan(queryType, tasks);

        } catch (JsonProcessingException e) {
            log.error("Failed to parse plan JSON: {}", e.getMessage());
            return new Plan("rca", Collections.emptyList());
        }
    }

    private String stripCodeFences(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    private SubAgent getSubAgent(String agentName) {
        return switch (agentName.toLowerCase()) {
            case "realtime" -> realTimeAgent;
            case "historical" -> historicalAgent;
            case "context" -> contextAgent;
            default -> {
                log.warn("Unknown sub-agent: {}", agentName);
                yield null;
            }
        };
    }

    private record Plan(String queryType, List<Task> tasks) {}
    private record Task(String agent, String briefing) {}
}
