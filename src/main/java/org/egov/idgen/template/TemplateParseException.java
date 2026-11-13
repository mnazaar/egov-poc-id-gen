package org.egov.idgen.template;

/** A template format that can't be parsed. Names the template and the 1-based character position. */
public class TemplateParseException extends RuntimeException {

    private final String templateId;
    private final int position;

    public TemplateParseException(String templateId, int position, String message) {
        super("Template " + templateId + (position > 0 ? ", position " + position : "") + ": " + message);
        this.templateId = templateId;
        this.position = position;
    }

    public String templateId() {
        return templateId;
    }

    public int position() {
        return position;
    }
}
