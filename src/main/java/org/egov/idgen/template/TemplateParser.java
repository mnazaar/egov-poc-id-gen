package org.egov.idgen.template;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a template format such as {@code PG-[CITY]-[FY:yyyy-yy]-[SEQ:6]} into tokens, once, at startup.
 *
 * <p>Grammar: {@code [CITY]}, {@code [FY:yyyy-yy]}, {@code [FY:yy-yy]}, {@code [CY:fmt]}, {@code [SEQ:n]},
 * {@code [SEQ_NAME:n]}. Anything else inside brackets is an error, as is a template without exactly one
 * sequence placeholder (with none, every ID it produces would be identical).
 */
public final class TemplateParser {

    private static final Pattern SEQUENCE = Pattern.compile("(SEQ(?:_[A-Z0-9_]+)?):(\\d{1,2})");
    private static final int MAX_WIDTH = 18; // a long has 19 digits; keep the minimum width below that

    private TemplateParser() {}

    public static List<Token> parse(String templateId, String format) {
        if (format == null || format.isEmpty()) {
            throw new TemplateParseException(templateId, 0, "format is empty");
        }
        List<Token> tokens = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < format.length()) {
            char ch = format.charAt(i);
            if (ch == ']') {
                throw new TemplateParseException(templateId, i + 1, "']' without a matching '['");
            }
            if (ch != '[') {
                literal.append(ch);
                i++;
                continue;
            }
            int close = format.indexOf(']', i);
            int nextOpen = format.indexOf('[', i + 1);
            if (close < 0 || (nextOpen >= 0 && nextOpen < close)) {
                throw new TemplateParseException(templateId, i + 1, "'[' is never closed");
            }
            if (!literal.isEmpty()) {
                tokens.add(new Token.Literal(literal.toString()));
                literal.setLength(0);
            }
            tokens.add(placeholder(templateId, format.substring(i + 1, close), i + 1));
            i = close + 1;
        }
        if (!literal.isEmpty()) {
            tokens.add(new Token.Literal(literal.toString()));
        }

        long sequences = tokens.stream().filter(t -> t instanceof Token.Sequence).count();
        if (sequences != 1) {
            throw new TemplateParseException(templateId, 0, sequences == 0
                    ? "no [SEQ:n] or [SEQ_NAME:n] placeholder, so every ID would be the same"
                    : sequences + " sequence placeholders; exactly one is supported");
        }
        return List.copyOf(tokens);
    }

    private static Token placeholder(String templateId, String body, int position) {
        if (body.equals("CITY")) {
            return new Token.CityCode();
        }
        if (body.equals("FY:yyyy-yy")) {
            return new Token.FinancialYear(true);
        }
        if (body.equals("FY:yy-yy")) {
            return new Token.FinancialYear(false);
        }
        if (body.startsWith("CY:")) {
            String pattern = body.substring(3);
            if (pattern.isEmpty()) {
                throw new TemplateParseException(templateId, position, "[CY:] needs a date pattern");
            }
            try {
                return new Token.CalendarDate(pattern, DateTimeFormatter.ofPattern(pattern, Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new TemplateParseException(templateId, position,
                        "invalid date pattern '" + pattern + "': " + e.getMessage());
            }
        }
        Matcher m = SEQUENCE.matcher(body);
        if (m.matches()) {
            int width = Integer.parseInt(m.group(2));
            if (width < 1 || width > MAX_WIDTH) {
                throw new TemplateParseException(templateId, position,
                        "sequence width must be 1-" + MAX_WIDTH + ", got " + width);
            }
            String name = m.group(1);
            return new Token.Sequence(name.equals("SEQ") ? null : name, width);
        }
        throw new TemplateParseException(templateId, position, "unknown placeholder [" + body + "]");
    }
}
