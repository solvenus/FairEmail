package eu.faircode.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure observer-only decision layer combining two independent questions:
 * alias/domain evidence answers whether traffic is expected, while family
 * similarity answers whether the message resembles already confirmed spam.
 *
 * This class does not decide family identity and never moves mail.
 */
public final class SpamDecisionScorer {
    public static final double SPAM_THRESHOLD = 0.56;
    public static final double HAM_THRESHOLD = 0.64;
    public static final double MIN_MARGIN = 0.18;

    public enum ExplicitLabel {
        UNKNOWN,
        SPAM,
        HAM
    }

    public enum Verdict {
        CONFIRMED_SPAM,
        CONFIRMED_LEGIT,
        SUSPICIOUS,
        LIKELY_LEGIT,
        UNKNOWN
    }

    public static final class Result {
        public final double spamSupport;
        public final double hamSupport;
        public final double net;
        public final Verdict verdict;
        public final List<String> reasons;

        private Result(double spamSupport,
                       double hamSupport,
                       Verdict verdict,
                       List<String> reasons) {
            this.spamSupport = clamp01(spamSupport);
            this.hamSupport = clamp01(hamSupport);
            this.net = this.spamSupport - this.hamSupport;
            this.verdict = verdict;
            this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        }
    }

    private SpamDecisionScorer() {
    }

    public static Result score(ExplicitLabel explicitLabel,
                               Double familyScore,
                               double aliasSpamSupport,
                               double aliasHamSupport) {
        ExplicitLabel explicit = explicitLabel == null ? ExplicitLabel.UNKNOWN : explicitLabel;
        double aliasSpam = clamp01(aliasSpamSupport);
        double aliasHam = clamp01(aliasHamSupport);
        Double family = sanitizeNullable(familyScore);
        List<String> reasons = new ArrayList<>();

        if (explicit == ExplicitLabel.SPAM) {
            reasons.add("explicit_spam");
            return new Result(1.0, 0.0, Verdict.CONFIRMED_SPAM, reasons);
        }
        if (explicit == ExplicitLabel.HAM) {
            reasons.add("explicit_legitimate");
            return new Result(0.0, 1.0, Verdict.CONFIRMED_LEGIT, reasons);
        }

        double spam = aliasSpam;
        if (aliasSpam >= 0.35)
            reasons.add("alias_spam_evidence");
        if (aliasHam >= 0.35)
            reasons.add("alias_legitimate_evidence");

        if (family != null) {
            // Independent evidence combines as a noisy-OR. A strong signal is
            // not diluted merely because the other channel has no evidence.
            spam = noisyOr(spam, family);
            if (family >= 0.35)
                reasons.add("known_spam_similarity");
            if (aliasSpam >= 0.30 && family >= 0.35)
                reasons.add("independent_spam_signals_agree");
        }

        double net = spam - aliasHam;
        Verdict verdict;
        if (spam >= SPAM_THRESHOLD && net >= MIN_MARGIN)
            verdict = Verdict.SUSPICIOUS;
        else if (aliasHam >= HAM_THRESHOLD && net <= -MIN_MARGIN)
            verdict = Verdict.LIKELY_LEGIT;
        else
            verdict = Verdict.UNKNOWN;

        if (verdict == Verdict.UNKNOWN && spam >= 0.50 && aliasHam >= 0.50)
            reasons.add("conflicting_evidence");

        return new Result(spam, aliasHam, verdict, reasons);
    }

    private static double noisyOr(double a, double b) {
        a = clamp01(a);
        b = clamp01(b);
        return 1.0 - (1.0 - a) * (1.0 - b);
    }

    private static Double sanitizeNullable(Double value) {
        if (value == null || !Double.isFinite(value))
            return null;
        return clamp01(value);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value))
            return 0.0;
        if (value <= 0.0)
            return 0.0;
        if (value >= 1.0)
            return 1.0;
        return value;
    }
}
