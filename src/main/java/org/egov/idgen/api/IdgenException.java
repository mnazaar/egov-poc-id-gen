package org.egov.idgen.api;

import java.util.Map;

/** A failure the caller should see as a structured error, never as a stack trace. */
public class IdgenException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public IdgenException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public IdgenException(ErrorCode code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public IdgenException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
