package com.nice.agentic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.agents.AgentEvent;
import com.nice.agentic.agents.MultiAgentOrchestrator;
import com.nice.agentic.agents.WatchdogScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * POST /rca/v1/ask/stream — SSE streaming endpoint for multi-agent investigation.
 *
 * Emits events showing the multi-agent flow:
 * <ol>
 *   <li>{@code planning} — orchestrator creates investigation plan</li>
 *   <li>{@code investigating} — sub-agents (realtime, historical, context) investigate in parallel</li>
 *   <li>{@code reasoning} — reasoning agent correlates evidence and generates hypotheses</li>
 *   <li>{@code recommending} — recommendation agent produces actionable recommendations</li>
 *   <li>{@code complete} — final answer</li>
 * </ol>
 */
@RestController
@RequestMapping("/rca/v1")
public class RcaStreamController {

    private static final Logger log = LoggerFactory.getLogger(RcaStreamController.class);

    private static final int RESULT_TRUNCATE_CHARS = 500;

    @Deprecated
    private final RcaAgent agent; // Keep for backward compatibility
    private final MultiAgentOrchestrator orchestrator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    @Autowired(required = false)
    private WatchdogScheduler watchdogScheduler;

    public RcaStreamController(RcaAgent agent, MultiAgentOrchestrator orchestrator) {
        this.agent = agent;
        this.orchestrator = orchestrator;
    }

    /**
     * Multi-agent streaming endpoint (GET for EventSource compatibility).
     */
    @GetMapping("/ask/stream")
    public SseEmitter askStreamGet(@RequestParam("q") String question) {
        return runMultiAgentStream(question);
    }

    /**
     * Multi-agent streaming endpoint (POST for programmatic clients).
     */
    @PostMapping("/ask/stream")
    public SseEmitter askStream(@RequestBody RcaController.AskRequest request) {
        return runMultiAgentStream(request.question());
    }

    private SseEmitter runMultiAgentStream(String question) {
        SseEmitter emitter = new SseEmitter(180_000L); // 3 minutes for multi-agent flow

        // Send heartbeat every 10s to keep connection alive during long Bedrock calls
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception ignored) {}
        }, 5, 10, TimeUnit.SECONDS);

        emitter.onCompletion(() -> heartbeat.cancel(false));
        emitter.onTimeout(() -> heartbeat.cancel(false));
        emitter.onError(e -> heartbeat.cancel(false));

        executor.execute(() -> {
            try {
                String result = orchestrator.investigate(question, event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.phase())
                                .data(mapper.writeValueAsString(Map.of(
                                        "agentName", event.agentName(),
                                        "status", event.status(),
                                        "detail", event.detail(),
                                        "data", event.data() != null ? event.data() : ""
                                ))));
                    } catch (Exception e) {
                        log.warn("SSE send error during streaming: {}", e.getMessage());
                    }
                });

                emitter.send(SseEmitter.event()
                        .name("final")
                        .data(result));

                emitter.complete();

            } catch (Exception e) {
                log.error("SSE multi-agent error", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(toJson(Map.of("error", e.getMessage() != null ? e.getMessage() : "unknown error"))));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Legacy single-agent endpoint (deprecated, kept for backward compatibility).
     */
    @Deprecated
    @PostMapping("/ask/stream/legacy")
    public SseEmitter askStreamLegacy(@RequestBody RcaController.AskRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);

        executor.execute(() -> {
            try {
                // 1. thinking event
                emitter.send(SseEmitter.event()
                        .name("thinking")
                        .data(toJson(Map.of("status", "Investigating…"))));

                // 2. Run agent with streaming callback
                RcaAgent.AgentResult result = agent.askStream(request.question(), entry -> {
                    try {
                        if ("tool_call".equals(entry.type())) {
                            // emit tool_call
                            emitter.send(SseEmitter.event()
                                    .name("tool_call")
                                    .data(toJson(Map.of(
                                            "tool", entry.name(),
                                            "input", entry.input() != null ? entry.input() : ""))));
                            // emit tool_result
                            String truncated = entry.output() != null
                                    ? entry.output().substring(0, Math.min(entry.output().length(), RESULT_TRUNCATE_CHARS))
                                    : "";
                            emitter.send(SseEmitter.event()
                                    .name("tool_result")
                                    .data(toJson(Map.of(
                                            "tool", entry.name(),
                                            "result", truncated))));
                        }
                        // final entry is handled below after askStream returns
                    } catch (Exception e) {
                        log.warn("SSE send error during streaming: {}", e.getMessage());
                    }
                });

                // 3. final event — full AgentResult JSON
                emitter.send(SseEmitter.event()
                        .name("final")
                        .data(mapper.writeValueAsString(result)));

                emitter.complete();

            } catch (Exception e) {
                log.error("SSE agent error", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(toJson(Map.of("error", e.getMessage() != null ? e.getMessage() : "unknown error"))));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * SSE endpoint for receiving watchdog alerts in real-time.
     * <p>
     * Clients subscribe to this endpoint to receive:
     * - SLA violation alerts
     * - AHT drift alerts
     * - Auto-coach notifications
     */
    @GetMapping("/alerts/stream")
    public SseEmitter alertsStream() {
        if (watchdogScheduler == null) {
            log.warn("Watchdog scheduler not enabled, alerts stream unavailable");
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(toJson(Map.of("error", "Watchdog not enabled"))));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // Keep connection open indefinitely
        watchdogScheduler.registerAlertEmitter(emitter);

        log.info("New client subscribed to watchdog alerts");

        return emitter;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }
}
