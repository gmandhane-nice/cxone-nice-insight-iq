package com.nice.agentic.forecast;

import com.nice.agentic.TenantContext;
import com.nice.agentic.query.SnowflakeExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Predictive Demand Forecasting endpoint.
 *
 * <p>Analyzes historical contact volume patterns (hour-of-day, day-of-week) from the
 * last 4 weeks and forecasts contact volume for the next 4 hours. Supervisors see
 * predicted surges BEFORE they happen so they can pre-position agents.</p>
 */
@RestController
@RequestMapping("/forecast")
public class DemandForecastController {

    private static final Logger log = LoggerFactory.getLogger(DemandForecastController.class);

    private static final int CONTACTS_PER_AGENT_PER_HOUR = 8;
    private static final int FORECAST_HOURS = 4;
    private static final int HISTORY_DAYS = 28;

    private final SnowflakeExecutor snowflakeExecutor;
    private final TenantContext tenantContext;

    public DemandForecastController(SnowflakeExecutor snowflakeExecutor,
                                    TenantContext tenantContext) {
        this.snowflakeExecutor = snowflakeExecutor;
        this.tenantContext = tenantContext;
    }

    // -------------------------------------------------------------------------
    // GET /forecast/demand
    // -------------------------------------------------------------------------

    @GetMapping("/demand")
    public Map<String, Object> demand() {
        if (!snowflakeExecutor.isConfigured()) {
            log.info("Snowflake not configured — returning mock forecast data");
            return buildMockResponse();
        }

        try {
            return buildLiveForecast();
        } catch (Exception e) {
            log.error("Failed to build live forecast — returning mock data: {}", e.getMessage(), e);
            return buildMockResponse();
        }
    }

    // -------------------------------------------------------------------------
    // Live forecast from Snowflake
    // -------------------------------------------------------------------------

    private Map<String, Object> buildLiveForecast() {
        String tenantId = tenantContext.getTenantId();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int currentHour = now.getHour();
        int currentDow = now.getDayOfWeek().getValue(); // 1=Monday .. 7=Sunday

        // Step 1: Query historical hourly volumes grouped by hour_of_day and day_of_week
        List<Map<String, Object>> historicalRows = queryHistoricalVolumes(tenantId);

        // Build lookup: key = "dow_hour" -> {avg, max, count}
        Map<String, double[]> historicalLookup = new LinkedHashMap<>();
        for (Map<String, Object> row : historicalRows) {
            int dow = toInt(row.get("DOW"));
            int hour = toInt(row.get("HOUR_OF_DAY"));
            double avg = toDouble(row.get("AVG_VOLUME"), 0.0);
            double max = toDouble(row.get("MAX_VOLUME"), 0.0);
            historicalLookup.put(dow + "_" + hour, new double[]{avg, max});
        }

        // Step 2: Query current hour actual volume
        int actualVolume = queryCurrentHourVolume(tenantId);

        // Step 3: Query active agents in the last hour
        int activeAgents = queryActiveAgents(tenantId);

        // Calculate predicted volume for current hour
        String currentKey = currentDow + "_" + currentHour;
        double[] currentHist = historicalLookup.getOrDefault(currentKey, new double[]{actualVolume, actualVolume});
        int predictedCurrent = (int) Math.round(currentHist[0]);

        // Build current hour block
        Map<String, Object> currentHourBlock = new LinkedHashMap<>();
        currentHourBlock.put("hour", currentHour);
        currentHourBlock.put("actualVolume", actualVolume);
        currentHourBlock.put("predictedVolume", predictedCurrent);
        currentHourBlock.put("activeAgents", activeAgents);

        // Step 4: Build forecast for next 4 hours
        List<Map<String, Object>> forecast = new ArrayList<>();
        double overallAvg = computeOverallAverage(historicalLookup);
        int peakHour = currentHour;
        int peakVolume = predictedCurrent;

        for (int i = 1; i <= FORECAST_HOURS; i++) {
            ZonedDateTime futureTime = now.plusHours(i);
            int fHour = futureTime.getHour();
            int fDow = futureTime.getDayOfWeek().getValue();
            String key = fDow + "_" + fHour;
            double[] hist = historicalLookup.getOrDefault(key, new double[]{0, 0});
            int predicted = (int) Math.round(hist[0]);
            int histMax = (int) Math.round(hist[1]);
            int histAvg = predicted;

            // Staffing sufficiency
            int agentsRecommended = (int) Math.ceil((double) predicted / CONTACTS_PER_AGENT_PER_HOUR);
            String sufficiency = calculateStaffingSufficiency(activeAgents, predicted);
            boolean surgeAlert = predicted > overallAvg * 1.15;

            if (predicted > peakVolume) {
                peakVolume = predicted;
                peakHour = fHour;
            }

            String dayName = DayOfWeek.of(fDow).getDisplayName(TextStyle.FULL, Locale.ENGLISH).toUpperCase();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("hour", fHour);
            entry.put("dayOfWeek", dayName);
            entry.put("predictedVolume", predicted);
            entry.put("historicalAvg", histAvg);
            entry.put("historicalMax", histMax);
            entry.put("staffingSufficiency", sufficiency);
            entry.put("agentsRecommended", agentsRecommended);
            entry.put("surgeAlert", surgeAlert);
            forecast.add(entry);
        }

        // Build insights
        List<String> insights = buildInsights(forecast, currentHour, predictedCurrent, peakHour, peakVolume, now, overallAvg);

        // Assemble response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        result.put("currentHour", currentHourBlock);
        result.put("forecast", forecast);
        result.put("insights", insights);
        return result;
    }

