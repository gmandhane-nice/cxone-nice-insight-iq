package com.nice.agentic.query;

import java.util.List;

/**
 * Describes a data query produced by {@link QueryGenerator}: which backing store to target,
 * which table/index to read, optional filters, aggregations, time range, and row limit.
 *
 * Owned by Agent A — this is the canonical definition.
 *
 * @param store        backing store — one of "opensearch" or "snowflake"
 * @param indexOrTable table (Snowflake) or index (OpenSearch) name
 * @param filters      key=value filter strings, e.g. ["scope=Banking", "event_type=deploy"]
 * @param aggs         aggregation strings, e.g. ["count", "group_by:hour"]
 * @param timeRange    duration string, e.g. "last_3h" or an ISO interval
 * @param limit        maximum number of rows to return
 * @param select       columns to select, e.g. ["AGENT_NO", "USER_ID"]. Empty = SELECT *
 * @param groupBy      columns to group by, e.g. ["AGENT_NO"]. Empty = no GROUP BY
 */
public record QueryDescriptor(
    String store,
    String indexOrTable,
    List<String> filters,
    List<String> aggs,
    String timeRange,
    int limit,
    List<String> select,
    List<String> groupBy
) {}
