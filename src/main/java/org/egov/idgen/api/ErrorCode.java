package org.egov.idgen.api;

/** Every error the API can return, with its HTTP status. See "API contract" in docs/architecture.md. */
public enum ErrorCode {
    VALIDATION_ERROR(400),
    INVALID_COUNT(400),
    UNKNOWN_TEMPLATE(404),
    UNKNOWN_CITY(404),
    COUNTER_NOT_CONFIGURED(422),
    SEQUENCE_UNAVAILABLE(503),
    INTERNAL_ERROR(500);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
