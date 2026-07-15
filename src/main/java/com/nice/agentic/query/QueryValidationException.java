package com.nice.agentic.query;

/**
 * Thrown by {@link QueryValidator} when a {@link QueryDescriptor} fails validation.
 * The {@code errorCode} identifies which rule was violated.
 */
public class QueryValidationException extends RuntimeException {

    private final String errorCode;

    public QueryValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
