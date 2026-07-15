package com.nice.agentic.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nice.agentic.widget.WidgetPayloadResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/risk")
public class RiskController {

    private static final Logger log = LoggerFactory.getLogger(RiskController.class);

    private final ObjectMapper objectMapper;

    /**
     * Injected lazily so the application still starts if Agent B's bean is not yet registered.
     */
    private final WidgetPayloadResolver widgetPayloadResolver;

    private Map<String, Object> snapshotTemplate;

    public RiskController(ObjectMapper objectMapper,
                          @Lazy WidgetPayloadResolver widgetPayloadResolver) {
        this.objectMapper = objectMapper;
        this.widgetPayloadResolver = widgetPayloadResolver;
    }

    @PostConstruct
    public void loadSnapshot() {
        try {
            ClassPathResource resource = new ClassPathResource("fixtures/risk-snapshot.json");
            //noinspection unchecked
            snapshotTemplate = objectMapper.readValue(
                    resource.getInputStream(), Map.class);
            log.info("Loaded risk-snapshot.json with {} skills",
                    ((List<?>) snapshotTemplate.getOrDefault("skills", List.of())).size());
        } catch (IOException e) {
            log.error("Failed to load risk-snapshot.json — using empty snapshot: {}", e.getMessage());
            snapshotTemplate = new HashMap<>();
        }
    }

    // -------------------------------------------------------------------------
    // GET /risk/snapshot
    // -------------------------------------------------------------------------

    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() {
        // Deep-copy the template so mutations don't affect subsequent calls
        Map<String, Object> result = deepCopySnapshot(snapshotTemplate);

        // Attempt to enrich the Banking skill with a live queue depth
        try {
            Map<String, Object> liveQueueState = widgetPayloadResolver.resolve(
                    "queue_state", Map.of("scope", "Banking"));

            Object liveDepth = liveQueueState.get("queueDepth");
            if (liveDepth != null) {
                enrichBankingSkill(result, liveDepth);
                log.info("Enriched Banking skill queueDepth with live value: {}", liveDepth);
            }
        } catch (Exception e) {
            log.warn("Could not enrich snapshot with live queue state (returning static data): {}", e.getMessage());
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void enrichBankingSkill(Map<String, Object> snapshot, Object liveQueueDepth) {
        Object skillsRaw = snapshot.get("skills");
        if (!(skillsRaw instanceof List<?> skills)) return;

        for (Object skillObj : skills) {
            if (!(skillObj instanceof Map<?, ?> skill)) continue;
            Object name = skill.get("skillName");
            if ("Banking".equals(name)) {
                ((Map<String, Object>) skill).put("queueDepth", liveQueueDepth);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopySnapshot(Map<String, Object> source) {
        try {
            // Serialize + deserialize for a clean deep copy
            byte[] bytes = objectMapper.writeValueAsBytes(source);
            return objectMapper.readValue(bytes, Map.class);
        } catch (IOException e) {
            log.warn("Deep copy of snapshot failed, returning original: {}", e.getMessage());
            return new HashMap<>(source);
        }
    }
}
