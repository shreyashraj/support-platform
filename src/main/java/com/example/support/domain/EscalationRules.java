package com.example.support.domain;

import com.example.support.web.ProblemException;

public final class EscalationRules {
    private EscalationRules() {}

    public static void check(SupportCase c, EscalationTarget target) {
        if (c.status() == CaseStatus.escalated) {
            throw ProblemException.conflict("case-already-escalated", "Case is already escalated",
                    "Case " + c.id() + " was escalated at " + c.escalatedAt() + ".");
        }
        if (c.status() != CaseStatus.open && c.status() != CaseStatus.pending) {
            throw ProblemException.conflict("case-not-escalatable", "Case cannot be escalated",
                    "Case " + c.id() + " is " + c.status() + "; only open or pending cases can be escalated.");
        }
        if (c.priority() == Priority.P4) {
            throw ProblemException.unprocessable("business-rule-violation", "P4 cases cannot be escalated",
                    "Raise the priority of " + c.id() + " before escalating it.");
        }
        if (target == EscalationTarget.management && c.priority() != Priority.P1 && c.priority() != Priority.P2) {
            throw ProblemException.unprocessable("business-rule-violation", "Management escalation needs P1 or P2",
                    "Case " + c.id() + " is " + c.priority() + "; use tier2 or engineering.");
        }
    }
}
