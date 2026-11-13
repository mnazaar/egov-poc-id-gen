package org.egov.idgen.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Turns every failure into the one error shape, never a stack trace. Expected errors keep their code; anything
 * unexpected becomes INTERNAL_ERROR with a correlation ID that is also logged with the full exception.
 */
public class ErrorMapper {

    private static final Logger LOG = Logger.getLogger(ErrorMapper.class);

    @ServerExceptionMapper
    public Response idgen(IdgenException e) {
        if (e.code() == ErrorCode.SEQUENCE_UNAVAILABLE) {
            LOG.warnf(e.getCause(), "Sequence store unavailable: %s", e.getMessage());
        }
        return build(e.code().status(), e.code().name(), e.getMessage(), e.details());
    }

    /** Malformed JSON body. */
    @ServerExceptionMapper
    public Response badJson(JsonProcessingException e) {
        return build(400, ErrorCode.VALIDATION_ERROR.name(), "Request body is not valid JSON",
                Map.of("reason", e.getOriginalMessage()));
    }

    /** Framework errors: unknown path (404), wrong method (405), wrong content type (415), bad request (400). */
    @ServerExceptionMapper
    public Response web(WebApplicationException e) {
        int status = e.getResponse().getStatus();
        if (status >= 500) {
            return internal(e);
        }
        if (status == 400) {
            String message = e.getCause() instanceof JsonProcessingException
                    ? "Request body is not valid JSON" : "Invalid request";
            return build(400, ErrorCode.VALIDATION_ERROR.name(), message, Map.of());
        }
        Response.Status known = Response.Status.fromStatusCode(status);
        String code = known == null ? "HTTP_" + status : known.name();
        String message = known == null ? "HTTP " + status : known.getReasonPhrase();
        return build(status, code, message, Map.of());
    }

    @ServerExceptionMapper
    public Response unexpected(Throwable e) {
        return internal(e);
    }

    private static Response internal(Throwable e) {
        String correlationId = UUID.randomUUID().toString();
        LOG.errorf(e, "Unexpected error, correlationId=%s", correlationId);
        return build(500, ErrorCode.INTERNAL_ERROR.name(), "Unexpected error; quote the correlation ID when reporting it",
                Map.of("correlationId", correlationId));
    }

    private static Response build(int status, String code, String message, Map<String, Object> details) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(ErrorBody.of(code, message, details))
                .build();
    }
}
