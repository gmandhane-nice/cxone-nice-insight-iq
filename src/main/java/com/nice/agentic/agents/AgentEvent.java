package com.nice.agentic.agents;

/**
 * Event model for SSE streaming of multi-agent investigation progress.
 * <p>
 * Phases: "planning", "investigating", "reasoning", "recommending", "complete"<br>
 * Status: "started", "tool_call", "completed"
 */
public record AgentEvent(
        String phase,        // "planning", "investigating", "reasoning", "recommending", "complete"
        String agentName,    // which agent is active (e.g., "orchestrator", "realtime", "historical")
        String status,       // "started", "tool_call", "completed"
        String detail,       // human-readable description
        Object data          // payload (tool result, hypothesis, recommendation, etc.)
) {}
