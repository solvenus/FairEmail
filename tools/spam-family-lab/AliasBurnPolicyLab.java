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

        AliasBurnPolicy.Input spamButNotCompromised = new AliasBurnPolicy.Input();
        spamButNotCompromised.spamHits = 8;
        AliasBurnPolicy.Result review = AliasBurnPolicy.evaluate(spamButNotCompromised);
        require(review.verdict == AliasBurnPolicy.Verdict.REVIEW_COMPROMISE,
                "spam truth alone must not become alias-compromise truth");
        require(!review.compromised && !review.burnAllowed,
                "unconfirmed compromise must not authorize burn");

        AliasBurnPolicy.Input leakedService = new AliasBurnPolicy.Input();
        leakedService.aliasCompromised = true;
        leakedService.spamHits = 1;
        leakedService.hamHits = 12;
        leakedService.serviceDomainKnown = true;
        leakedService.trustedDomainsConfigured = true;
        AliasBurnPolicy.Result b = AliasBurnPolicy.evaluate(leakedService);
        require(b.verdict == AliasBurnPolicy.Verdict.ROTATE_FIRST,
                "used compromised service alias must be rotated before burn");
        require(!b.burnAllowed, "service alias must not burn before replacement");

        AliasBurnPolicy.Input replacementUnverified = new AliasBurnPolicy.Input();
        replacementUnverified.aliasCompromised = true;
        replacementUnverified.spamHits = 3;
        replacementUnverified.hamHits = 20;
        replacementUnverified.serviceDomainKnown = true;
        replacementUnverified.replacementConfigured = true;
        AliasBurnPolicy.Result verify = AliasBurnPolicy.evaluate(replacementUnverified);
        require(verify.verdict == AliasBurnPolicy.Verdict.VERIFY_REPLACEMENT,
                "configured replacement must be observed before burn");
        require(!verify.burnAllowed,
                "unverified replacement must never authorize burn");

        AliasBurnPolicy.Input replaced = new AliasBurnPolicy.Input();
        replaced.aliasCompromised = true;
        replaced.spamHits = 3;
        replaced.hamHits = 20;
        replaced.serviceDomainKnown = true;
        replaced.replacementConfigured = true;
        replaced.replacementVerified = true;
        AliasBurnPolicy.Result c = AliasBurnPolicy.evaluate(replaced);
        require(c.verdict == AliasBurnPolicy.Verdict.READY_TO_BURN,
                "verified replacement should make compromised alias burn-ready");
        require(c.burnAllowed,
                "explicit compromise plus verified replacement is the burn gate");

        AliasBurnPolicy.Input unknownLeak = new AliasBurnPolicy.Input();
        unknownLeak.aliasCompromised = true;
        unknownLeak.spamHits = 8;
        AliasBurnPolicy.Result d = AliasBurnPolicy.evaluate(unknownLeak);
        require(d.verdict == AliasBurnPolicy.Verdict.COMPROMISED,
                "confirmed compromise without replacement remains non-destructive");
        require(!d.burnAllowed,
                "compromise without replacement must remain non-destructive");

        AliasBurnPolicy.Input pending = new AliasBurnPolicy.Input();
        pending.aliasCompromised = true;
        pending.spamHits = 2;
        pending.replacementConfigured = true;
        pending.replacementVerified = true;
        pending.serverState = AliasBurnPolicy.ServerState.REJECT_PENDING;
        AliasBurnPolicy.Result e = AliasBurnPolicy.evaluate(pending);
        require(e.verdict == AliasBurnPolicy.Verdict.SERVER_PENDING,
                "pending remote operation must dominate local readiness");
        require(!e.burnAllowed, "never launch a second burn while one is pending");

        AliasBurnPolicy.Input dead = new AliasBurnPolicy.Input();
        dead.aliasCompromised = true;
        dead.spamHits = 2;
        dead.serverState = AliasBurnPolicy.ServerState.REJECT_VERIFIED;
        AliasBurnPolicy.Result f = AliasBurnPolicy.evaluate(dead);
        require(f.verdict == AliasBurnPolicy.Verdict.SMTP_DEAD,
                "verified rejection must be represented as physically dead");
        require(!f.burnAllowed, "already dead alias is not burn-ready again");

        System.out.println("PASS burn-policy " +
                a.verdict + "," + review.verdict + "," + b.verdict + "," +
                verify.verdict + "," + c.verdict + "," + d.verdict + "," +
                e.verdict + "," + f.verdict);
    }
}
