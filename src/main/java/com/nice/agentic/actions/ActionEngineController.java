package com.nice.agentic.actions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.*;

/**
 * Lightweight Action Engine — generates contextual follow-up actions using Haiku (fast model).
 * Called async from the frontend AFTER the main result renders, so it never blocks the user.
 */
@RestController
@RequestMapping("/actions")
public class ActionEngineController {

    private static final Logger log = LoggerFactory.getLogger(ActionEngineController.class);

    private final BedrockRuntimeClient bedrock;
    private final String modelId;
    private final ObjectMapper mapper = new ObjectMapper();

    public ActionEngineController(BedrockRuntimeClient bedrock,
                                  @Value("${agentic.bedrock.haiku-model-id:}") String haikuModelId,
                                  @Value("${agentic.bedrock.model-id}") String mainModelId) {
        this.bedrock = bedrock;
        this.modelId = (haikuModelId != null && !haikuModelId.isEmpty()) ? haikuModelId : mainModelId;
        log.info("ActionEngine using model: {}", this.modelId);
    }

    @PostMapping("/suggest")
    public Map<String, Object> suggest(@RequestBody ActionRequest request) {
        long start = System.currentTimeMillis();
        try {
            String prompt = buildPrompt(request);

            ConverseRequest req = ConverseRequest.builder()
                    .modelId(modelId)
                    .system(SystemContentBlock.fromText(
                            "You are a contact center analytics assistant. Return ONLY a JSON array of follow-up actions. No other text."))
                    .messages(Message.builder()
                            .role(ConversationRole.USER)
                            .content(ContentBlock.fromText(prompt))
                            .build())
                    .inferenceConfig(InferenceConfiguration.builder()
                            .maxTokens(512)
                            .temperature(0.3f)
                            .build())
                    .build();

            ConverseResponse response = bedrock.converse(req);
            String text = "";
            for (ContentBlock block : response.output().message().content()) {
                if (block.text() != null) text += block.text();
            }

            List<Map<String, String>> actions = parseActions(text.trim());
            long elapsed = System.currentTimeMillis() - start;
            log.info("ActionEngine generated {} actions in {}ms", actions.size(), elapsed);

            return Map.of("actions", actions, "latencyMs", elapsed);

        } catch (Exception e) {
            log.error("ActionEngine failed: {}", e.getMessage());
            return Map.of("actions", List.of(), "error", e.getMessage());
        }
    }

    private String buildPrompt(ActionRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("The user asked: \"").append(request.question).append("\"\n\n");

        if (request.summary != null && !request.summary.isEmpty()) {
            sb.append("System returned this analysis:\n").append(request.summary).append("\n\n");
        }
        if (request.columns != null && !request.columns.isEmpty()) {
            sb.append("Data columns: ").append(request.columns).append("\n\n");
        }
        if (request.resultType != null) {
            sb.append("Result type: ").append(request.resultType).append("\n\n");
        }

        sb.append("Suggest 3-4 NEXT STEPS the supervisor should take to dig deeper.\n");
        sb.append("DO NOT suggest anything the user already asked or already has.\n");
        sb.append("Each action should be a concrete query they can run.\n\n");
        sb.append("Return ONLY a JSON array: [{\"label\":\"short text max 5 words\",\"query\":\"full question to ask the system\"}]");

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseActions(String text) {
        try {
            String cleaned = text.trim();
            if (cleaned.startsWith("```")) {
                int firstNl = cleaned.indexOf('\n');
                if (firstNl > 0) cleaned = cleaned.substring(firstNl + 1);
                if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
            List<Map<String, String>> actions = mapper.readValue(cleaned, List.class);
            List<Map<String, String>> valid = new ArrayList<>();
            for (Map<String, String> a : actions) {
                if (a.containsKey("label") && a.containsKey("query")) {
                    valid.add(a);
                }
            }
            return valid.size() > 4 ? valid.subList(0, 4) : valid;
        } catch (Exception e) {
            log.warn("Failed to parse actions JSON: {}", e.getMessage());
            return List.of();
        }
    }

    public static class ActionRequest {
        public String question;
        public String summary;
        public List<String> columns;
        public String resultType;
    }
}
