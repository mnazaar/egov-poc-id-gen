package org.egov.idgen.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The two codes that can't be triggered through the running API on demand. */
class ErrorMapperTest {

    final ErrorMapper mapper = new ErrorMapper();

    @Test
    void sequenceUnavailableIs503WithTheSameShape() {
        Response r = mapper.idgen(new IdgenException(ErrorCode.SEQUENCE_UNAVAILABLE,
                "The sequence store is unavailable; try again shortly", Map.of(), new RuntimeException("refused")));
        assertEquals(503, r.getStatus());
        ErrorBody body = (ErrorBody) r.getEntity();
        assertEquals("SEQUENCE_UNAVAILABLE", body.error().code());
        assertEquals("The sequence store is unavailable; try again shortly", body.error().message());
        assertEquals(Map.of(), body.error().details());
    }

    @Test
    void unexpectedErrorIs500WithACorrelationIdAndNoInternals() {
        Response r = mapper.unexpected(new NullPointerException("secret internal detail"));
        assertEquals(500, r.getStatus());
        ErrorBody body = (ErrorBody) r.getEntity();
        assertEquals("INTERNAL_ERROR", body.error().code());
        assertNotNull(body.error().details().get("correlationId"));
        assertTrue(!body.error().message().contains("secret"), body.error().message());
    }
}
