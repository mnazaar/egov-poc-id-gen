package org.egov.idgen.template;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** One piece of a parsed template format: literal text or a placeholder. */
public sealed interface Token {

    /** A token whose rendered value depends on the date. Its rendered value scopes the counter (ADR-003). */
    sealed interface Period extends Token {
        String render(ZonedDateTime now);
    }

    /** Text copied unchanged, e.g. {@code "PG-"} or {@code "/"}. */
    record Literal(String text) implements Token {}

    /** {@code [CITY]}: the resolved tenant's city code. */
    record CityCode() implements Token {}

    /** {@code [FY:yyyy-yy]} or {@code [FY:yy-yy]}: Indian financial year, April to March. */
    record FinancialYear(boolean fourDigitStart) implements Period {
        @Override
        public String render(ZonedDateTime now) {
            int start = now.getMonthValue() >= 4 ? now.getYear() : now.getYear() - 1;
            String end = two(start + 1);
            return (fourDigitStart ? Integer.toString(start) : two(start)) + "-" + end;
        }

        private static String two(int year) {
            return String.format("%02d", Math.floorMod(year, 100));
        }
    }

    /** {@code [CY:fmt]}: the current date formatted with a {@code java.time} pattern. */
    record CalendarDate(String pattern, DateTimeFormatter formatter) implements Period {
        @Override
        public String render(ZonedDateTime now) {
            return formatter.format(now);
        }
    }

    /**
     * {@code [SEQ:n]} (implicit counter, {@code counter == null}) or {@code [SEQ_NAME:n]} (named counter).
     * {@code width} is a minimum: wider values are printed in full, never truncated.
     */
    record Sequence(String counter, int width) implements Token {
        public boolean named() {
            return counter != null;
        }
    }
}
