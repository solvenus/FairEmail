package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Converts persistent Alias Registry state into explainable traffic evidence. */
public final class AliasTrafficAnalyzer {
    private static final double ALIAS_CONFIDENCE_SAMPLES = 12.0;
    private static final double DOMAIN_CONFIDENCE_SAMPLES = 3.0;

    private AliasTrafficAnalyzer() {
    }

    public static Result assess(Context context,
                                EntityAccount account,
                                EntityMessage message) {
        if (context == null || account == null || account.uuid == null || message == null)
            return Result.EMPTY;

        try {
            AliasDomainAffinity.Evidence evidence = AliasDomainAffinity.fromMessage(context, message);
            if (evidence.alias == null)
                return Result.EMPTY;

            DaoAlias dao = SpamIntelligenceDB.getInstance(context).alias();
            EntityAlias alias = dao.getAlias(account.uuid, evidence.alias);
            if (alias == null)
                return Result.EMPTY;

            String sender = evidence.senderDomain;
            String serviceDomain = AliasDomainAffinity.rootDomain(context, alias.service_domain);
            List<String> trusted = parseTrustedDomains(context, alias.trusted_domains);

            int spam = dao.countAliasLabel(account.uuid, evidence.alias,
                    EntityAliasDelivery.LABEL_SPAM);
            int ham = dao.countAliasLabel(account.uuid, evidence.alias,
                    EntityAliasDelivery.LABEL_HAM);
            int senderSpam = sender == null ? 0 : dao.countSenderDomainLabel(
                    account.uuid, evidence.alias, sender, EntityAliasDelivery.LABEL_SPAM);
            int senderHam = sender == null ? 0 : dao.countSenderDomainLabel(
                    account.uuid, evidence.alias, sender, EntityAliasDelivery.LABEL_HAM);

            int labelled = spam + ham;
            double aliasSpamRisk = (spam + 0.10 * 6.0) / (labelled + 6.0);
            double labelConfidence = labelled / (labelled + ALIAS_CONFIDENCE_SAMPLES);
            aliasSpamRisk = 0.10 + labelConfidence * (aliasSpamRisk - 0.10);

            double senderSpamConfidence = senderSpam /
                    (senderSpam + DOMAIN_CONFIDENCE_SAMPLES);
            double senderHamConfidence = senderHam /
                    (senderHam + DOMAIN_CONFIDENCE_SAMPLES);
            double aliasHamConfidence = ham /
                    (ham + ALIAS_CONFIDENCE_SAMPLES);
            double unexpectedSender = SpamControlPolicy.foreignSenderEvidence(context) && sender != null
                    ? aliasHamConfidence * (1.0 - senderHamConfidence)
                    : 0.0;

            AliasTrafficScorer.Input input = new AliasTrafficScorer.Input();
            input.senderDomainKnown = sender != null;
            input.serviceDomainKnown = serviceDomain != null;
            input.serviceDomainMatch = sender != null && serviceDomain != null &&
                    serviceDomain.equalsIgnoreCase(sender);
            input.trustedDomainsConfigured = !trusted.isEmpty();
            input.trustedDomainMatch = sender != null && containsIgnoreCase(trusted, sender);
            input.aliasDomainSimilarity = evidence.aliasDomainSimilarity;
            input.aliasSpamRisk = aliasSpamRisk;
            input.senderSpamConfidence = senderSpamConfidence;
            input.senderHamConfidence = senderHamConfidence;
            input.unexpectedSender = unexpectedSender;
            input.hasUnsubscribe = evidence.unsubscribe;

            return new Result(
                    evidence.alias,
                    sender,
                    serviceDomain,
                    trusted,
                    spam,
                    ham,
                    senderSpam,
                    senderHam,
                    AliasTrafficScorer.assess(input));
        } catch (Throwable ex) {
            Log.e(ex);
            return Result.EMPTY;
        }
    }

    private static List<String> parseTrustedDomains(Context context, String json) {
        if (TextUtils.isEmpty(json))
            return Collections.emptyList();
        try {
            JSONArray array = new JSONArray(json);
            List<String> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                String domain = AliasDomainAffinity.rootDomain(context, array.optString(i, null));
                if (domain != null && !containsIgnoreCase(result, domain))
                    result.add(domain);
            }
            Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
            return Collections.unmodifiableList(result);
        } catch (Throwable ex) {
            Log.w(ex);
            return Collections.emptyList();
        }
    }

    private static boolean containsIgnoreCase(List<String> values, String needle) {
        if (needle == null)
            return false;
        for (String value : values)
            if (needle.equalsIgnoreCase(value))
                return true;
        return false;
    }

    public static final class Result {
        static final Result EMPTY = new Result(null, null, null,
                Collections.<String>emptyList(), 0, 0, 0, 0,
                new AliasTrafficScorer.Assessment(0, 0, 0,
                        AliasTrafficScorer.Verdict.UNKNOWN,
                        Collections.<String>emptyList()));

        public final String alias;
        public final String senderDomain;
        public final String serviceDomain;
        public final List<String> trustedDomains;
        public final int aliasSpam;
        public final int aliasHam;
        public final int senderSpam;
        public final int senderHam;
        public final AliasTrafficScorer.Assessment assessment;

        Result(String alias, String senderDomain, String serviceDomain,
               List<String> trustedDomains,
               int aliasSpam, int aliasHam, int senderSpam, int senderHam,
               AliasTrafficScorer.Assessment assessment) {
            this.alias = alias;
            this.senderDomain = senderDomain;
            this.serviceDomain = serviceDomain;
            this.trustedDomains = trustedDomains;
            this.aliasSpam = aliasSpam;
            this.aliasHam = aliasHam;
            this.senderSpam = senderSpam;
            this.senderHam = senderHam;
            this.assessment = assessment;
        }
    }
}
