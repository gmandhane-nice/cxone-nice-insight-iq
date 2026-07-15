package com.nice.agentic.query;

/**
 * Contract for executing a {@link QueryDescriptor} against a backing data store and
 * returning a {@link TabularResult}.
 *
 * This interface is owned by the query-layer contract (Agent A). It is reproduced here
 * so the module compiles independently while Agent A's code is not yet merged.
 *
 * The primary implementation is {@link RealQueryExecutor}.
 */
public interface QueryExecutor {
    TabularResult execute(QueryDescriptor descriptor);
}
