package org.egov.idgen.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TemplateParserTest {

    @Test
    void parsesEveryPlaceholderKind() {
        List<Token> t = TemplateParser.parse("PG_RECEIPT", "PG-[CITY]-[FY:yyyy-yy]-[SEQ:6]");
        assertEquals(List.of(
                new Token.Literal("PG-"),
                new Token.CityCode(),
                new Token.Literal("-"),
                new Token.FinancialYear(true),
                new Token.Literal("-"),
                new Token.Sequence(null, 6)), t);
    }

    @Test
    void parsesNamedCounterAndCalendarDate() {
        List<Token> t = TemplateParser.parse("WS_CONNECTION", "WS-[CITY]-[SEQ_WS:4]-[CY:MM-yyyy]");
        assertEquals(new Token.Sequence("SEQ_WS", 4), t.get(3));
        Token.CalendarDate cy = assertInstanceOf(Token.CalendarDate.class, t.get(5));
        assertEquals("MM-yyyy", cy.pattern());
    }

    @Test
    void parsesShortFinancialYearAndSlashLiterals() {
        assertEquals(new Token.FinancialYear(false),
                TemplateParser.parse("BPA_PERMIT", "BPA-[CITY]-[FY:yy-yy]-[SEQ_BPA_PERMIT:6]").get(3));
        assertEquals(new Token.Literal("/"), TemplateParser.parse("TL_LICENSE", "TL/[CITY]/[CY:yyyy]/[SEQ:5]").get(2));
    }

    @ParameterizedTest(name = "{0} -> position {1}")
    @CsvSource(delimiter = '|', value = {
            "X-[FOO]-[SEQ:3]        | 3  | unknown placeholder [FOO]",
            "X-[FY:yyyy]-[SEQ:3]    | 3  | unknown placeholder [FY:yyyy]",
            "X-[SEQ:3]-[CITY        | 11 | never closed",
            "X-[SEQ:3]]             | 10 | without a matching",
            "X-[SEQ:0]              | 3  | width must be",
            "X-[CY:]-[SEQ:3]        | 3  | needs a date pattern",
            "X-[CY:yyyy{]-[SEQ:3]   | 3  | invalid date pattern",
            "X-[seq:3]              | 3  | unknown placeholder",
    })
    void rejectsBadSyntaxWithTemplateAndPosition(String format, int position, String message) {
        String trimmed = format.trim();
        TemplateParseException e = assertThrows(TemplateParseException.class,
                () -> TemplateParser.parse("BAD", trimmed));
        assertEquals("BAD", e.templateId());
        assertEquals(position, e.position());
        assertTrue(e.getMessage().contains(message), e.getMessage());
        assertTrue(e.getMessage().startsWith("Template BAD, position " + position), e.getMessage());
    }

    @Test
    void rejectsTemplateWithoutSequenceBecauseEveryIdWouldBeTheSame() {
        TemplateParseException e = assertThrows(TemplateParseException.class,
                () -> TemplateParser.parse("NOSEQ", "X-[CITY]-[CY:yyyy]"));
        assertTrue(e.getMessage().contains("every ID would be the same"), e.getMessage());
    }

    @Test
    void rejectsTwoSequences() {
        assertThrows(TemplateParseException.class, () -> TemplateParser.parse("TWO", "[SEQ:3]-[SEQ_WS:3]"));
    }
}
