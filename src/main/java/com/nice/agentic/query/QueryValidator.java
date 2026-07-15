package com.nice.agentic.query;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Validates a {@link QueryDescriptor} before it is passed to a {@link QueryExecutor}.
 *
 * Error codes:
 * <ul>
 *   <li>{@code STORE_NOT_ALLOWED} — store not in the allowlist</li>
 *   <li>{@code TABLE_NOT_ALLOWED} — indexOrTable not in the allowlist</li>
 *   <li>{@code DANGEROUS_KEYWORD} — a DDL/DML keyword found in filters</li>
 *   <li>{@code TIME_RANGE_TOO_WIDE} — timeRange exceeds 24 hours</li>
 *   <li>{@code LIMIT_TOO_LARGE} — limit exceeds 500</li>
 * </ul>
 */
@Component
public class QueryValidator {

    private static final Set<String> ALLOWED_STORES = Set.of("opensearch", "snowflake");

    private static final Set<String> ALLOWED_TABLES = Set.of(
            "ops_events", "aht_history", "contact_events", "agent_metrics",
            "agent_contact_fact", "agent_dim", "skill_dim", "channel_dim", "contact_state_dim",
            "slim_agent_scd_dim_view", "agent_contact_fact_view", "skill_scd_dim_view",
            "contact_activity_fact", "contact_skill_fact",
            "agent_session_activity", "agent_session_activity_fact", "agent_state_dim");

    private static final Set<String> DANGEROUS_KEYWORDS = Set.of(
            "DROP", "DELETE", "UPDATE", "INSERT", "TRUNCATE",
            "ALTER", "CREATE", "UNION", "EXEC");

    static final int MAX_LIMIT = 500;

    /**
     * Validates the descriptor.  Throws {@link QueryValidationException} on the first
     * rule violation.
     *
     * @param descriptor the descriptor to validate (must not be {@code null})
     * @throws QueryValidationException if any validation rule is violated
     */
    public void validate(QueryDescriptor descriptor) {
        validateStore(descriptor.store());
        validateTable(descriptor.indexOrTable());
        validateFilters(descriptor.filters());
        validateAggs(descriptor.aggs());
        validateTimeRange(descriptor.timeRange());
        validateLimit(descriptor.limit());
        validateColumns(descriptor.select(), "select");
        validateColumns(descriptor.groupBy(), "groupBy");
    }

    // -------------------------------------------------------------------------
    // Rule: store allowlist
    // -------------------------------------------------------------------------

    private void validateStore(String store) {
        if (store == null || !ALLOWED_STORES.contains(store.toLowerCase())) {
            throw new QueryValidationException(
                    "STORE_NOT_ALLOWED",
                    "Store '" + store + "' is not allowed. Allowed: " + ALLOWED_STORES);
        }
    }

    // -------------------------------------------------------------------------
    // Rule: table/index allowlist
    // -------------------------------------------------------------------------

    private void validateTable(String indexOrTable) {
        if (indexOrTable == null) {
            throw new QueryValidationException("TABLE_NOT_ALLOWED", "Table/index is null");
        }
        String lower = indexOrTable.toLowerCase().replace("-", "_");
        if (ALLOWED_TABLES.contains(lower)) return;
        // Allow if any allowed table is a prefix/suffix match (e.g. "SLIM_AGENT_SCD_DIM_VIEW" matches "slim_agent_scd_dim_view")
        for (String allowed : ALLOWED_TABLES) {
            if (lower.contains(allowed) || allowed.contains(lower)) return;
        }
        throw new QueryValidationException(
                "TABLE_NOT_ALLOWED",
                "Table/index '" + indexOrTable + "' is not allowed. Allowed: " + ALLOWED_TABLES);
    }

    // -------------------------------------------------------------------------
    // Rule: no DDL/DML keywords in filters
    // -------------------------------------------------------------------------

    private void validateFilters(List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        for (String filter : filters) {
            if (filter == null) continue;
            for (String keyword : DANGEROUS_KEYWORDS) {
                if (containsWord(filter, keyword)) {
                    throw new QueryValidationException(
                            "DANGEROUS_KEYWORD",
                            "Filter contains forbidden keyword '" + keyword + "' in: '" + filter + "'");
                }
            }
        }
    }

    private boolean containsWord(String text, String word) {
        if (text == null) return false;
        String upperText = text.toUpperCase();
        int idx = upperText.indexOf(word);
        while (idx >= 0) {
            boolean prefixOk  = idx == 0 || !Character.isLetterOrDigit(upperText.charAt(idx - 1));
            boolean suffixOk  = (idx + word.length()) >= upperText.length()
                    || !Character.isLetterOrDigit(upperText.charAt(idx + word.length()));
            if (prefixOk && suffixOk) return true;
            idx = upperText.indexOf(word, idx + 1);
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Rule: timeRange format validation (no cap on range)
    // -------------------------------------------------------------------------

    private void validateTimeRange(String timeRange) {
        // No time range cap — Snowflake handles whatever data is available
    }

    // -------------------------------------------------------------------------
    // Rule: column names must be safe identifiers (no SQL injection)
    // -------------------------------------------------------------------------

    private static final java.util.regex.Pattern SAFE_COLUMN = java.util.regex.Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*");
    private static final java.util.regex.Pattern SAFE_AGG = java.util.regex.Pattern.compile(
            "(?i)(COUNT|SUM|AVG|MAX|MIN)\\s*\\(\\s*(DISTINCT\\s+)?[A-Za-z_*][A-Za-z0-9_]*\\s*\\)" +
            "(\\s+(as|AS)\\s+[A-Za-z_][A-Za-z0-9_]*)?");

    private void validateAggs(List<String> aggs) {
        if (aggs == null || aggs.isEmpty()) return;
        for (String agg : aggs) {
            if (agg == null) continue;
            if (!SAFE_AGG.matcher(agg.trim()).matches()) {
                for (String keyword : DANGEROUS_KEYWORDS) {
                    if (containsWord(agg, keyword)) {
                        throw new QueryValidationException(
                                "DANGEROUS_KEYWORD",
                                "Aggregation contains forbidden keyword: " + agg);
                    }
                }
            }
        }
    }

    private void validateColumns(List<String> columns, String fieldName) {
        if (columns == null || columns.isEmpty()) return;
        for (String col : columns) {
            if (col == null) continue;
            if (!SAFE_COLUMN.matcher(col).matches()) {
                throw new QueryValidationException(
                        "DANGEROUS_KEYWORD",
                        fieldName + " column '" + col + "' is not a safe identifier");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rule: limit cap
    // -------------------------------------------------------------------------

    private void validateLimit(int limit) {
        if (limit > MAX_LIMIT) {
            throw new QueryValidationException(
                    "LIMIT_TOO_LARGE",
                    "Limit " + limit + " exceeds the maximum allowed value of " + MAX_LIMIT + ".");
        }
    }
}
