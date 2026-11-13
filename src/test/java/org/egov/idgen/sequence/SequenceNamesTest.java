package org.egov.idgen.sequence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.egov.idgen.template.CounterKey;
import org.egov.idgen.template.CounterKey.Kind;
import org.junit.jupiter.api.Test;

class SequenceNamesTest {

    @Test
    void namesFollowTheSpecExamples() {
        assertEquals("seq_pg_receipt_amr_2026_27",
                SequenceNames.of(new CounterKey(Kind.TEMPLATE, "PG_RECEIPT", "AMR", "2026-27")));
        assertEquals("seq_pgr_complaint", SequenceNames.of(new CounterKey(Kind.TEMPLATE, "PGR_COMPLAINT", null, null)));
        assertEquals("seq_ws_amr_11_2026", SequenceNames.of(new CounterKey(Kind.NAMED, "SEQ_WS", "AMR", "11-2026")));
        assertEquals("seq_tl_license_amr_2026", SequenceNames.of(new CounterKey(Kind.TEMPLATE, "TL_LICENSE", "AMR", "2026")));
    }

    @Test
    void periodsAndCitiesGiveDistinctNames() {
        assertNotEquals(SequenceNames.of(new CounterKey(Kind.TEMPLATE, "PG_RECEIPT", "AMR", "2026-27")),
                SequenceNames.of(new CounterKey(Kind.TEMPLATE, "PG_RECEIPT", "AMR", "2027-28")));
        assertNotEquals(SequenceNames.of(new CounterKey(Kind.TEMPLATE, "PG_RECEIPT", "AMR", "2026-27")),
                SequenceNames.of(new CounterKey(Kind.TEMPLATE, "PG_RECEIPT", "LDH", "2026-27")));
    }

    @Test
    void longNamesAreTruncatedWithAHashWithinPostgresLimit() {
        String longId = "A_VERY_LONG_TEMPLATE_IDENTIFIER_THAT_GOES_ON_AND_ON_AND_ON";
        String a = SequenceNames.of(new CounterKey(Kind.TEMPLATE, longId, "AMR", "2026-27"));
        String b = SequenceNames.of(new CounterKey(Kind.TEMPLATE, longId, "AMR", "2027-28"));
        assertTrue(a.length() <= 63, a);
        assertTrue(a.matches("[a-z0-9_]+"), a);
        assertNotEquals(a, b, "the hash keeps truncated names apart");
    }

    @Test
    void oddCharactersBecomeUnderscores() {
        assertEquals("seq_x_amr_11_2026", SequenceNames.of(new CounterKey(Kind.TEMPLATE, "X", "AMR", "11/2026")));
    }

    @Test
    void narrowCountersGetSmallBlocks() {
        assertEquals(10, SequenceSpec.incrementFor(4, 1000, 100), "SEQ_WS:4 from 1000 has only 9,000 values");
        assertEquals(100, SequenceSpec.incrementFor(6, 1, 100));
        assertEquals(100, SequenceSpec.incrementFor(5, 1, 100), "TL [SEQ:5] has 99,999 values");
        assertEquals(10, SequenceSpec.incrementFor(3, 1, 100));
    }
}
