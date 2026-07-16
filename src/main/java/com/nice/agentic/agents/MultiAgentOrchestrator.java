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

            // FAST PATH: classify without LLM call for obvious data queries
            boolean isDataQuery = isObviousDataQuery(question);
            Plan plan = null;

            if (isDataQuery) {
                log.info("Fast-path: classified as data_query (skipping planner)");
                onEvent.accept(new AgentEvent("planning", "orchestrator", "started", "Analyzing question", null));
                onEvent.accept(new AgentEvent("planning", "orchestrator", "completed", "Query classified — executing", null));
            } else {
                // Only call planner for ambiguous/RCA questions
                onEvent.accept(new AgentEvent("planning", "orchestrator", "started", "Analyzing question and creating investigation plan", null));
                String planJson = orchestrator.plan(question);
                onEvent.accept(new AgentEvent("planning", "orchestrator", "completed", "Investigation plan created", planJson));

                plan = parsePlan(planJson);
                if (plan.tasks.isEmpty()) {
                    log.warn("Orchestrator produced empty task list");
                    return "{\"summary\":\"Unable to create investigation plan\",\"recommendations\":[]}";
                }
                isDataQuery = "data_query".equalsIgnoreCase(plan.queryType);
            }

            log.info("Query classified as: {}", isDataQuery ? "data_query" : "rca");

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
                                "\nFormat your response EXACTLY like this (use bullet points with • character):" +
                                "\n\n🔍 KEY FINDING:" +
                                "\n• One clear sentence about the main issue. Include the ACTUAL TOTAL count." +
                                "\n\n⚠️ ROOT CAUSE:" +
                                "\n• What is causing it (be specific — name the metric, channel, or behavior)" +
                                "\n• Supporting detail (e.g., which time period, which channel)" +
                                "\n\n📊 EVIDENCE:" +
                                "\n• Data point 1 with exact numbers" +
                                "\n• Data point 2 with exact numbers" +
                                "\n• Data point 3 with exact numbers (if relevant)" +
                                "\n\n✅ RECOMMENDATION:" +
                                "\n• Concrete action the supervisor should take immediately" +
                                "\n• Expected impact of taking this action" +
                                "\n\nRules:" +
                                "\n- Be specific with numbers (counts, percentages, durations)." +
                                "\n- Each bullet must be one concise sentence — no paragraphs." +
                                "\n- Do NOT use markdown (no **, no ##, no ```). Use plain text with • bullets." +
                                "\n- Use the emoji headers exactly as shown above.";
                    } else {
                        summaryPrompt = "User asked: \"" + question + "\"\n\n" +
                                "SQL executed: " + sql + "\n\n" +
                                "Results (" + actualTotal + " total rows, showing " + rows.size() + ", columns: " + columns + "):\n" + dataJson +
                                totalNote +
                                "\n\nProvide a concise analysis using bullet points." +
                                "\nFormat your response EXACTLY like this:" +
                                "\n\n📊 SUMMARY:" +
                                "\n• Key finding #1 with specific numbers" +
                                "\n• Key finding #2 with specific numbers" +
                                "\n• Key finding #3 (if relevant)" +
                                "\n\n💡 INSIGHT:" +
                                "\n• What this means for the supervisor — one actionable sentence" +
                                "\n\nRules:" +
                                "\n- Always report the ACTUAL TOTAL count (" + actualTotal + "), not the display limit." +
                                "\n- Focus on what a contact center supervisor would care about." +
                                "\n- Be specific with numbers (agents, contacts, durations, percentages)." +
                                "\n- Each bullet is one concise sentence — no paragraphs." +
                                "\n- Do NOT use markdown (no **, no ##, no ```). Use plain text with • bullets." +
                                "\n- Use the emoji headers exactly as shown above.";
                    }

                    String summary = callBedrockForAnalysis(summaryPrompt);
                    List<Map<String, String>> suggestedActions = List.of(); // loaded async by frontend

                    onEvent.accept(new AgentEvent("reasoning", "analyzer", "completed",
                            "Analysis complete", summary));

                    String dataResult = buildDirectDataResponse(tableData, summary, suggestedActions);
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

            // RCA FLOW: Combined reasoning + recommendation + actions in ONE call
            onEvent.accept(new AgentEvent("reasoning", "reasoning", "started", "Analyzing evidence and generating recommendations", null));

            String evidenceText = evidence.entrySet().stream()
                    .map(e -> "=== " + e.getKey().toUpperCase() + " ===\n" + e.getValue())
                    .collect(Collectors.joining("\n\n"));

            String rcaPrompt = "You are a contact center RCA analyst. The user asked: \"" + question + "\"\n\n" +
                    "Below is evidence gathered from multiple investigation agents:\n\n" + evidenceText + "\n\n" +
                    "Provide a comprehensive root cause analysis. Format your response as JSON:\n" +
                    "{\n" +
                    "  \"summary\": \"One sentence root cause summary\",\n" +
                    "  \"recommendations\": [\n" +
                    "    {\"action\": \"what to do\", \"rationale\": \"why\", \"urgency\": \"critical|high|medium\", \"expectedImpact\": \"result\"}\n" +
                    "  ],\n" +
                    "  \"suggestedActions\": [\n" +
                    "    {\"label\": \"short button text\", \"query\": \"follow-up question to ask\"}\n" +
                    "  ]\n" +
                    "}\n\n" +
                    "Rules:\n" +
                    "- summary: one clear sentence about the root cause\n" +
                    "- recommendations: 2-4 actionable items sorted by urgency\n" +
                    "- suggestedActions: 3-4 follow-up queries for evidence/verification\n" +
                    "- Be specific with numbers from the evidence\n" +
                    "- Return ONLY valid JSON, no code fences";

            String rcaResponse = callBedrockForAnalysis(rcaPrompt);
            onEvent.accept(new AgentEvent("reasoning", "reasoning", "completed", "Analysis complete", null));

            // Build final response
            String finalResult = buildRcaResponseFromCombined(evidence, rcaResponse);
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

    private List<Map<String, String>> generateLlmSuggestedActions(String question, String summary, List<String> columns, List<Map<String, Object>> rows) {
        String dataSnippet = "";
        try {
            List<Map<String, Object>> sample = rows.size() > 5 ? rows.subList(0, 5) : rows;
            dataSnippet = mapper.writeValueAsString(sample);
        } catch (Exception ignored) {}

        String prompt = "You are a contact center analytics assistant helping a supervisor investigate issues.\n\n" +
                "The user just asked: \"" + question + "\"\n\n" +
                "The system returned data with columns: " + columns + "\n" +
                "Sample data (first few rows): " + dataSnippet + "\n\n" +
                "Analysis summary: " + summary + "\n\n" +
                "Based on what the user ALREADY received, suggest 3-4 NEXT STEPS the supervisor should take to dig deeper.\n" +
                "DO NOT suggest anything the user already asked or already has in front of them.\n" +
                "Each suggestion must be a concrete query they can run to get EVIDENCE or PROOF.\n\n" +
                "Return ONLY a JSON array with objects having \"label\" (short button text, max 5 words) and \"query\" (the full question to ask the system).\n" +
                "Example: [{\"label\":\"Show daily trend\",\"query\":\"Show daily contact count for PerfUser_0011 over last 7 days\"}]\n\n" +
                "Return ONLY the JSON array, no other text.";

        String response = callBedrockForAnalysis(prompt);
        return parseSuggestedActionsJson(response);
    }

    private List<Map<String, String>> generateLlmRcaSuggestedActions(String question, String recommendationsJson, String hypothesesJson) {
        String prompt = "You are a contact center analytics assistant helping a supervisor investigate root causes.\n\n" +
                "The user asked: \"" + question + "\"\n\n" +
                "The system performed a full root cause analysis and found:\n" + recommendationsJson + "\n\n" +
                "Hypotheses investigated:\n" + hypothesesJson + "\n\n" +
                "Based on this RCA, suggest 3-4 NEXT STEPS the supervisor should take to verify the findings or take action.\n" +
                "Each suggestion should help them get EVIDENCE, PROOF, or take a concrete action.\n" +
                "DO NOT repeat what was already analyzed — suggest what comes NEXT.\n\n" +
                "Return ONLY a JSON array with objects having \"label\" (short button text, max 5 words) and \"query\" (the full question to ask the system).\n" +
                "Example: [{\"label\":\"Show stuck contacts\",\"query\":\"Show all contacts with duration over 1 hour and zero talk time for PerfUser_0011\"}]\n\n" +
                "Return ONLY the JSON array, no other text.";

        String response = callBedrockForAnalysis(prompt);
        return parseSuggestedActionsJson(response);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseSuggestedActionsJson(String response) {
        try {
            String cleaned = response.trim();
            // Strip code fences if present
            cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
            List<Map<String, String>> actions = mapper.readValue(cleaned,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            // Cap at 4 and validate
            List<Map<String, String>> valid = new ArrayList<>();
            for (Map<String, String> action : actions) {
                if (action.containsKey("label") && action.containsKey("query") && valid.size() < 4) {
                    valid.add(Map.of("label", action.get("label"), "query", action.get("query")));
                }
            }
            return valid;
        } catch (Exception e) {
            log.warn("Failed to parse LLM suggested actions: {}", e.getMessage());
            return List.of();
        }
    }


    private String buildDirectDataResponse(Map<String, Object> tableData, String summary, List<Map<String, String>> suggestedActions) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "data_result");
            response.put("summary", summary);
            response.put("tables", List.of(tableData));
            if (suggestedActions != null && !suggestedActions.isEmpty()) {
                response.put("suggestedActions", suggestedActions);
            }
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

    private String buildRcaResponse(Map<String, String> evidence, String recommendationsJson, List<Map<String, String>> suggestedActions) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "rca_result");
            response.put("evidence", evidence);
            try {
                String cleaned = stripCodeFences(recommendationsJson);
                response.put("analysis", mapper.readTree(cleaned));
            } catch (Exception e) {
                response.put("analysis", Map.of("summary", recommendationsJson));
            }
            if (suggestedActions != null && !suggestedActions.isEmpty()) {
                response.put("suggestedActions", suggestedActions);
            }
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return recommendationsJson;
        }
    }


    private boolean isObviousDataQuery(String question) {
        String q = question.toLowerCase().trim();
        // Data query patterns: show, list, get, give, which, what, how many, top, compare
        if (q.matches("^(show|list|get|give|display|find|fetch|tell|count|check)\\b.*")) return true;
        if (q.matches("^(which|what|how many|how much|top \\d|compare|who)\\b.*")) return true;
        if (q.matches(".*\\b(by skill|by agent|by team|by channel|per agent|per skill|this week|today|yesterday|last \\d)\\b.*")) return true;
        if (q.matches(".*\\b(aht|handle time|contacts|volume|sla|abandonment|staffing|login|session)\\b.*")
                && !q.matches(".*\\b(why is|what caused|investigate|root cause)\\b.*")) return true;
        // Follow-up/recommendation patterns — treat as data query
        if (q.matches(".*\\b(recommend|suggestion|improve|fix|action|what should|how to fix|how can we)\\b.*")) return true;
        // RCA patterns that should NOT fast-path
        if (q.matches(".*\\b(why is .* (increasing|decreasing|spiking|dropping|failing))\\b.*")) return false;
        if (q.matches(".*\\b(root cause|investigate|what caused the)\\b.*")) return false;
        // Default: if it contains "why" with a systemic term, it's RCA
        if (q.startsWith("why") && q.matches(".*\\b(queue|sla|abandon|service level)\\b.*")) return false;
        // Default to data query
        return true;
    }

    private String buildRcaResponseFromCombined(Map<String, String> evidence, String rcaResponse) {
        try {
            String cleaned = stripCodeFences(rcaResponse);
            JsonNode root = mapper.readTree(cleaned);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "rca_result");
            response.put("evidence", evidence);
            response.put("analysis", Map.of(
                    "summary", root.has("summary") ? root.get("summary").asText() : "Analysis complete",
                    "recommendations", root.has("recommendations") ? mapper.readTree(root.get("recommendations").toString()) : List.of()
            ));
            if (root.has("suggestedActions")) {
                response.put("suggestedActions", mapper.readTree(root.get("suggestedActions").toString()));
            }
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("Failed to parse combined RCA response, wrapping as summary: {}", e.getMessage());
            try {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("type", "rca_result");
                response.put("evidence", evidence);
                response.put("analysis", Map.of("summary", rcaResponse));
                return mapper.writeValueAsString(response);
            } catch (JsonProcessingException ex) {
                return rcaResponse;
            }
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
