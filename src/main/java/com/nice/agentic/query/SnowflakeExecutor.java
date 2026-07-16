package com.nice.agentic.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Snowflake JDBC driver.
 *
 * <p>If valid SNOWFLAKE_* environment variables are present the component opens a
 * real connection; otherwise it logs a warning and all callers fall back to the
 * stub data returned by {@link RealQueryExecutor}. The application starts and runs
 * the Banking AHT demo without real Snowflake credentials.</p>
 *
 * <h2>Key views used (SUITE_REFINED schema)</h2>
 * <ul>
 *   <li>{@code SUITE_REFINED.AGENT_CONTACT_FACT_VIEW_V011} — AHT / contact facts
 *       (template: {@code QUERYENGINE.PERF.AGENT_CONTACT_SUMMARY})</li>
 *   <li>{@code SUITE_REFINED.SKILL_DIM_VIEW_V001} — skill dimension</li>
 *   <li>{@code SUITE_REFINED.AGENT_DIM_VIEW_V001} — agent dimension (tenure, etc.)</li>
 * </ul>
 *
 * <h2>Real wiring checklist</h2>
 * <ol>
 *   <li>Set {@code SNOWFLAKE_ACCOUNT}, {@code SNOWFLAKE_USER}, {@code SNOWFLAKE_PASSWORD},
 *       {@code SNOWFLAKE_WAREHOUSE}, {@code SNOWFLAKE_DATABASE}, {@code SNOWFLAKE_ROLE}.</li>
 *   <li>For production, consider replacing {@link DriverManager#getConnection} with a
 *       HikariCP {@code DataSource} configured from these same properties.</li>
 *   <li>To use cxcv-query-engine-business-logic templates:
 *       {@code buildQueryFromAPIRequestAndTemplate(templateData, queryRequest, true, null, null)}
 *       instead of building SQL inline.</li>
 * </ol>
 */
@Component
public class SnowflakeExecutor {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeExecutor.class);

    public static final String VIEW_AGENT_CONTACT_FACT    = "DATAHUB.SUITE_REFINED.AGENT_CONTACT_FACT_VIEW_V011";
    public static final String VIEW_SKILL_DIM            = "DATAHUB.SUITE_REFINED.SKILL_SCD_DIM_VIEW_V001";
    public static final String VIEW_AGENT_DIM            = "DATAHUB.SUITE_REFINED.SLIM_AGENT_SCD_DIM_VIEW_V001";
    public static final String VIEW_CHANNEL_DIM          = "DATAHUB.SUITE_REFINED.CHANNEL_DIM_VIEW_V001";
    public static final String VIEW_CONTACT_STATE_DIM    = "DATAHUB.SUITE_REFINED.CONTACT_STATE_DIM_VIEW_V001";
    public static final String VIEW_AGENT_SESSION_FACT   = "DATAHUB.ENTITYMGT_REFINED.AGENT_SESSION_ACTIVITY_FACT";
    public static final String VIEW_AGENT_STATE_DIM      = "DATAHUB.SUITE_REFINED.AGENT_STATE_DIM_VIEW_V011";
    public static final String VIEW_USER_DIM             = "DATAHUB.SUITE_REFINED.USER_DIM_VIEW_V001";

    private final String account;
    private final String user;
    private final String password;
    private final String token;
    private final String authenticator;
    private final String warehouse;
    private final String database;
    private final String role;
    private final String schema;
    private final int queryTimeout;

    /** True only when all non-placeholder credentials are present. */
    private final boolean configured;

    public SnowflakeExecutor(
            @Value("${snowflake.account:placeholder-account}")    String account,
            @Value("${snowflake.user:placeholder-user}")          String user,
            @Value("${snowflake.password:}")                      String password,
            @Value("${snowflake.token:}")                         String token,
            @Value("${snowflake.authenticator:}")                 String authenticator,
            @Value("${snowflake.warehouse:placeholder-warehouse}") String warehouse,
            @Value("${snowflake.database:DATAHUB}")               String database,
            @Value("${snowflake.role:placeholder-role}")          String role,
            @Value("${snowflake.schema:SUITE_REFINED}")           String schema,
            @Value("${snowflake.query-timeout:60}")               int queryTimeout) {
        this.account       = account;
        this.user          = user;
        this.password      = password;
        this.token         = token;
        this.authenticator = authenticator;
        this.warehouse     = warehouse;
        this.database      = database;
        this.role          = role;
        this.schema        = schema;
        this.queryTimeout  = queryTimeout;

        boolean hasToken = token != null && !token.isBlank();
        boolean hasPassword = password != null && !password.isBlank() && !password.contains("placeholder");
        this.configured = !account.contains("placeholder")
                && !user.contains("placeholder")
                && (hasToken || hasPassword);

        if (configured) {
            try {
                Class.forName("net.snowflake.client.jdbc.SnowflakeDriver");
            } catch (ClassNotFoundException e) {
                log.error("Snowflake JDBC driver not found on classpath");
            }
            String authMode = hasToken ? "programmatic_access_token" : "password";
            log.info("SnowflakeExecutor initialised — account={} database={} auth={}", account, database, authMode);
        } else {
            log.warn("SnowflakeExecutor is NOT configured (placeholder credentials) — " +
                    "set snowflake.* properties to enable real queries");
        }
    }

    /**
     * Returns {@code true} when real Snowflake credentials are available.
     */
    public boolean isConfigured() {
        return configured;
    }

    /**
     * Execute {@code sql} against Snowflake and return the rows as a list of maps.
     *
     * @throws RuntimeException if the JDBC call fails; callers catch this and fall back to stubs.
     */
    private static final java.util.Set<String> BLOCKED_KEYWORDS = java.util.Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE",
            "MERGE", "GRANT", "REVOKE", "EXEC", "EXECUTE", "CALL", "PUT", "COPY");

    public List<Map<String, Object>> execute(String sql) {
        if (!configured) {
            throw new IllegalStateException("Snowflake is not configured — cannot execute real query");
        }

        // Safety: reject any DML/DDL before it reaches Snowflake
        String upper = sql.stripLeading().toUpperCase();
        for (String kw : BLOCKED_KEYWORDS) {
            if (upper.startsWith(kw + " ") || upper.startsWith(kw + "\n") || upper.startsWith(kw + "\t")) {
                throw new IllegalArgumentException("Blocked: only SELECT/WITH queries allowed, got: " + kw);
            }
        }

        String url = String.format(
                "jdbc:snowflake://%s.snowflakecomputing.com/?warehouse=%s&db=%s&schema=%s&role=%s",
                account, warehouse, database, schema, role);

        java.util.Properties props = new java.util.Properties();
        props.put("user", user);
        if (token != null && !token.isBlank()) {
            props.put("authenticator", "programmatic_access_token");
            props.put("token", token);
        } else {
            props.put("password", password);
        }
        props.put("JDBC_QUERY_RESULT_FORMAT", "JSON");

        try (Connection conn = DriverManager.getConnection(url, props);
             Statement  stmt = conn.createStatement()) {
            conn.setReadOnly(true);
            stmt.setQueryTimeout(queryTimeout);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                return resultSetToList(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Snowflake query failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // ResultSet → List<Map> conversion (pattern from nrt-cache repo)
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> resultSetToList(ResultSet rs) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                row.put(meta.getColumnName(i), rs.getObject(i));
            }
            results.add(row);
        }
        return results;
    }
}
