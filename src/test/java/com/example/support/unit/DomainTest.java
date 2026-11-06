package com.example.support.unit;

import com.example.support.domain.AccountIds;
import com.example.support.domain.CaseStatus;
import com.example.support.domain.EscalationRules;
import com.example.support.domain.EscalationTarget;
import com.example.support.domain.Priority;
import com.example.support.domain.SupportCase;
import com.example.support.service.Cursor;
import com.example.support.web.ProblemException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DomainTest {

    private static SupportCase c(Priority p, CaseStatus s) {
        return new SupportCase("CS-1", "ACC-001", p, s, "x", Instant.EPOCH, null, 1, List.of());
    }

    @ParameterizedTest
    @CsvSource({"ACC-77,ACC-077", "acc-007,ACC-007", "ACC-0007,ACC-007", "ACC007,ACC-007", " ACC-1234 ,ACC-1234"})
    void accountIdsAreNormalised(String raw, String expected) {
        Assertions.assertEquals(expected, AccountIds.normalize(raw));
    }

    @Test
    void priorityRaisesOneStepAndStopsAtP1() {
        Assertions.assertEquals(Priority.P2, Priority.P3.raise());
        Assertions.assertEquals(Priority.P1, Priority.P2.raise());
        Assertions.assertEquals(Priority.P1, Priority.P1.raise());
    }

    @Test
    void openAndPendingCasesCanBeEscalated() {
        EscalationRules.check(c(Priority.P2, CaseStatus.open), EscalationTarget.management);
        EscalationRules.check(c(Priority.P3, CaseStatus.pending), EscalationTarget.tier2);
    }

    @ParameterizedTest
    @CsvSource({
        "P2,escalated,tier2,case-already-escalated",
        "P2,closed,tier2,case-not-escalatable",
        "P2,resolved,engineering,case-not-escalatable",
        "P4,open,tier2,business-rule-violation",
        "P3,open,management,business-rule-violation"})
    void rulesRejectWithTheDocumentedCode(Priority p, CaseStatus s, EscalationTarget t, String code) {
        var ex = Assertions.assertThrows(ProblemException.class, () -> EscalationRules.check(c(p, s), t));
        Assertions.assertEquals(code, ex.code());
    }

    @Test
    void cursorRoundTripsAndRejectsTampering() {
        String token = new Cursor(1234L, "CS-1", "abc").encode();
        Cursor back = Cursor.decode(token, "abc");
        Assertions.assertEquals(1234L, back.key());
        Assertions.assertEquals("CS-1", back.id());
        Assertions.assertEquals("invalid-cursor", Assertions.assertThrows(ProblemException.class, () -> Cursor.decode(token, "other")).code());
        Assertions.assertEquals("invalid-cursor", Assertions.assertThrows(ProblemException.class, () -> Cursor.decode("%%%", "abc")).code());
    }
}
