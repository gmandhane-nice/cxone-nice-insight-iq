package com.nice.agentic.agents;

import com.nice.agentic.widget.WidgetPayloadResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Background Watchdog - continuously monitors metrics and automatically triggers alerts/actions.
 * <p>
 * Runs every 60 seconds (configurable) and checks:
 * - Queue SLA compliance
 * - Agent AHT drift
 * <p>
 * When anomalies detected:
 * - For agent-level issues: triggers coach nudge automatically
 * - For queue-level issues: pushes risk alert to supervisors via SSE
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "agentic.watchdog.enabled", havingValue = "true", matchIfMissing = false)
public class WatchdogScheduler {

    private static final Logger log = LoggerFactory.getLogger(WatchdogScheduler.class);

    private final WidgetPayloadResolver widgetResolver;
    private final List<SseEmitter> alertEmitters = new CopyOnWriteArrayList<>();

    @Value("${agentic.watchdog.sla-threshold:0.85}")
    private double slaThreshold;

    @Value("${agentic.watchdog.aht-drift-threshold-pct:20}")
    private int ahtDriftThresholdPct;

    @Value("${agentic.watchdog.auto-coach-enabled:true}")
    private boolean autoCoachEnabled;

    public WatchdogScheduler(WidgetPayloadResolver widgetResolver) {
        this.widgetResolver = widgetResolver;
    }

    /**
     * Runs every 60 seconds to check for anomalies.
     */
    @Scheduled(fixedDelayString = "${agentic.watchdog.interval-ms:60000}")
    public void checkMetrics() {
        try {
            log.debug("Watchdog running scheduled check");

            // Check queue SLA compliance
            checkQueueSla();

            // Check agent AHT drift
            checkAgentAhtDrift();

        } catch (Exception e) {
            log.error("Watchdog check failed", e);
        }
    }

    /**
     * Checks queue SLA compliance and pushes alerts if below threshold.
     */
    private void checkQueueSla() {
        // TODO: Implement queue SLA check using WidgetPayloadResolver
        // Example: Get queue metrics and check if SLA < threshold
        // If below threshold, broadcast alert

        // For now, this is a placeholder
        log.debug("Checking queue SLA compliance (threshold: {})", slaThreshold);
    }

    /**
     * Checks agent AHT drift and triggers coach nudge if needed.
     */
    private void checkAgentAhtDrift() {
        // TODO: Implement AHT drift check using WidgetPayloadResolver
        // Example: Compare current AHT vs baseline for each agent
        // If drift > threshold, trigger coach nudge

        log.debug("Checking agent AHT drift (threshold: {}%)", ahtDriftThresholdPct);
    }

    /**
     * Broadcasts an alert to all subscribed SSE clients.
     *
     * @param alertType type of alert (sla_violation, aht_drift, etc.)
     * @param message   alert message
     * @param data      additional alert data
     */
    private void broadcastAlert(String alertType, String message, Map<String, Object> data) {
        log.info("Broadcasting watchdog alert: {} - {}", alertType, message);

        Map<String, Object> alert = Map.of(
                "type", alertType,
                "message", message,
                "timestamp", System.currentTimeMillis(),
                "data", data
        );

        // Remove dead emitters while sending
        alertEmitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("watchdog_alert")
                        .data(alert));
                return false; // keep emitter
            } catch (Exception e) {
                log.debug("Removing dead SSE emitter");
                return true; // remove emitter
            }
        });
    }

    /**
     * Registers an SSE emitter to receive watchdog alerts.
     */
    public void registerAlertEmitter(SseEmitter emitter) {
        alertEmitters.add(emitter);
        log.info("Registered new alert subscriber (total: {})", alertEmitters.size());

        emitter.onCompletion(() -> {
            alertEmitters.remove(emitter);
            log.info("Alert subscriber disconnected (remaining: {})", alertEmitters.size());
        });

        emitter.onTimeout(() -> {
            alertEmitters.remove(emitter);
            log.info("Alert subscriber timeout (remaining: {})", alertEmitters.size());
        });
    }
}
