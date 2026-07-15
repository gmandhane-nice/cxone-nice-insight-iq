package com.nice.agentic.coach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.BedrockConfig;
import com.nice.agentic.BedrockHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/coach")
public class CoachController {

    private static final Logger log = LoggerFactory.getLogger(CoachController.class);

    private final BedrockHelper bedrockHelper;
    private final BedrockConfig.BedrockSettings bedrockSettings;
    private final ObjectMapper objectMapper;

    @Value("${agentic.coach.haiku-model-id:us.anthropic.claude-haiku-4-5-20251001-v1:0}")
    private String haikuModelId;

    @Value("${agentic.coach.fallback-haiku-model-id:anthropic.claude-haiku-20240307-v1:0}")
    private String fallbackHaikuModelId;

    @Value("${agentic.coach.max-tokens:512}")
    private int maxTokens;

    @Value("${agentic.coach.temperature:0.4}")
    private float temperature;

    private final List<SseEmitter> nudgeSubscribers = new CopyOnWriteArrayList<>();

    public CoachController(BedrockHelper bedrockHelper,
                           BedrockConfig.BedrockSettings bedrockSettings,
                           ObjectMapper objectMapper) {
        this.bedrockHelper = bedrockHelper;
        this.bedrockSettings = bedrockSettings;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Inner records
    // -------------------------------------------------------------------------

    public record TriggerRequest(
            String agentId,
            String agentName,
            String metric,
            double currentValue,
            double baselineValue,
            double deltaPct,
            String skillName,
            List<String> recentContactTypes,
            String moduleReference,
            String moduleTitle
    ) {}

    public record NudgeResponse(
            String agentId,
            String nudge,
            String metric,
            double deltaPct,
            String actionLink,
            String generatedAt
    ) {}

    // -------------------------------------------------------------------------
    // POST /coach/trigger
    // -------------------------------------------------------------------------

    @PostMapping("/trigger")
    public NudgeResponse trigger(@RequestBody TriggerRequest request) throws IOException {
        log.info("Coach trigger for agent={} metric={} delta=+{}%",
                request.agentId(), request.metric(), request.deltaPct());

        // Build context string
        String contactTypes = request.recentContactTypes() != null
                ? String.join(", ", request.recentContactTypes())
                : "";
        String context = String.format(
                """
                Agent: %s (ID: %s)
                Skill: %s
                Metric: %s
                Current value: %ss | Baseline: %ss | Delta: +%.1f%%
                Recent contact types: %s
                Relevant training module: %s — %s
                """,
                request.agentName(), request.agentId(),
                request.skillName(),
                request.metric(),
                (long) request.currentValue(), (long) request.baselineValue(), request.deltaPct(),
                contactTypes,
                request.moduleReference(), request.moduleTitle()
        );

        // Load system prompt
        String systemPrompt = loadClasspathText("prompts/coach-nudge-v1.txt");

        // Call Bedrock (try primary model, fall back if it fails)
        String nudge = callBedrock(systemPrompt, context);

        // Tone-critic check
        runToneCriticCheck(nudge);

        // Build action link
        String actionLink = "/training/modules/" + request.moduleReference();

        NudgeResponse nudgeResponse = new NudgeResponse(
                request.agentId(),
                nudge,
                request.metric(),
                request.deltaPct(),
                actionLink,
                Instant.now().toString()
        );

        // Broadcast to SSE subscribers
        broadcastNudge(nudgeResponse);

        return nudgeResponse;
    }

    // -------------------------------------------------------------------------
    // GET /coach/nudges/stream  (SSE)
    // -------------------------------------------------------------------------

    @GetMapping(value = "/nudges/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToNudges() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        nudgeSubscribers.add(emitter);
        emitter.onCompletion(() -> nudgeSubscribers.remove(emitter));
        emitter.onTimeout(() -> nudgeSubscribers.remove(emitter));
        emitter.onError(e -> nudgeSubscribers.remove(emitter));
        // Send a heartbeat so the browser does not close the connection immediately
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception ignored) {}
        log.info("New SSE subscriber registered, total={}", nudgeSubscribers.size());
        return emitter;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String callBedrock(String systemPrompt, String context) {
        try {
            return bedrockHelper.singleTurn(systemPrompt, context, haikuModelId, maxTokens, temperature);
        } catch (RuntimeException primary) {
            log.warn("Primary Haiku model {} failed, trying fallback {}: {}",
                    haikuModelId, fallbackHaikuModelId, primary.getMessage());
            try {
                return bedrockHelper.singleTurn(systemPrompt, context, fallbackHaikuModelId, maxTokens, temperature);
            } catch (RuntimeException fallback) {
                log.error("Fallback Haiku model also failed: {}", fallback.getMessage());
                throw new RuntimeException(
                        "Both Haiku models failed. Primary: " + primary.getMessage()
                        + " | Fallback: " + fallback.getMessage(), fallback);
            }
        }
    }

    private void runToneCriticCheck(String nudge) {
        try {
            String toneCriticPrompt = loadClasspathText("prompts/coach-tone-critic-v1.txt");
            String criticResponse = bedrockHelper.singleTurn(
                    toneCriticPrompt, nudge, haikuModelId, 256, 0.0f);

            // Parse the JSON response
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(criticResponse, Map.class);
            boolean approved = Boolean.TRUE.equals(parsed.get("approved"));
            Object reasonObj = parsed.get("reason");
            String reason = reasonObj != null ? reasonObj.toString() : "";

            if (!approved) {
                log.warn("Tone critic did NOT approve nudge. Reason: {}. Returning nudge anyway for demo.", reason);
            } else {
                log.info("Tone critic approved nudge. Reason: {}", reason);
            }
        } catch (Exception e) {
            log.warn("Tone critic check failed (non-blocking): {}", e.getMessage());
        }
    }

    private void broadcastNudge(NudgeResponse nudge) {
        String json;
        try {
            json = objectMapper.writeValueAsString(nudge);
        } catch (Exception e) {
            log.error("Failed to serialize NudgeResponse for SSE broadcast: {}", e.getMessage());
            return;
        }

        final String jsonPayload = json;
        nudgeSubscribers.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("nudge").data(jsonPayload));
                return false;
            } catch (Exception e) {
                log.debug("Removing dead SSE emitter: {}", e.getMessage());
                return true;
            }
        });
    }

    private String loadClasspathText(String path) throws IOException {
        return new String(
                new ClassPathResource(path).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }
}
