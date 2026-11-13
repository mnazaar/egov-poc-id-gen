package org.egov.idgen.template;

/**
 * Identifies one sequence of numbers (ADR-003). A counter is scoped by exactly the variable parts printed in
 * the IDs it produces: the city only if the template prints {@code [CITY]}, the period only if it prints a
 * date placeholder.
 *
 * @param kind     an implicit {@code [SEQ:n]} counter (owned by one template) or a named, shared one
 * @param counter  the template ID for an implicit counter, else the counter name ({@code SEQ_WS})
 * @param cityCode the city code, or {@code null} when the IDs don't print a city
 * @param period   the rendered date placeholders joined by {@code |}, or {@code null} when none are printed
 */
public record CounterKey(Kind kind, String counter, String cityCode, String period) {

    public enum Kind { TEMPLATE, NAMED }

    @Override
    public String toString() {
        return kind + ":" + counter + "|" + (cityCode == null ? "*" : cityCode) + "|" + (period == null ? "*" : period);
    }
}