    // -------------------------------------------------------------------------
    // Snowflake queries
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> queryHistoricalVolumes(String tenantId) {
        String sql = "SELECT DOW, HOUR_OF_DAY,\n"
                + "       AVG(DAILY_HOURLY_COUNT) AS AVG_VOLUME,\n"
                + "       MAX(DAILY_HOURLY_COUNT) AS MAX_VOLUME\n"
                + "FROM (\n"
                + "  SELECT DATE_TRUNC('day', a.START_TIMESTAMP) AS DT,\n"
                + "         DAYOFWEEK(a.START_TIMESTAMP) AS DOW,\n"
                + "         HOUR(a.START_TIMESTAMP) AS HOUR_OF_DAY,\n"
                + "         COUNT(*) AS DAILY_HOURLY_COUNT\n"
                + "  FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "  WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "    AND a.START_TIMESTAMP >= DATEADD(day, -" + HISTORY_DAYS + ", CURRENT_TIMESTAMP())\n"
                + "  GROUP BY DATE_TRUNC('day', a.START_TIMESTAMP), DAYOFWEEK(a.START_TIMESTAMP), HOUR(a.START_TIMESTAMP)\n"
                + ") sub\n"
                + "GROUP BY DOW, HOUR_OF_DAY\n"
                + "ORDER BY DOW, HOUR_OF_DAY";

        return snowflakeExecutor.execute(sql);
    }

    private int queryCurrentHourVolume(String tenantId) {
        String sql = "SELECT COUNT(*) AS VOLUME\n"
                + "FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "  AND a.START_TIMESTAMP >= DATEADD(minute, -60, CURRENT_TIMESTAMP())";

        List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
        if (!rows.isEmpty()) {
            return toInt(rows.get(0).get("VOLUME"));
        }
        return 0;
    }

