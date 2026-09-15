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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Learns spam/ham reputation for the real SMTP envelope recipient.
 *
 * FairEmail already stores Envelope-To/X-Envelope-To/X-Original-To as
 * EntityMessage.deliveredto. For catch-all mailboxes this identifies the
 * service alias instead of the catch-all mailbox itself.
 *
 * Alias reputation and sender affinity are intentionally independent from
 * SpamFamilyEngine. A spam family may hit several aliases, while an alias may
 * receive several unrelated spam families.
 */
public final class SpamAliasReputation {
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)([a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,})");

    private SpamAliasReputation() {
    }

    public static final class Model {
        private final Map<String, Stats> aliases = new HashMap<>();
        private final double priorSpam;
        private final double priorStrength;
        private final double confidenceSamples;
        private final double senderConfidenceSamples;

        public Model() {
            this(0.10, 6.0, 12.0, 3.0);
        }

        public Model(double priorSpam, double priorStrength,
                     double confidenceSamples, double senderConfidenceSamples) {
            if (priorSpam < 0 || priorSpam > 1) throw new IllegalArgumentException("priorSpam");
            if (priorStrength <= 0) throw new IllegalArgumentException("priorStrength");
            if (confidenceSamples <= 0) throw new IllegalArgumentException("confidenceSamples");
            if (senderConfidenceSamples <= 0) throw new IllegalArgumentException("senderConfidenceSamples");
            this.priorSpam = priorSpam;
            this.priorStrength = priorStrength;
            this.confidenceSamples = confidenceSamples;
            this.senderConfidenceSamples = senderConfidenceSamples;
        }

        /** Backwards-compatible observation when sender infrastructure is unavailable. */
        public void observe(String deliveredTo, boolean spam) {
            observe(deliveredTo, null, spam);
        }

        /**
         * Observe a labelled message.
         *
         * senderDomain should preferably be a registrable/root domain. The
         * Android adapter can obtain that with UriHelper.getRootDomain().
         */
        public void observe(String deliveredTo, String senderDomain, boolean spam) {
            String alias = normalizeAddress(deliveredTo);
            if (alias == null) return;

            Stats stats = aliases.get(alias);
            if (stats == null) {
                stats = new Stats();
                aliases.put(alias, stats);
            }

            if (spam) stats.spam++;
            else stats.ham++;

            String domain = normalizeDomain(senderDomain);
            if (domain != null) {
                DomainStats ds = stats.domains.get(domain);
                if (ds == null) {
                    ds = new DomainStats();
                    stats.domains.put(domain, ds);
                }
                if (spam) ds.spam++;
                else ds.ham++;
            }
        }

        public Reputation get(String deliveredTo) {
            return get(deliveredTo, null);
        }

        /**
         * Return alias reputation plus how familiar the supplied sender domain
         * is as legitimate traffic for this specific alias.
         */
        public Reputation get(String deliveredTo, String senderDomain) {
            String alias = normalizeAddress(deliveredTo);
            if (alias == null) return Reputation.EMPTY;

            String domain = normalizeDomain(senderDomain);
            Stats stats = aliases.get(alias);
            if (stats == null)
                return new Reputation(alias, domain, 0, 0,
                        priorSpam, 0, priorSpam,
                        0, 0, 0, 0, 0);

            int samples = stats.spam + stats.ham;
            double posterior = (stats.spam + priorSpam * priorStrength) /
                    (samples + priorStrength);
            double confidence = samples / (samples + confidenceSamples);
            double effective = priorSpam + confidence * (posterior - priorSpam);

            int senderSpam = 0;
            int senderHam = 0;
            double senderHamConfidence = 0;
            double senderHamShare = 0;
            if (domain != null) {
                DomainStats ds = stats.domains.get(domain);
                if (ds != null) {
                    senderSpam = ds.spam;
                    senderHam = ds.ham;
                    senderHamConfidence = senderHam /
                            (senderHam + senderConfidenceSamples);
                    if (stats.ham > 0)
                        senderHamShare = (double) senderHam / stats.ham;
                }
            }

            // Be suspicious of a new sender only when this alias already has
            // enough legitimate history to know what "normal" looks like.
            double aliasHamConfidence = stats.ham / (stats.ham + confidenceSamples);
            double unexpectedSender = domain == null ? 0 :
                    aliasHamConfidence * (1.0 - senderHamConfidence);

            return new Reputation(alias, domain, stats.spam, stats.ham,
                    posterior, confidence, effective,
                    senderSpam, senderHam, senderHamConfidence,
                    senderHamShare, unexpectedSender);
        }

        public Map<String, Reputation> snapshot() {
            Map<String, Reputation> result = new HashMap<>();
            for (String alias : aliases.keySet())
                result.put(alias, get(alias));
            return Collections.unmodifiableMap(result);
        }
    }

    public static final class Reputation {
        static final Reputation EMPTY = new Reputation(null, null, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0);

        public final String alias;
        public final String senderDomain;
        public final int spam;
        public final int ham;
        /** Smoothed P(spam | alias). */
        public final double posterior;
        /** 0..1 evidence confidence based on all observations of the alias. */
        public final double confidence;
        /** Posterior shrunk toward the configured global prior at low sample counts. */
        public final double effectiveRisk;
        public final int senderSpam;
        public final int senderHam;
        /** 0..1 confidence that this sender domain is known legitimate traffic for the alias. */
        public final double senderHamConfidence;
        /** Fraction of the alias's legitimate traffic that came from this sender domain. */
        public final double senderHamShare;
        /** 0..1 anomaly signal: established alias, but unfamiliar sender domain. */
        public final double unexpectedSender;

        private Reputation(String alias, String senderDomain,
                           int spam, int ham,
                           double posterior, double confidence, double effectiveRisk,
                           int senderSpam, int senderHam,
                           double senderHamConfidence, double senderHamShare,
                           double unexpectedSender) {
            this.alias = alias;
            this.senderDomain = senderDomain;
            this.spam = spam;
            this.ham = ham;
            this.posterior = posterior;
            this.confidence = confidence;
            this.effectiveRisk = effectiveRisk;
            this.senderSpam = senderSpam;
            this.senderHam = senderHam;
            this.senderHamConfidence = senderHamConfidence;
            this.senderHamShare = senderHamShare;
            this.unexpectedSender = unexpectedSender;
        }
    }

    /**
     * Canonical mailbox identity shared conceptually with AliasRegistry:
     * preserve the RFC local-part and normalize only the DNS domain.
     */
    static String normalizeAddress(String deliveredTo) {
        if (deliveredTo == null)
            return null;
        String raw = deliveredTo.trim();
        if (raw.isEmpty())
            return null;

        Matcher matcher = EMAIL.matcher(raw);
        if (!matcher.find())
            return null;

        String address = matcher.group(1);
        int at = address.lastIndexOf('@');
        if (at <= 0 || at + 1 >= address.length())
            return null;
        return address.substring(0, at) + "@" +
                address.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    static String normalizeDomain(String value) {
        if (value == null) return null;
        String domain = value.trim().toLowerCase(Locale.ROOT);
        if (domain.startsWith("<") && domain.endsWith(">") && domain.length() > 2)
            domain = domain.substring(1, domain.length() - 1).trim();
        int at = domain.lastIndexOf('@');
        if (at >= 0 && at + 1 < domain.length())
            domain = domain.substring(at + 1);
        while (domain.endsWith("."))
            domain = domain.substring(0, domain.length() - 1);
        return domain.isEmpty() || domain.indexOf(' ') >= 0 ? null : domain;
    }

    private static final class Stats {
        int spam;
        int ham;
        final Map<String, DomainStats> domains = new HashMap<>();
    }

    private static final class DomainStats {
        int spam;
        int ham;
    }
}
