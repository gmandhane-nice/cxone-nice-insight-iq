package com.nice.agentic.query;

import com.nice.agentic.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Primary {@link QueryExecutor} implementation.
 *
 * <p>Routes to the appropriate backing store via {@link SnowflakeExecutor} or
 * {@link OpenSearchExecutor}. When either executor is not configured (no env vars / tunnel
 * not running) or when a live query fails, the method falls back to static demo data that
 * tells the Banking AHT story — so the demo works on any dev laptop without credentials.</p>
 */
@Component
@Primary
public class RealQueryExecutor implements QueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RealQueryExecutor.class);

    private final SnowflakeExecutor   snowflakeExecutor;
    private final OpenSearchExecutor  openSearchExecutor;
    private final TenantContext       tenantContext;

    public RealQueryExecutor(SnowflakeExecutor snowflakeExecutor,
                             OpenSearchExecutor openSearchExecutor,
                             TenantContext tenantContext) {
        this.snowflakeExecutor  = snowflakeExecutor;
        this.openSearchExecutor = openSearchExecutor;
        this.tenantContext      = tenantContext;
    }

    @Override
    public TabularResult execute(QueryDescriptor descriptor) {
        return switch (descriptor.store()) {
            case "snowflake"  -> executeSnowflake(descriptor);
            case "opensearch" -> executeOpenSearch(descriptor);
            default -> {
                log.warn("Unknown store '{}' — returning empty result", descriptor.store());
                yield new TabularResult(List.of("note"),
                        List.of(Map.of("note", "Unsupported store: " + descriptor.store())));
            }
        };
    }

    // -------------------------------------------------------------------------
    // Snowflake
    // NOTE: uses real Snowflake JDBC when SNOWFLAKE_* env vars are set
    // -------------------------------------------------------------------------

    private TabularResult executeSnowflake(QueryDescriptor descriptor) {
        if (snowflakeExecutor.isConfigured()) {
            try {
                String sql = buildSnowflakeSql(descriptor);
                log.info("Snowflake executing: {}", sql);
                List<Map<String, Object>> rows = snowflakeExecutor.execute(sql);
                List<String> columns = rows.isEmpty()
                        ? List.of()
                        : new ArrayList<>(rows.get(0).keySet());
                return new TabularResult(columns, rows);
            } catch (Exception e) {
                log.warn("Snowflake query failed ({}), falling back to demo data", e.getMessage());
            }
        }
        // Stub fallback — Banking AHT demo scenario
        return snowflakeStubFallback(descriptor);
    }

    /**
     * Build a SQL statement from the descriptor with support for column selection,
     * aggregations, GROUP BY, and automatic JOIN to agent_dim for name resolution.
     */
    private String buildSnowflakeSql(QueryDescriptor descriptor) {
        String table = resolveSnowflakeView(descriptor.indexOrTable());
        String tenantId = tenantContext.getTenantId().replace("'", "''");

        // Auto-join user_dim for fact/session tables to get user names + state_dim for session
        boolean needsAgentJoin = (table.contains("SESSION_ACTIVITY") || table.contains("CONTACT_FACT"))
                && !table.contains("DIM");
        boolean isSessionTable = table.contains("SESSION_ACTIVITY");
        String mainAlias = needsAgentJoin ? "f" : null;
        String userAlias = "ud";
        String stateAlias = "asd";

        // Build SELECT clause
        String selectClause;
        List<String> selectCols = descriptor.select();
        List<String> groupByCols = descriptor.groupBy();
        List<String> aggs = descriptor.aggs();
        boolean hasGroupBy = groupByCols != null && !groupByCols.isEmpty();
        boolean hasAggs = aggs != null && !aggs.isEmpty();

        if (hasGroupBy && hasAggs) {
            List<String> selectParts = new ArrayList<>();
            if (needsAgentJoin) {
                selectParts.add(userAlias + ".USER_FIRST_NAME");
                selectParts.add(userAlias + ".USER_LAST_NAME");
            }
            if (isSessionTable) {
                selectParts.add(stateAlias + ".AGENT_STATE_NAME");
            }
            for (String col : groupByCols) {
                selectParts.add(needsAgentJoin ? mainAlias + "." + col : col);
            }
            selectParts.addAll(aggs);
            selectClause = String.join(", ", selectParts);
        } else if (selectCols != null && !selectCols.isEmpty()) {
            List<String> formattedCols = new ArrayList<>();
            if (needsAgentJoin) {
                formattedCols.add(userAlias + ".USER_FIRST_NAME");
                formattedCols.add(userAlias + ".USER_LAST_NAME");
            }
            if (isSessionTable) {
                formattedCols.add(stateAlias + ".AGENT_STATE_NAME");
            }
            for (String col : selectCols) {
                String formatted = formatColumnSelect(col);
                formattedCols.add(needsAgentJoin ? formatted.replace(col, mainAlias + "." + col)
                        .replace(" as " + mainAlias + ".", " as ") : formatted);
            }
            selectClause = String.join(", ", formattedCols);
        } else {
            if (needsAgentJoin) {
                selectClause = mainAlias + ".*, " + userAlias + ".USER_FIRST_NAME, " + userAlias + ".USER_LAST_NAME";
                if (isSessionTable) {
                    selectClause += ", " + stateAlias + ".AGENT_STATE_NAME";
                }
            } else {
                selectClause = "*";
            }
        }

        StringBuilder sql = new StringBuilder("SELECT ").append(selectClause).append(" FROM ").append(table);

        if (needsAgentJoin) {
            sql.append(" ").append(mainAlias);
            sql.append(" LEFT JOIN ").append(SnowflakeExecutor.VIEW_USER_DIM).append(" ").append(userAlias);
            sql.append(" ON ").append(mainAlias).append(".USER_ID = ").append(userAlias).append(".USER_ID");
            sql.append(" AND ").append(userAlias).append("._TENANT_ID = '").append(tenantId).append("'");
            if (isSessionTable) {
                sql.append(" LEFT JOIN ").append(SnowflakeExecutor.VIEW_AGENT_STATE_DIM).append(" ").append(stateAlias);
                sql.append(" ON ").append(mainAlias).append(".AGENT_STATE_KEY = ").append(stateAlias).append(".AGENT_STATE_KEY");
            }
        }

        // WHERE clause
        sql.append(" WHERE ").append(needsAgentJoin ? mainAlias + "." : "").append("_tenant_id = '")
           .append(tenantId).append("'");

        // Time range filter
        if (table.contains("FACT") || table.contains("CONTACT") || table.contains("SESSION_ACTIVITY")) {
            String timeClause = buildTimeRangeClause(descriptor.timeRange(), table,
                    needsAgentJoin ? mainAlias : null);
            if (timeClause != null) {
                sql.append(" AND ").append(timeClause);
            }
        }

        Map<String, String> filters = parseFilters(descriptor.filters());
        if (!filters.isEmpty()) {
            sql.append(" AND ");
            String prefix = needsAgentJoin ? mainAlias + "." : "";
            sql.append(filters.entrySet().stream()
                    .map(e -> prefix + e.getKey() + " = '" + e.getValue().replace("'", "''") + "'")
                    .collect(Collectors.joining(" AND ")));
        }

        // GROUP BY
        if (hasGroupBy) {
            List<String> gbParts = new ArrayList<>();
            if (needsAgentJoin) {
                gbParts.add(userAlias + ".USER_FIRST_NAME");
                gbParts.add(userAlias + ".USER_LAST_NAME");
            }
            if (isSessionTable) {
                gbParts.add(stateAlias + ".AGENT_STATE_NAME");
            }
            for (String col : groupByCols) {
                gbParts.add(needsAgentJoin ? mainAlias + "." + col : col);
            }
            sql.append(" GROUP BY ").append(String.join(", ", gbParts));
        }

        // ORDER BY
        if (hasGroupBy && hasAggs) {
            String firstAgg = aggs.get(0);
            String alias = firstAgg.contains(" as ") ? firstAgg.substring(firstAgg.lastIndexOf(" as ") + 4).trim()
                         : firstAgg.contains(" AS ") ? firstAgg.substring(firstAgg.lastIndexOf(" AS ") + 4).trim()
                         : null;
            if (alias != null) {
                sql.append(" ORDER BY ").append(alias).append(" DESC");
            }
        }

        int limit = descriptor.limit() > 0 ? descriptor.limit() : 100;
        sql.append(" LIMIT ").append(limit);
        return sql.toString();
    }

    private String formatColumnSelect(String col) {
        String upper = col.toUpperCase();
        if (upper.contains("TIMESTAMP")) {
            return "TO_VARCHAR(" + col + ", 'YYYY-MM-DD HH24:MI:SS') as " + col;
        }
        return col;
    }

    private String buildTimeRangeClause(String timeRange, String table, String alias) {
        if (timeRange == null || timeRange.isBlank()) return null;
        String tenantId = tenantContext.getTenantId().replace("'", "''");
        String colPrefix = (alias != null) ? alias + "." : "";
        java.util.regex.Matcher hMatcher = java.util.regex.Pattern
                .compile("last_(\\d+)_?h(?:ours?)?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(timeRange);
        if (hMatcher.find()) {
            int hours = Integer.parseInt(hMatcher.group(1));
            return colPrefix + "START_TIMESTAMP >= DATEADD(hour, -" + hours + ", (SELECT MAX(START_TIMESTAMP) FROM " +
                    table + " WHERE _tenant_id = '" + tenantId + "'))";
        }
        java.util.regex.Matcher dMatcher = java.util.regex.Pattern
                .compile("last_(\\d+)_?d(?:ays?)?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(timeRange);
        if (dMatcher.find()) {
            int days = Integer.parseInt(dMatcher.group(1));
            return colPrefix + "START_TIMESTAMP >= DATEADD(day, -" + days + ", (SELECT MAX(START_TIMESTAMP) FROM " +
                    table + " WHERE _tenant_id = '" + tenantId + "'))";
        }
        return null;
    }

    /**
     * Map logical/short table names to canonical SUITE_REFINED view names.
     */
    private String resolveSnowflakeView(String logicalName) {
        String lower = logicalName.toLowerCase();
        if (lower.contains("agent_session_activity") || lower.contains("agent_session_fact")) {
            return SnowflakeExecutor.VIEW_AGENT_SESSION_FACT;
        }
        if (lower.contains("agent_state_dim")) {
            return SnowflakeExecutor.VIEW_AGENT_STATE_DIM;
        }
        if (lower.contains("agent_contact_fact") || lower.equals("aht_data") || lower.equals("contacts")) {
            return SnowflakeExecutor.VIEW_AGENT_CONTACT_FACT;
        }
        if (lower.contains("skill") && lower.contains("dim")) {
            return SnowflakeExecutor.VIEW_SKILL_DIM;
        }
        if (lower.contains("agent") && lower.contains("dim")) {
            return SnowflakeExecutor.VIEW_AGENT_DIM;
        }
        if (lower.equals("agent_dim") || lower.equals("agents")) {
            return SnowflakeExecutor.VIEW_AGENT_DIM;
        }
        if (lower.contains("channel_dim")) {
            return SnowflakeExecutor.VIEW_CHANNEL_DIM;
        }
        if (lower.contains("contact_state_dim")) {
            return SnowflakeExecutor.VIEW_CONTACT_STATE_DIM;
        }
        return logicalName;
    }

    /** Stub data for when Snowflake is not reachable. */
    private TabularResult snowflakeStubFallback(QueryDescriptor descriptor) {
        log.warn("Snowflake executor is not configured or failed — returning demo data for table {}",
                descriptor.indexOrTable());

        if ("ops_events".equals(descriptor.indexOrTable())) {
            return new TabularResult(
                List.of("event_time", "event_type", "scope", "description"),
                List.of(
                    Map.of("event_time",  "2026-07-15T10:55:00Z",
                           "event_type",  "deploy",
                           "scope",       "Banking",
                           "description", "Routing-rule update v2.3.1"),
                    Map.of("event_time",  "2026-07-15T10:58:00Z",
                           "event_type",  "config_change",
                           "scope",       "Banking",
                           "description", "New-hire agent batch added to Banking skill")
                )
            );
        }
        return new TabularResult(
            List.of("note"),
            List.of(Map.of("note", "No demo data for table: " + descriptor.indexOrTable()))
        );
    }

    // -------------------------------------------------------------------------
    // OpenSearch
    // NOTE: uses real OpenSearch client when OPENSEARCH_ENDPOINT env var is set
    // Dev tunnel: localhost:6380 → cxcv-opensearch.dev.wfosaas.internal.com:443
    // -------------------------------------------------------------------------

    private TabularResult executeOpenSearch(QueryDescriptor descriptor) {
        Map<String, String> filters = new LinkedHashMap<>(parseFilters(descriptor.filters()));
        filters.put("tenantId", tenantContext.getTenantId());
        int limit = descriptor.limit() > 0 ? descriptor.limit() : 100;

        List<Map<String, Object>> rows =
                openSearchExecutor.search(descriptor.indexOrTable(), filters, limit);

        if (!rows.isEmpty()) {
            List<String> columns = new ArrayList<>(rows.get(0).keySet());
            return new TabularResult(columns, rows);
        }

        // Stub fallback — executor not configured or returned no hits
        log.warn("OpenSearch executor returned no data for index {} — returning demo stub",
                descriptor.indexOrTable());
        return new TabularResult(
            List.of("timestamp", "event", "scope"),
            List.of(
                Map.of("timestamp", "2026-07-15T11:00:00Z",
                       "event",     "stub_event",
                       "scope",     filters.toString())
            )
        );
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private static final Set<String> NON_COLUMN_KEYS = Set.of(
            "join", "join_table", "join_on", "source_view", "table",
            "select", "group_by", "order_by", "sort_by",
            "having", "distinct", "count", "sum", "avg", "max", "min",
            "session_count>", "session_count", "login_count",
            "start_timestamp", "end_timestamp", "time_range", "timerange",
            "limit", "offset", "where", "and", "or");

    private static final java.util.regex.Pattern TIME_RANGE_VALUE = java.util.regex.Pattern.compile(
            "last_\\d+_?(h|d|hours?|days?|weeks?|months?)", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern VIEW_TABLE_VALUE = java.util.regex.Pattern.compile(
            ".*(VIEW|FACT|DIM|_SCD_|_V\\d{3}).*", java.util.regex.Pattern.CASE_INSENSITIVE);

    private boolean isValidColumnFilter(String key, String value) {
        if (NON_COLUMN_KEYS.contains(key.toLowerCase())) return false;
        if (TIME_RANGE_VALUE.matcher(value).matches()) return false;
        if (VIEW_TABLE_VALUE.matcher(value).matches()) return false;
        if (value.contains(",") && value.contains(" ")) return false;
        if (key.contains(">") || key.contains("<")) return false;
        return true;
    }

    private Map<String, String> parseFilters(List<String> filters) {
        Map<String, String> result = new LinkedHashMap<>();
        if (filters == null) return result;
        for (String f : filters) {
            int idx = f.indexOf('=');
            if (idx > 0) {
                String key = f.substring(0, idx).trim();
                String value = f.substring(idx + 1).trim();
                if (!isValidColumnFilter(key, value)) {
                    log.debug("Skipping non-column filter: {}={}", key, value);
                    continue;
                }
                result.put(key, value);
            }
        }
        return result;
    }
}
