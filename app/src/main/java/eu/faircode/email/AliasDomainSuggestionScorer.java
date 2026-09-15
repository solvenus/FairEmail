package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure-Java ranking of sender domains that may legitimately belong to an alias. */
public final class AliasDomainSuggestionScorer {
    private AliasDomainSuggestionScorer() {
    }

    public static Suggestion score(Input in) {
        if (in == null)
            throw new IllegalArgumentException("input");

        List<String> reasons = new ArrayList<>();
        double support = 0;

        if (in.exactAliasDomainMatch) {
            support = noisyOr(support, 0.92);
            reasons.add("exact-alias-domain-match");
        } else {
            double similarity = clamp01(in.aliasDomainSimilarity);
            if (similarity >= 0.50) {
                support = noisyOr(support, 0.68 * similarity);
                reasons.add(similarity >= 0.85 ?
                        "strong-alias-domain-overlap" : "alias-domain-overlap");
            }
        }

        double hamConfidence = in.ham <= 0 ? 0 :
                (double) in.ham / (in.ham + 2.0);
        if (hamConfidence > 0) {
            support = noisyOr(support, 0.72 * hamConfidence);
            reasons.add("legitimate-history");
        }

        double unsubscribeConfidence = in.unsubscribe <= 0 ? 0 :
                (double) in.unsubscribe / (in.unsubscribe + 3.0);
        if (unsubscribeConfidence > 0) {
            support = noisyOr(support, 0.30 * unsubscribeConfidence);
            reasons.add("unsubscribe-history");
        }

        // Repetition is useful only as confidence in the other positive signals;
        // raw volume can never whitelist a sender by itself.
        double repetition = in.messages <= 0 ? 0 :
                (double) in.messages / (in.messages + 4.0);
        support *= 0.72 + 0.28 * repetition;

        double spamConfidence = in.spam <= 0 ? 0 :
                (double) in.spam / (in.spam + 2.0);
        if (spamConfidence > 0) {
            support *= 1.0 - 0.90 * spamConfidence;
            reasons.add("spam-history-penalty");
        }

        double confidence = clamp01(support);
        boolean recommended = confidence >= 0.62 &&
                !(in.spam >= 2 && in.spam > in.ham);
        return new Suggestion(confidence, recommended, reasons);
    }

    private static double noisyOr(double current, double signal) {
        current = clamp01(current);
        signal = clamp01(signal);
        return 1.0 - (1.0 - current) * (1.0 - signal);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value))
            return 0;
        return Math.max(0, Math.min(1, value));
    }

    public static final class Input {
        public int messages;
        public int unsubscribe;
        public int ham;
        public int spam;
        public double aliasDomainSimilarity;
        public boolean exactAliasDomainMatch;
    }

    public static final class Suggestion {
        public final double confidence;
        public final boolean recommended;
        public final List<String> reasons;

        Suggestion(double confidence, boolean recommended, List<String> reasons) {
            this.confidence = confidence;
            this.recommended = recommended;
            this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "recommended=%s confidence=%.3f reasons=%s",
                    recommended, confidence, reasons);
        }
    }
}
