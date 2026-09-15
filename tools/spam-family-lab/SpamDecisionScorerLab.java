package eu.faircode.email;

public final class SpamDecisionScorerLab {
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    private static SpamDecisionScorer.Result score(Double family,
                                                    double aliasSpam,
                                                    double aliasHam) {
        return SpamDecisionScorer.score(
                SpamDecisionScorer.ExplicitLabel.UNKNOWN,
                family, aliasSpam, aliasHam);
    }

    public static void main(String[] args) {
        SpamDecisionScorer.Result explicitSpam = SpamDecisionScorer.score(
                SpamDecisionScorer.ExplicitLabel.SPAM, 0.0, 0.0, 1.0);
        require(explicitSpam.verdict == SpamDecisionScorer.Verdict.CONFIRMED_SPAM,
                "explicit spam must override observer evidence");

        SpamDecisionScorer.Result explicitHam = SpamDecisionScorer.score(
                SpamDecisionScorer.ExplicitLabel.HAM, 1.0, 1.0, 0.0);
        require(explicitHam.verdict == SpamDecisionScorer.Verdict.CONFIRMED_LEGIT,
                "explicit legitimate label must override observer evidence");

        // Ubuy-style hard negative: correct service/alias relationship and
        // unsubscribe/legitimate history must beat a vague spam-family resemblance.
        SpamDecisionScorer.Result ubuy = score(0.58, 0.05, 0.88);
        require(ubuy.verdict == SpamDecisionScorer.Verdict.LIKELY_LEGIT,
                "strong legitimate alias evidence must protect an Ubuy-like newsletter");
        require(ubuy.net < -SpamDecisionScorer.MIN_MARGIN,
                "Ubuy hard negative should have a clear legitimate margin");

        // Established service alias hit by an unrelated sender/domain.
        SpamDecisionScorer.Result aliasMismatch = score(0.38, 0.82, 0.05);
        require(aliasMismatch.verdict == SpamDecisionScorer.Verdict.SUSPICIOUS,
                "strong alias mismatch must be first-class spam evidence");

        // Strong resemblance to confirmed spam still matters without alias history.
        SpamDecisionScorer.Result strongFamilyOnly = score(0.82, 0.0, 0.0);
        require(strongFamilyOnly.verdict == SpamDecisionScorer.Verdict.SUSPICIOUS,
                "strong family resemblance must work when alias evidence is absent");

        // Vague resemblance alone is not enough.
        SpamDecisionScorer.Result vagueFamilyOnly = score(0.48, 0.0, 0.0);
        require(vagueFamilyOnly.verdict == SpamDecisionScorer.Verdict.UNKNOWN,
                "moderate family resemblance alone must remain review-only");

        // Two moderate independent spam signals should reinforce one another.
        SpamDecisionScorer.Result combined = score(0.44, 0.40, 0.05);
        require(combined.verdict == SpamDecisionScorer.Verdict.SUSPICIOUS,
                "moderate alias and family evidence should reinforce each other");

        // Strong disagreement is deliberately review-only, not auto-spam.
        SpamDecisionScorer.Result conflict = score(0.85, 0.05, 0.80);
        require(conflict.verdict == SpamDecisionScorer.Verdict.UNKNOWN,
                "strong family-vs-legitimate-alias conflict must stay review-only");
        require(conflict.reasons.contains("conflicting_evidence"),
                "conflicting strong evidence should be explainable");

        // Invalid numeric evidence must fail closed instead of poisoning scoring.
        SpamDecisionScorer.Result corrupt = score(Double.NaN, Double.NaN, Double.POSITIVE_INFINITY);
        require(corrupt.verdict == SpamDecisionScorer.Verdict.UNKNOWN,
                "non-finite evidence must fail closed");

        System.out.println("PASS SpamDecisionScorerLab");
    }
}
