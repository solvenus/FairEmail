package eu.faircode.email;

public final class AliasCompromisePolicyLab {
    private AliasCompromisePolicyLab() {
    }

    public static void main(String[] args) {
        AliasCompromisePolicy.Input expectedSpam = input();
        expectedSpam.explicitSpam = true;
        expectedSpam.senderKnown = true;
        expectedSpam.expectedContextKnown = true;
        expectedSpam.expectedSenderMatch = true;
        require(AliasCompromisePolicy.decide(expectedSpam) == AliasCompromisePolicy.Decision.KEEP,
                "spam from expected service sender must not imply alias compromise");

        AliasCompromisePolicy.Input foreign = input();
        foreign.explicitSpam = true;
        foreign.senderKnown = true;
        foreign.expectedContextKnown = true;
        foreign.expectedSenderMatch = false;
        require(AliasCompromisePolicy.decide(foreign) == AliasCompromisePolicy.Decision.COMPROMISE,
                "foreign spam sender on known service alias should compromise alias");

        AliasCompromisePolicy.Input suspicious = input();
        suspicious.explicitSpam = true;
        suspicious.trafficSuspicious = true;
        require(AliasCompromisePolicy.decide(suspicious) == AliasCompromisePolicy.Decision.COMPROMISE,
                "independently suspicious traffic should compromise alias");

        AliasCompromisePolicy.Input established = input();
        established.explicitSpam = true;
        established.senderKnown = true;
        established.aliasHam = 12;
        require(AliasCompromisePolicy.decide(established) == AliasCompromisePolicy.Decision.COMPROMISE,
                "new spam sender on established legitimate alias should compromise alias");

        AliasCompromisePolicy.Input ambiguous = input();
        ambiguous.explicitSpam = true;
        require(AliasCompromisePolicy.decide(ambiguous) == AliasCompromisePolicy.Decision.REVIEW,
                "spam without alias-context evidence must remain a separate compromise question");

        AliasCompromisePolicy.Input ham = input();
        ham.explicitSpam = false;
        ham.trafficSuspicious = true;
        require(AliasCompromisePolicy.decide(ham) == AliasCompromisePolicy.Decision.KEEP,
                "non-spam action must never compromise alias through this policy");

        System.out.println("PASS: spam truth and alias compromise truth remain separate");
    }

    private static AliasCompromisePolicy.Input input() {
        return new AliasCompromisePolicy.Input();
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
