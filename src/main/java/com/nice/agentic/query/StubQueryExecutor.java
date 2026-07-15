package com.nice.agentic.query;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Fallback {@link QueryExecutor} used when no real implementation is present.
 *
 * Annotated {@link ConditionalOnMissingBean} so that it is superseded automatically once
 * Agent B wires the real {@link QueryExecutor} implementation.
 */
@Component
@ConditionalOnMissingBean(QueryExecutor.class)
public class StubQueryExecutor implements QueryExecutor {

    @Override
    public TabularResult execute(QueryDescriptor descriptor) {
        return new TabularResult(
                List.of("note"),
                List.of(Map.of("note", "stub: real executor not yet wired")));
    }
}
