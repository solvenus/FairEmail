package eu.faircode.email;

/**
 * Pure policy that decides whether an explicit spam classification is enough
 * evidence to move an alias lifecycle into COMPROMISED.
 *
 * Message spam truth and alias-compromise truth are deliberately separate.
 */
public final class AliasCompromisePolicy {
    private AliasCompromisePolicy() {
    }

    public enum Decision {
        KEEP,
        COMPROMISE,
        REVIEW
    }

    public static final class Input {
        public boolean explicitSpam;
        public boolean trafficSuspicious;
        public boolean senderKnown;
        public boolean expectedContextKnown;
        public boolean expectedSenderMatch;
        public int aliasHam;
    }

    public static Decision decide(Input input) {
        if (input == null)
            throw new IllegalArgumentException("input");
        if (!input.explicitSpam)
            return Decision.KEEP;

        // Spam from the service/domain this alias is actually intended for does
        // not prove that the address leaked. The message can be spam while the
        // alias remains healthy.
        if (input.expectedContextKnown && input.expectedSenderMatch)
            return Decision.KEEP;

        // Independent alias evidence is strong enough to make the lifecycle
        // transition automatically.
        if (input.trafficSuspicious)
            return Decision.COMPROMISE;

        // A known intended sender context plus a foreign sender is direct
        // compromise evidence, even before enough samples exist for a traffic
        // score to cross its threshold.
        if (input.expectedContextKnown && input.senderKnown && !input.expectedSenderMatch)
            return Decision.COMPROMISE;

        // An alias with established legitimate history that is hit by a new
        // unknown sender is also strong leakage evidence.
        if (input.aliasHam > 0 && input.senderKnown && !input.expectedSenderMatch)
            return Decision.COMPROMISE;

        // We know the message is spam, but not yet why the alias received it.
        // Preserve the two truths instead of silently collapsing them.
        return Decision.REVIEW;
    }
}
