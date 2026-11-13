package org.egov.idgen.api;

import java.util.Map;

/** The one error shape: {@code {"error":{"code":"…","message":"…","details":{…}}}}. */
public record ErrorBody(Error error) {

    public record Error(String code, String message, Map<String, Object> details) {}

    public static ErrorBody of(String code, String message, Map<String, Object> details) {
        return new ErrorBody(new Error(code, message, details == null ? Map.of() : details));
    }
}
