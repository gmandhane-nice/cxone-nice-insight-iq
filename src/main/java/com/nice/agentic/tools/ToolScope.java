package com.nice.agentic.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the agent scope(s) a tool participates in (e.g. "rca", "staffing").
 * Used by the tool registry to filter available tools per invocation context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolScope {
    String[] value();
}
