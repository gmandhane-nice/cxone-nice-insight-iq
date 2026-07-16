package com.nice.agentic.simulator;

import com.nice.agentic.TenantContext;
import com.nice.agentic.config.ModuleMetrics;
import com.nice.agentic.query.SnowflakeExecutor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/simulator")
@Tag(name = "Simulator")
public class StaffingSimulatorController {

    private static final Logger log = LoggerFactory.getLogger(StaffingSimulatorController.class);

    private final SnowflakeExecutor snowflakeExecutor;
    private final TenantContext tenantContext;
    private final ModuleMetrics moduleMetrics;

    public StaffingSimulatorController(SnowflakeExecutor snowflakeExecutor, TenantContext tenantContext,
                                       ModuleMetrics moduleMetrics) {
        this.snowflakeExecutor = snowflakeExecutor;
        this.tenantContext = tenantContext;
        this.moduleMetrics = moduleMetrics;
    }

    // -------------------------------------------------------------------------
    // GET /simulator/current-state
    // -------------------------------------------------------------------------

    @GetMapping("/current-state")
    @Operation(
            summary = "Get current staffing state",
            description = "Returns the current staffing state across all skills including contacts per hour, "
                    + "average handle time, active agent count, current SLA, and traffic intensity using Erlang-C modeling.")
    public Map<String, Object> currentState() {
        long start = System.currentTimeMillis();
        try {
            log.info("Fetching current staffing state for tenant {}", tenantContext.getTenantId());

            if (!snowflakeExecutor.isConfigured()) {
                log.warn("Snowflake not configured — returning mock staffing state");
                return buildMockCurrentState();
            }

            try {
                String tenantId = tenantContext.getTenantId();

                // Query last 7 days: contacts per day, avg AHT, distinct agents per skill
                String sql = "SELECT s.SKILL_NAME, a.SKILL_NO, " +
                        "COUNT(*) / 7.0 / 24.0 AS CONTACTS_PER_HOUR, " +
                        "AVG(a.HANDLE_SECONDS) AS AVG_AHT, " +
                        "COUNT(DISTINCT a.USER_ID) AS CURRENT_AGENTS " +
                        "FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a " +
                        "JOIN (SELECT SKILL_NO, MAX(SKILL_NAME) AS SKILL_NAME " +
                        "      FROM " + SnowflakeExecutor.VIEW_SKILL_DIM +
                        "      WHERE _TENANT_ID = '" + tenantId + "' " +
                        "      GROUP BY SKILL_NO) s ON a.SKILL_NO = s.SKILL_NO " +
                        "WHERE a._TENANT_ID = '" + tenantId + "' " +
                        "AND a.START_TIMESTAMP >= DATEADD(day, -7, CURRENT_TIMESTAMP()) " +
                        "GROUP BY s.SKILL_NAME, a.SKILL_NO " +
                        "ORDER BY CONTACTS_PER_HOUR DESC";

                List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);

                List<Map<String, Object>> skills = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    double contactsPerHour = toDouble(row.get("CONTACTS_PER_HOUR"));
                    double avgAht = toDouble(row.get("AVG_AHT"));
                    int currentAgents = toInt(row.get("CURRENT_AGENTS"));
                    double trafficIntensity = contactsPerHour * (avgAht / 3600.0);
                    double currentSla = predictSla(currentAgents, contactsPerHour, avgAht, 30.0);

                    Map<String, Object> skill = new LinkedHashMap<>();
                    skill.put("skillName", row.get("SKILL_NAME"));
                    skill.put("skillNo", toInt(row.get("SKILL_NO")));
                    skill.put("contactsPerHour", Math.round(contactsPerHour * 100.0) / 100.0);
                    skill.put("avgAht", Math.round(avgAht * 10.0) / 10.0);
                    skill.put("currentAgents", currentAgents);
                    skill.put("currentSla", Math.round(currentSla * 100.0) / 100.0);
                    skill.put("trafficIntensity", Math.round(trafficIntensity * 100.0) / 100.0);
                    skills.add(skill);
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("generatedAt", Instant.now().toString());
                response.put("skills", skills);
                log.info("Returned staffing state for {} skills", skills.size());
                return response;

            } catch (Exception e) {
                log.error("Failed to fetch current staffing state: {}", e.getMessage(), e);
                return buildMockCurrentState();
            }
        } finally {
            moduleMetrics.record("simulator", System.currentTimeMillis() - start);
        }
    }

    // -------------------------------------------------------------------------
    // POST /simulator/simulate
    // -------------------------------------------------------------------------

    @PostMapping("/simulate")
    @Operation(
            summary = "Run staffing simulation",
            description = "Simulates the SLA impact of moving agents between skills using Erlang-C queuing theory. "
                    + "Accepts agent delta changes per skill and returns before/after SLA predictions, "
                    + "wait probabilities, and a safety recommendation.")
    public Map<String, Object> simulate(@RequestBody SimulateRequest request) {
        log.info("Running staffing simulation with {} changes, targetAnswerTime={}s",
                request.changes != null ? request.changes.size() : 0, request.targetAnswerTime);

        int targetAnswerTime = request.targetAnswerTime > 0 ? request.targetAnswerTime : 30;

        if (!snowflakeExecutor.isConfigured()) {
            log.warn("Snowflake not configured — returning mock simulation");
            return buildMockSimulation(request, targetAnswerTime);
        }

        try {
            // Fetch current state to run simulation against
            Map<String, Object> currentStateResponse = currentState();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> skills = (List<Map<String, Object>>) currentStateResponse.get("skills");

            if (skills == null || skills.isEmpty()) {
                return buildMockSimulation(request, targetAnswerTime);
            }

            // Build a lookup by skillNo
            Map<Integer, Map<String, Object>> skillMap = new LinkedHashMap<>();
            for (Map<String, Object> skill : skills) {
                skillMap.put(toInt(skill.get("skillNo")), skill);
            }

            List<Map<String, Object>> results = new ArrayList<>();
            List<String> impactSummaries = new ArrayList<>();

            for (Change change : request.changes) {
                Map<String, Object> skillData = skillMap.get(change.skillNo);
                if (skillData == null) {
                    log.warn("Skill {} not found in current state — skipping", change.skillNo);
                    continue;
                }

                String skillName = (String) skillData.get("skillName");
                int currentAgents = toInt(skillData.get("currentAgents"));
                double contactsPerHour = toDouble(skillData.get("contactsPerHour"));
                double avgAht = toDouble(skillData.get("avgAht"));
                double trafficIntensity = toDouble(skillData.get("trafficIntensity"));

                int newAgents = Math.max(1, currentAgents + change.agentDelta);

                double beforeSla = predictSla(currentAgents, contactsPerHour, avgAht, targetAnswerTime);
                double afterSla = predictSla(newAgents, contactsPerHour, avgAht, targetAnswerTime);
                double beforeWaitProb = erlangC(currentAgents, trafficIntensity);
                double afterWaitProb = erlangC(newAgents, trafficIntensity);
                double slaDelta = afterSla - beforeSla;

                String verdict = computeVerdict(slaDelta);

                Map<String, Object> before = new LinkedHashMap<>();
                before.put("agents", currentAgents);
                before.put("sla", Math.round(beforeSla * 100.0) / 100.0);
                before.put("waitProbability", Math.round(beforeWaitProb * 100.0) / 100.0);

                Map<String, Object> after = new LinkedHashMap<>();
                after.put("agents", newAgents);
                after.put("sla", Math.round(afterSla * 100.0) / 100.0);
                after.put("waitProbability", Math.round(afterWaitProb * 100.0) / 100.0);

                Map<String, Object> impact = new LinkedHashMap<>();
                impact.put("slaDelta", Math.round(slaDelta * 100.0) / 100.0);
                impact.put("verdict", verdict);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("skillName", skillName);
                result.put("skillNo", change.skillNo);
                result.put("before", before);
                result.put("after", after);
                result.put("impact", impact);
                results.add(result);

                int slaDeltaPct = (int) Math.round(slaDelta * 100);
                String sign = slaDeltaPct >= 0 ? "+" : "";
                impactSummaries.add(String.format("%s SLA %s%d%%", skillName, sign, slaDeltaPct));
            }

            String netImpact = buildNetImpact(impactSummaries, request.changes);
            String recommendation = buildRecommendation(results, request.changes);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("generatedAt", Instant.now().toString());
            response.put("targetAnswerTime", targetAnswerTime);
            response.put("results", results);
            response.put("netImpact", netImpact);
            response.put("recommendation", recommendation);

            log.info("Simulation complete: {} skill impacts computed", results.size());
            return response;

        } catch (Exception e) {
            log.error("Simulation failed: {}", e.getMessage(), e);
            return buildMockSimulation(request, targetAnswerTime);
        }
    }

    // -------------------------------------------------------------------------
    // Erlang-C and SLA prediction
    // -------------------------------------------------------------------------

    private double erlangC(int agents, double trafficIntensity) {
        if (agents <= trafficIntensity) return 1.0; // overloaded
        double rho = trafficIntensity / agents;
        double sum = 0;
        for (int k = 0; k < agents; k++) {
            sum += Math.pow(trafficIntensity, k) / factorial(k);
        }
        double erlangB = Math.pow(trafficIntensity, agents) / factorial(agents);
        double ec = erlangB / (erlangB + (1 - rho) * sum);
        return Math.min(1.0, Math.max(0.0, ec));
    }

    private double factorial(int n) {
        double result = 1;
        for (int i = 2; i <= Math.min(n, 170); i++) result *= i;
        return result;
    }

    private double predictSla(int agents, double callsPerHour, double avgHandleTimeSec, double targetAnswerTimeSec) {
        double trafficIntensity = callsPerHour * (avgHandleTimeSec / 3600.0);
        double ec = erlangC(agents, trafficIntensity);
        double sla = 1 - ec * Math.exp(-(agents - trafficIntensity) * (targetAnswerTimeSec / avgHandleTimeSec));
        return Math.min(1.0, Math.max(0.0, sla));
    }

    // -------------------------------------------------------------------------
    // Verdict logic
    // -------------------------------------------------------------------------

    private String computeVerdict(double slaDelta) {
        if (slaDelta > 0.1) return "Significant SLA improvement";
        if (slaDelta > 0.05) return "Moderate SLA improvement";
        if (slaDelta > -0.05) return "Minimal impact";
        if (slaDelta > -0.1) return "Moderate SLA degradation — use caution";
        return "SLA drops below threshold — NOT recommended";
    }

    // -------------------------------------------------------------------------
    // Net impact and recommendation helpers
    // -------------------------------------------------------------------------

    private String buildNetImpact(List<String> impactSummaries, List<Change> changes) {
        if (impactSummaries.isEmpty()) return "No impact computed.";

        int totalDelta = 0;
        for (Change c : changes) totalDelta += Math.abs(c.agentDelta);

        StringBuilder sb = new StringBuilder();
        sb.append("Moving ").append(totalDelta / 2).append(" agent(s): ");
        sb.append(String.join(", ", impactSummaries)).append(".");
        return sb.toString();
    }

    private String buildRecommendation(List<Map<String, Object>> results, List<Change> changes) {
        boolean allAboveThreshold = true;
        boolean anyDegradation = false;

        for (Map<String, Object> result : results) {
            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) result.get("after");
            @SuppressWarnings("unchecked")
            Map<String, Object> impact = (Map<String, Object>) result.get("impact");
            double afterSla = toDouble(after.get("sla"));
            double slaDelta = toDouble(impact.get("slaDelta"));

            if (afterSla < 0.8) allAboveThreshold = false;
            if (slaDelta < -0.05) anyDegradation = true;
        }

        if (allAboveThreshold && !anyDegradation) {
            return "All skills remain above 80% SLA — change is safe to apply.";
        } else if (!allAboveThreshold && anyDegradation) {
            int suggestedDelta = Math.abs(changes.get(0).agentDelta) - 1;
            if (suggestedDelta > 0) {
                return "Move " + suggestedDelta + " agent(s) instead of " +
                        Math.abs(changes.get(0).agentDelta) + " to keep both skills above 80% SLA.";
            }
            return "Change would drop skills below 80% SLA — NOT recommended.";
        } else if (anyDegradation) {
            return "Some skills experience degradation — monitor closely if applied.";
        }
        return "Change appears safe but verify with real-time metrics before applying.";
    }

    // -------------------------------------------------------------------------
    // Mock data fallbacks
    // -------------------------------------------------------------------------

    private Map<String, Object> buildMockCurrentState() {
        List<Map<String, Object>> skills = new ArrayList<>();

        Map<String, Object> billing = new LinkedHashMap<>();
        billing.put("skillName", "Billing");
        billing.put("skillNo", 1042);
        billing.put("contactsPerHour", 52.0);
        billing.put("avgAht", 285.0);
        billing.put("currentAgents", 12);
        billing.put("currentSla", 0.87);
        billing.put("trafficIntensity", 4.12);
        skills.add(billing);

        Map<String, Object> technical = new LinkedHashMap<>();
        technical.put("skillName", "Technical");
        technical.put("skillNo", 1078);
        technical.put("contactsPerHour", 38.0);
        technical.put("avgAht", 420.0);
        technical.put("currentAgents", 5);
        technical.put("currentSla", 0.62);
        technical.put("trafficIntensity", 4.43);
        skills.add(technical);

        Map<String, Object> sales = new LinkedHashMap<>();
        sales.put("skillName", "Sales");
        sales.put("skillNo", 1105);
        sales.put("contactsPerHour", 27.0);
        sales.put("avgAht", 195.0);
        sales.put("currentAgents", 8);
        sales.put("currentSla", 0.94);
        sales.put("trafficIntensity", 1.46);
        skills.add(sales);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", Instant.now().toString());
        response.put("skills", skills);
        return response;
    }

    private Map<String, Object> buildMockSimulation(SimulateRequest request, int targetAnswerTime) {
        List<Map<String, Object>> results = new ArrayList<>();

        // Use mock current state as baseline
        Map<String, Object> mockState = buildMockCurrentState();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mockSkills = (List<Map<String, Object>>) mockState.get("skills");

        Map<Integer, Map<String, Object>> skillMap = new LinkedHashMap<>();
        for (Map<String, Object> skill : mockSkills) {
            skillMap.put(toInt(skill.get("skillNo")), skill);
        }

        List<String> impactSummaries = new ArrayList<>();

        if (request.changes != null) {
            for (Change change : request.changes) {
                Map<String, Object> skillData = skillMap.get(change.skillNo);
                if (skillData == null) continue;

                String skillName = (String) skillData.get("skillName");
                int currentAgents = toInt(skillData.get("currentAgents"));
                double contactsPerHour = toDouble(skillData.get("contactsPerHour"));
                double avgAht = toDouble(skillData.get("avgAht"));
                double trafficIntensity = toDouble(skillData.get("trafficIntensity"));

                int newAgents = Math.max(1, currentAgents + change.agentDelta);

                double beforeSla = predictSla(currentAgents, contactsPerHour, avgAht, targetAnswerTime);
                double afterSla = predictSla(newAgents, contactsPerHour, avgAht, targetAnswerTime);
                double beforeWaitProb = erlangC(currentAgents, trafficIntensity);
                double afterWaitProb = erlangC(newAgents, trafficIntensity);
                double slaDelta = afterSla - beforeSla;

                String verdict = computeVerdict(slaDelta);

                Map<String, Object> before = new LinkedHashMap<>();
                before.put("agents", currentAgents);
                before.put("sla", Math.round(beforeSla * 100.0) / 100.0);
                before.put("waitProbability", Math.round(beforeWaitProb * 100.0) / 100.0);

                Map<String, Object> after = new LinkedHashMap<>();
                after.put("agents", newAgents);
                after.put("sla", Math.round(afterSla * 100.0) / 100.0);
                after.put("waitProbability", Math.round(afterWaitProb * 100.0) / 100.0);

                Map<String, Object> impact = new LinkedHashMap<>();
                impact.put("slaDelta", Math.round(slaDelta * 100.0) / 100.0);
                impact.put("verdict", verdict);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("skillName", skillName);
                result.put("skillNo", change.skillNo);
                result.put("before", before);
                result.put("after", after);
                result.put("impact", impact);
                results.add(result);

                int slaDeltaPct = (int) Math.round(slaDelta * 100);
                String sign = slaDeltaPct >= 0 ? "+" : "";
                impactSummaries.add(String.format("%s SLA %s%d%%", skillName, sign, slaDeltaPct));
            }
        }

        String netImpact = buildNetImpact(impactSummaries, request.changes != null ? request.changes : List.of());
        String recommendation = buildRecommendation(results, request.changes != null ? request.changes : List.of());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", Instant.now().toString());
        response.put("targetAnswerTime", targetAnswerTime);
        response.put("results", results);
        response.put("netImpact", netImpact);
        response.put("recommendation", recommendation);
        return response;
    }

    // -------------------------------------------------------------------------
    // Type conversion helpers
    // -------------------------------------------------------------------------

    private int toInt(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).intValue();
        try {
            return Integer.parseInt(obj.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double toDouble(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // -------------------------------------------------------------------------
    // Request DTOs (static inner classes)
    // -------------------------------------------------------------------------

    public static class SimulateRequest {
        public List<Change> changes;
        public int targetAnswerTime;

        public List<Change> getChanges() { return changes; }
        public void setChanges(List<Change> changes) { this.changes = changes; }
        public int getTargetAnswerTime() { return targetAnswerTime; }
        public void setTargetAnswerTime(int targetAnswerTime) { this.targetAnswerTime = targetAnswerTime; }
    }

    public static class Change {
        public int skillNo;
        public int agentDelta;

        public int getSkillNo() { return skillNo; }
        public void setSkillNo(int skillNo) { this.skillNo = skillNo; }
        public int getAgentDelta() { return agentDelta; }
        public void setAgentDelta(int agentDelta) { this.agentDelta = agentDelta; }
    }
}
