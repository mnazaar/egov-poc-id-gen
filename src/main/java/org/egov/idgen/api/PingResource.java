package org.egov.idgen.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Liveness smoke endpoint. Returns the serving instance's hostname so you can
 * see Nginx round-robin across backend replicas.
 */
@Path("/api/ping")
@Produces(MediaType.APPLICATION_JSON)
public class PingResource {

    private static final String INSTANCE = resolveInstance();

    public record Pong(String status, String instance) {}

    @GET
    public Pong ping() {
        return new Pong("ok", INSTANCE);
    }

    private static String resolveInstance() {
        String host = System.getenv("HOSTNAME");
        if (host != null && !host.isBlank()) {
            return host;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unknown";
        }
    }
}
