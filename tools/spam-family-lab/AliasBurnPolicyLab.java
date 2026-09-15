package eu.faircode.email;

/** Zero-dependency regression checks for AliasBurnPolicy. */
public final class AliasBurnPolicyLab {
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    public static void main(String[] args) {
        AliasBurnPolicy.Input healthy = new AliasBurnPolicy.Input();
        AliasBurnPolicy.Result a = AliasBurnPolicy.evaluate(healthy);
        require(a.verdict == AliasBurnPolicy.Verdict.HEALTHY,
                "alias without confirmed spam must stay healthy");
        require(!a.burnAllowed, "healthy alias must never be burn-ready");

        AliasBurnPolicy.Input leakedService = new AliasBurnPolicy.Input();
        leakedService.spamHits = 1;
        leakedService.hamHits = 12;
        leakedService.serviceDomainKnown = true;
        leakedService.trustedDomainsConfigured = true;
        AliasBurnPolicy.Result b = AliasBurnPolicy.evaluate(leakedService);
        require(b.verdict == AliasBurnPolicy.Verdict.ROTATE_FIRST,
                "used service alias must be rotated before burn");
        require(!b.burnAllowed, "service alias must not auto-burn before replacement");

        AliasBurnPolicy.Input replaced = new AliasBurnPolicy.Input();
        replaced.spamHits = 3;
        replaced.hamHits = 20;
        replaced.serviceDomainKnown = true;
        replaced.replacementConfigured = true;
        AliasBurnPolicy.Result c = AliasBurnPolicy.evaluate(replaced);
        require(c.verdict == AliasBurnPolicy.Verdict.READY_TO_BURN,
                "replaced compromised alias should be burn-ready");
        require(c.burnAllowed, "replacement is the V1 burn gate");

        AliasBurnPolicy.Input unknownLeak = new AliasBurnPolicy.Input();
        unknownLeak.spamHits = 8;
        AliasBurnPolicy.Result d = AliasBurnPolicy.evaluate(unknownLeak);
        require(d.verdict == AliasBurnPolicy.Verdict.COMPROMISED,
                "volume alone must not make destructive server action automatic");
        require(!d.burnAllowed,
                "spam volume without replacement must remain non-destructive");

        AliasBurnPolicy.Input pending = new AliasBurnPolicy.Input();
        pending.spamHits = 2;
        pending.replacementConfigured = true;
        pending.serverState = AliasBurnPolicy.ServerState.REJECT_PENDING;
        AliasBurnPolicy.Result e = AliasBurnPolicy.evaluate(pending);
        require(e.verdict == AliasBurnPolicy.Verdict.SERVER_PENDING,
                "pending remote operation must dominate local readiness");
        require(!e.burnAllowed, "never launch a second burn while one is pending");

        AliasBurnPolicy.Input dead = new AliasBurnPolicy.Input();
        dead.spamHits = 2;
        dead.serverState = AliasBurnPolicy.ServerState.REJECT_VERIFIED;
        AliasBurnPolicy.Result f = AliasBurnPolicy.evaluate(dead);
        require(f.verdict == AliasBurnPolicy.Verdict.SMTP_DEAD,
                "verified rejection must be represented as physically dead");
        require(!f.burnAllowed, "already dead alias is not burn-ready again");

        System.out.println("PASS burn-policy " +
                a.verdict + "," + b.verdict + "," + c.verdict + "," +
                d.verdict + "," + e.verdict + "," + f.verdict);
    }
}
