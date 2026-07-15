package com.nice.agentic.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link QueryValidator}, covering all error codes.
 */
class QueryValidatorTest {

    private QueryValidator validator;

    @BeforeEach
    void setUp() {
        validator = new QueryValidator();
    }

    // -------------------------------------------------------------------------
    // STORE_NOT_ALLOWED
    // -------------------------------------------------------------------------

    @Test
    void rejectsUnknownStore() {
        QueryDescriptor desc = new QueryDescriptor(
                "mysql", "ops_events", List.of(), List.of(), "last_3h", 10, List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(desc))
                .isInstanceOf(QueryValidationException.class)
                .satisfies(ex -> assertThat(((QueryValidationException) ex).getErrorCode())
                        .isEqualTo("STORE_NOT_ALLOWED"));
    }

    @Test
    void rejectsNullStore() {
        QueryDescriptor desc = new QueryDescriptor(
                null, "ops_events", List.of(), List.of(), "last_3h", 10, List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(desc))
                .isInstanceOf(QueryValidationException.class)
                .satisfies(ex -> assertThat(((QueryValidationException) ex).getErrorCode())
                        .isEqualTo("STORE_NOT_ALLOWED"));
    }

    // -------------------------------------------------------------------------
    // TABLE_NOT_ALLOWED
    // -------------------------------------------------------------------------

    @Test
    void rejectsUnknownTable() {
        QueryDescriptor desc = new QueryDescriptor(
                "snowflake", "users", List.of(), List.of(), "last_3h", 10, List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(desc))
                .isInstanceOf(QueryValidationException.class)
                .satisfies(ex -> assertThat(((QueryValidationException) ex).getErrorCode())
                        .isEqualTo("TABLE_NOT_ALLOWED"));
    }

    // -------------------------------------------------------------------------
    // DANGEROUS_KEYWORD
    // -------------------------------------------------------------------------

    @Test
    void rejectsDangerousKeywordDrop() {
        QueryDescriptor desc = new QueryDescriptor(
                "snowflake", "ops_events",
                List.of("scope=Banking", "DROP TABLE users"),
                List.of(), "last_3h", 10, List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(desc))
                .isInstanceOf(QueryValidationException.class)
                .satisfies(ex -> assertThat(((QueryValidationException) ex).getErrorCode())
                        .isEqualTo("DANGEROUS_KEYWORD"));
    }

    @Test
    void rejectsDangerousKeywordUnion() {
        QueryDescriptor desc = new QueryDescriptor(
                "opensearch", "contact_events",
                List.of("scope=Banking UNION SELECT *"),
                List.of(), "last_3h", 10, List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(desc))
                .isInstanceOf(QueryValidationException.class)
                .satisfies(ex -> assertThat(((QueryValidationException) ex).getErrorCode())
                        .isEqualTo("DANGEROUS_KEYWORD"));
    }

    @Test
    void allowsSafeFilters() {
        QueryDescriptor desc = new QueryDescriptor(
                "snowflake", "agent_metrics",
                List.of("scope=Banking", "event_type=deploy"),
                List.of(), "last_3h", 10, List.of(), List.of());

        assertThatNoException().isThrownBy(() -> validator.validate(desc));
    }

    // -------------------------------------------------------------------------
    // TIME_RANGE — no cap, all ranges are accepted
    // -------------------------------------------------------------------------

    @Test
    void acceptsLargeTimeRange() {
        QueryDescriptor desc = new QueryDescriptor(
                "snowflake", "ops_events", List.of(), List.of(), "last_720d", 10, List.of(), List.of());

        assertThatNoException().isThrownBy(() -> validator.validate(desc));
    }

    @Test
    void accepts24hExactly() {
        QueryDescriptor desc = new QueryDescriptor(
                "snowflake", "ops_events", List.of(), List.of(), "last_24h", 10, List.of(), List.of());

        assertThatNoException().isThrownBy(() -> validator.validate(desc));
    }

    // -------------------------------------------------------------------------
    // LIMIT_TOO_LARGE
    // -------------------------------------------------------------------------

    @Test
    void rejectsLimitOver500() {
        QueryDescriptor desc = new QueryDescriptor(
                "snowflake", "ops_events", List.of(), List.of(), "last_3h", 501, List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(desc))
                .isInstanceOf(QueryValidationException.class)
                .satisfies(ex -> assertThat(((QueryValidationException) ex).getErrorCode())
                        .isEqualTo("LIMIT_TOO_LARGE"));
    }

    @Test
    void accepts500LimitExactly() {
        QueryDescriptor desc = new QueryDescriptor(
                "snowflake", "ops_events", List.of(), List.of(), "last_3h", 500, List.of(), List.of());

        assertThatNoException().isThrownBy(() -> validator.validate(desc));
    }

    @Test
    void acceptsZeroLimit() {
        QueryDescriptor desc = new QueryDescriptor(
                "snowflake", "ops_events", List.of(), List.of(), "last_3h", 0, List.of(), List.of());

        assertThatNoException().isThrownBy(() -> validator.validate(desc));
    }

    // -------------------------------------------------------------------------
    // COLUMN VALIDATION (select / groupBy)
    // -------------------------------------------------------------------------

    @Test
    void rejectsUnsafeSelectColumn() {
        QueryDescriptor desc = new QueryDescriptor(
                "snowflake", "agent_session_activity", List.of(), List.of(), "last_7d", 50,
                List.of("AGENT_NO", "1; DROP TABLE"), List.of());

        assertThatThrownBy(() -> validator.validate(desc))
                .isInstanceOf(QueryValidationException.class)
                .satisfies(ex -> assertThat(((QueryValidationException) ex).getErrorCode())
                        .isEqualTo("DANGEROUS_KEYWORD"));
    }

    @Test
    void acceptsSafeSelectAndGroupBy() {
        QueryDescriptor desc = new QueryDescriptor(
                "snowflake", "agent_session_activity", List.of(), List.of(), "last_7d", 50,
                List.of("AGENT_NO", "USER_ID"), List.of("AGENT_NO", "USER_ID"));

        assertThatNoException().isThrownBy(() -> validator.validate(desc));
    }
}
