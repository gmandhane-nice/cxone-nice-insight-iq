package com.nice.agentic.query;

import java.util.List;
import java.util.Map;

/**
 * A typed result from a {@link QueryExecutor}: ordered column names plus a list of
 * row maps (column → value).
 *
 * This record is owned by the query-layer contract (Agent A). It is reproduced here
 * so the module compiles independently while Agent A's code is not yet merged.
 */
public record TabularResult(
    /** Column names in the order they were returned by the backing store. */
    List<String> columns,

    /** Rows as column-name → raw value maps. Values are typically String or Number. */
    List<Map<String, Object>> rows
) {}
