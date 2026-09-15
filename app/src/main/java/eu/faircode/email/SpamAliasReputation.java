/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package eu.faircode.email;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Learns spam/ham reputation for the real SMTP envelope recipient.
 *
 * FairEmail already stores Envelope-To/X-Envelope-To/X-Original-To as
 * EntityMessage.deliveredto. For catch-all mailboxes this is the address that
 * identifies the service alias, not the catch-all mailbox itself.
 */
public final class SpamAliasReputation {
    private SpamAliasReputation() {
    }

    public static final class Model {
        private final Map<String, Stats> aliases = new HashMap<>();
        private final double priorSpam;
        private final double priorStrength;
        private final double confidenceSamples;

        public Model() {
            this(0.10, 6.0, 12.0);
        }

        public Model(double priorSpam, double priorStrength, double confidenceSamples) {
            if (priorSpam < 0 || priorSpam > 1) throw new IllegalArgumentException("priorSpam");
            if (priorStrength <= 0) throw new IllegalArgumentException("priorStrength");
            if (confidenceSamples <= 0) throw new IllegalArgumentException("confidenceSamples");
            this.priorSpam = priorSpam;
            this.priorStrength = priorStrength;
            this.confidenceSamples = confidenceSamples;
        }

        public void observe(String deliveredTo, boolean spam) {
            String alias = normalize(deliveredTo);
            if (alias == null) return;
            Stats stats = aliases.get(alias);
            if (stats == null) {
                stats = new Stats();
                aliases.put(alias, stats);
            }
            if (spam) stats.spam++;
            else stats.ham++;
        }

        public Reputation get(String deliveredTo) {
            String alias = normalize(deliveredTo);
            if (alias == null) return Reputation.EMPTY;
            Stats stats = aliases.get(alias);
            if (stats == null) return new Reputation(alias, 0, 0, priorSpam, 0, priorSpam);

            int samples = stats.spam + stats.ham;
            double posterior = (stats.spam + priorSpam * priorStrength) /
                    (samples + priorStrength);
            double confidence = samples / (samples + confidenceSamples);
            double effective = priorSpam + confidence * (posterior - priorSpam);
            return new Reputation(alias, stats.spam, stats.ham, posterior, confidence, effective);
        }

        public Map<String, Reputation> snapshot() {
            Map<String, Reputation> result = new HashMap<>();
            for (String alias : aliases.keySet()) result.put(alias, get(alias));
            return Collections.unmodifiableMap(result);
        }
    }

    public static final class Reputation {
        static final Reputation EMPTY = new Reputation(null, 0, 0, 0, 0, 0);

        public final String alias;
        public final int spam;
        public final int ham;
        /** Smoothed P(spam | alias). */
        public final double posterior;
        /** 0..1 evidence confidence based on sample count. */
        public final double confidence;
        /** Posterior shrunk toward the configured global prior at low sample counts. */
        public final double effectiveRisk;

        private Reputation(String alias, int spam, int ham,
                           double posterior, double confidence, double effectiveRisk) {
            this.alias = alias;
            this.spam = spam;
            this.ham = ham;
            this.posterior = posterior;
            this.confidence = confidence;
            this.effectiveRisk = effectiveRisk;
        }
    }

    static String normalize(String deliveredTo) {
        if (deliveredTo == null) return null;
        String value = deliveredTo.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("<") && value.endsWith(">") && value.length() > 2)
            value = value.substring(1, value.length() - 1).trim();
        int comma = value.indexOf(',');
        if (comma >= 0) value = value.substring(0, comma).trim();
        return value.contains("@") ? value : null;
    }

    private static final class Stats {
        int spam;
        int ham;
    }
}
