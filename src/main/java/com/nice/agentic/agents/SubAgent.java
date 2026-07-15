package com.nice.agentic.agents;

import java.util.Map;

/**
 * Interface for specialized sub-agents in the multi-agent RCA system.
 * Each sub-agent focuses on a specific domain (real-time, historical, context).
 */
public interface SubAgent {

    /**
     * Returns the unique name of this sub-agent.
     */
    String name();

    /**
     * Investigates the given briefing using the sub-agent's specialized tools and knowledge.
     *
     * @param briefing a natural-language task description from the orchestrator
     * @param context  additional context (tenant ID, time range, etc.)
     * @return investigation results as a JSON string
     */
    String investigate(String briefing, Map<String, Object> context);
}
