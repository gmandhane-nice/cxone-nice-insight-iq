package com.nice.agentic.widget;

import java.util.List;

/**
 * Describes a registered widget: its ID, a human-readable description, and the
 * argument names that callers must supply when resolving it.
 */
public record WidgetSpec(String widgetId, String description, List<String> requiredArgs) {}
