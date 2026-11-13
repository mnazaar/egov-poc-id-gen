package org.egov.idgen.config;

/** Invalid or missing configuration in data/. Stops startup with a message naming the file and line. */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
