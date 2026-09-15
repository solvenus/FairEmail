/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package eu.faircode.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java evidence fusion for alias/domain legitimacy and spam suspicion.
 *
 * This class intentionally does not make the final spam-family decision. It
 * produces two independent supports so contradictory evidence remains visible
 * instead of being hidden inside one opaque number.
 */
public final class AliasTrafficScorer {
    private AliasTrafficScorer() {
    }

    public enum Verdict {
        LIKELY_LEGIT,
        UNKNOWN,
        SUSPICIOUS
    }

    public static Assessment assess(Input in) {
        if (in == null)
            throw new IllegalArgumentException("input");

        List<String> reasons = new ArrayList<>();
        double ham = 0;
        double spam = 0;

        if (in.serviceDomainKnown) {
            if (in.serviceDomainMatch) {
                ham = noisyOr(ham, 0.72);
                reasons.add("service-domain-match");
            } else if (in.senderDomainKnown) {
                spam = noisyOr(spam, 0.68);
                reasons.add("service-domain-mismatch");
            }
        }

        if (in.trustedDomainsConfigured) {
            if (in.trustedDomainMatch) {
                ham = noisyOr(ham, 0.80);
                reasons.add("trusted-domain-match");
            } else if (in.senderDomainKnown) {
                spam = noisyOr(spam, 0.74);
                reasons.add("trusted-domain-mismatch");
            }
        }

        double similarity = clamp01(in.aliasDomainSimilarity);
        if (similarity > 0) {
            ham = noisyOr(ham, 0.46 * similarity);
            if (similarity >= 0.85)
                reasons.add("alias-domain-high-overlap");
            else if (similarity >= 0.50)
                reasons.add("alias-domain-overlap");
        }

        double senderHam = clamp01(in.senderHamConfidence);
        if (senderHam > 0) {
            ham = noisyOr(ham, 0.58 * senderHam);
            if (senderHam >= 0.60)
                reasons.add("known-legitimate-sender-domain");
        }

        double unexpected = clamp01(in.unexpectedSender);
        if (unexpected > 0) {
            spam = noisyOr(spam, 0.58 * unexpected);
            if (unexpected >= 0.60)
                reasons.add("unexpected-sender-domain");
        }

        // A parsed List-Unsubscribe is useful newsletter evidence, but is never
        // an allow-list by itself because hostile mail can forge this header.
        if (in.hasUnsubscribe) {
            double strength = (in.serviceDomainMatch || in.trustedDomainMatch || similarity >= 0.75)
                    ? 0.38 : 0.20;
            ham = noisyOr(ham, strength);
            reasons.add("unsubscribe-present");
        }

        // No textual/domain overlap is weak evidence by itself. It becomes
        // meaningful only after the alias has an explicit expected domain.
        if (in.senderDomainKnown &&
                (in.serviceDomainKnown || in.trustedDomainsConfigured) &&
                !in.serviceDomainMatch && !in.trustedDomainMatch && similarity < 0.20) {
            spam = noisyOr(spam, 0.24);
            reasons.add("no-alias-domain-overlap");
        }

        double net = spam - ham;
        Verdict verdict = Verdict.UNKNOWN;
        if (spam >= 0.64 && net >= 0.22)
            verdict = Verdict.SUSPICIOUS;
        else if (ham >= 0.64 && net <= -0.22)
            verdict = Verdict.LIKELY_LEGIT;

        return new Assessment(spam, ham, net, verdict, reasons);
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
        public boolean senderDomainKnown;
        public boolean serviceDomainKnown;
        public boolean serviceDomainMatch;
        public boolean trustedDomainsConfigured;
        public boolean trustedDomainMatch;
        public double aliasDomainSimilarity;
        public double senderHamConfidence;
        public double unexpectedSender;
        public boolean hasUnsubscribe;
    }

    public static final class Assessment {
        public final double spamSupport;
        public final double hamSupport;
        /** Positive means spam-leaning, negative means legitimate-leaning. */
        public final double net;
        public final Verdict verdict;
        public final List<String> reasons;

        Assessment(double spamSupport, double hamSupport, double net,
                   Verdict verdict, List<String> reasons) {
            this.spamSupport = spamSupport;
            this.hamSupport = hamSupport;
            this.net = net;
            this.verdict = verdict;
            this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "verdict=%s spam=%.3f ham=%.3f net=%+.3f reasons=%s",
                    verdict, spamSupport, hamSupport, net, reasons);
        }
    }
}