    private int queryActiveAgents(String tenantId) {
        String sql = "SELECT COUNT(DISTINCT USER_ID) AS AGENT_COUNT\n"
                + "FROM " + SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT + " a\n"
                + "WHERE a._TENANT_ID = '" + tenantId + "'\n"
                + "  AND a.START_TIMESTAMP >= DATEADD(hour, -1, CURRENT_TIMESTAMP())";

        List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
        if (!rows.isEmpty()) {
            return toInt(rows.get(0).get("AGENT_COUNT"));
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Staffing & insights logic
    // -------------------------------------------------------------------------

    private String calculateStaffingSufficiency(int activeAgents, int predictedVolume) {
        double requiredAgents = (double) predictedVolume / CONTACTS_PER_AGENT_PER_HOUR;
        if (activeAgents > requiredAgents) {
            return "overstaffed";
        } else if (activeAgents >= requiredAgents * 0.9) {
            return "adequate";
        } else if (activeAgents >= requiredAgents * 0.75) {
            return "understaffed";
        } else {
            return "critical";
        }
    }

    private double computeOverallAverage(Map<String, double[]> historicalLookup) {
        if (historicalLookup.isEmpty()) return 0;
        double sum = 0;
        for (double[] vals : historicalLookup.values()) {
            sum += vals[0];
        }
        return sum / historicalLookup.size();
    }

    private List<String> buildInsights(List<Map<String, Object>> forecast,
                                       int currentHour, int currentVolume,
                                       int peakHour, int peakVolume,
                                       ZonedDateTime now, double overallAvg) {
        List<String> insights = new ArrayList<>();

        if (peakVolume > currentVolume && peakHour != currentHour) {
            int pctIncrease = currentVolume > 0
                    ? (int) Math.round(((double) (peakVolume - currentVolume) / currentVolume) * 100)
                    : 0;
            insights.add(String.format("Contact volume expected to peak at %02d:00 (+%d%% vs current)", peakHour, pctIncrease));
        }

        String dayName = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        for (Map<String, Object> f : forecast) {
            int predicted = toInt(f.get("predictedVolume"));
            if (overallAvg > 0 && predicted > overallAvg * 1.10) {
                int pctAbove = (int) Math.round(((predicted - overallAvg) / overallAvg) * 100);
                insights.add(String.format("Historically, %ss %02d:00-%02d:00 see %d%% more contacts than weekly average",
                        dayName, toInt(f.get("hour")), toInt(f.get("hour")) + 1, pctAbove));
                break;
            }
        }

        long criticalCount = forecast.stream()
                .filter(f -> "critical".equals(f.get("staffingSufficiency")))
                .count();
        if (criticalCount > 0) {
            insights.add(String.format("%d upcoming hour(s) forecast critical staffing shortage — consider calling in additional agents", criticalCount));
        }

        return insights;
    }

    // -------------------------------------------------------------------------
    // Mock response (when Snowflake is not configured)
    // -------------------------------------------------------------------------

    private Map<String, Object> buildMockResponse() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int currentHour = now.getHour();

        Map<String, Object> currentHourBlock = new LinkedHashMap<>();
        currentHourBlock.put("hour", currentHour);
        currentHourBlock.put("actualVolume", 342);
        currentHourBlock.put("predictedVolume", 380);
        currentHourBlock.put("activeAgents", 45);

        List<Map<String, Object>> forecast = new ArrayList<>();
        int[] mockPredicted = {420, 510, 460, 380};
        int[] mockHistAvg = {415, 440, 430, 370};
        int[] mockHistMax = {510, 580, 520, 450};
        String[] sufficiency = {"understaffed", "critical", "understaffed", "adequate"};
        boolean[] surges = {true, true, true, false};

        for (int i = 0; i < FORECAST_HOURS; i++) {
            ZonedDateTime futureTime = now.plusHours(i + 1);
            int fHour = futureTime.getHour();
            String dayName = futureTime.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toUpperCase();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("hour", fHour);
            entry.put("dayOfWeek", dayName);
            entry.put("predictedVolume", mockPredicted[i]);
            entry.put("historicalAvg", mockHistAvg[i]);
            entry.put("historicalMax", mockHistMax[i]);
            entry.put("staffingSufficiency", sufficiency[i]);
            entry.put("agentsRecommended", (int) Math.ceil((double) mockPredicted[i] / CONTACTS_PER_AGENT_PER_HOUR));
            entry.put("surgeAlert", surges[i]);
            forecast.add(entry);
        }

        List<String> insights = new ArrayList<>();
        ZonedDateTime surgeTime = now.plusHours(2);
        insights.add(String.format("Contact volume expected to peak at %02d:00 (+49%% vs current)", surgeTime.getHour()));
        String dayName = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        insights.add(String.format("Historically, %ss %02d:00-%02d:00 see 12%% more contacts than weekly average",
                dayName, surgeTime.getHour(), surgeTime.getHour() + 1));
        insights.add("2 upcoming hour(s) forecast critical staffing shortage — consider calling in additional agents");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        result.put("currentHour", currentHourBlock);
        result.put("forecast", forecast);
        result.put("insights", insights);
        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double toDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
