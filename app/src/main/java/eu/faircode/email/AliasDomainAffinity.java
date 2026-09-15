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

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;

/**
 * Evidence model connecting an observed envelope alias with sender domains.
 *
 * Observed domains are evidence, not trust. A spammer reaching a leaked alias
 * must never be able to whitelist itself merely by sending repeatedly.
 */
public final class AliasDomainAffinity {
    private AliasDomainAffinity() {
    }

    public static final class Evidence {
        public final String alias;
        public final String serviceToken;
        public final String senderDomain;
        public final double aliasDomainSimilarity;
        public final boolean unsubscribe;

        Evidence(String alias, String serviceToken, String senderDomain,
                 double aliasDomainSimilarity, boolean unsubscribe) {
            this.alias = alias;
            this.serviceToken = serviceToken;
            this.senderDomain = senderDomain;
            this.aliasDomainSimilarity = aliasDomainSimilarity;
            this.unsubscribe = unsubscribe;
        }
    }

    public static Evidence fromMessage(Context context, EntityMessage message) {
        String alias = AliasRegistry.normalizeAddress(message == null ? null : message.deliveredto);
        String sender = firstSenderRootDomain(context, message);
        String service = serviceToken(alias);
        return new Evidence(
                alias,
                service,
                sender,
                similarity(service, sender),
                message != null && !TextUtils.isEmpty(message.unsubscribe));
    }

    /** Return all distinct root domains present in sender-oriented headers. */
    public static List<String> senderRootDomains(Context context, EntityMessage message) {
        if (context == null || message == null)
            return Collections.emptyList();

        Set<String> result = new HashSet<>();
        addAddressDomains(context, result, message.from);
        addAddressDomains(context, result, message.reply);
        addAddressDomains(context, result, message.return_path);

        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        return Collections.unmodifiableList(sorted);
    }

    public static String firstSenderRootDomain(Context context, EntityMessage message) {
        List<String> domains = senderRootDomains(context, message);
        return domains.isEmpty() ? null : domains.get(0);
    }

    private static void addAddressDomains(Context context, Set<String> result, Address[] addresses) {
        if (addresses == null)
            return;
        for (Address address : addresses)
            if (address instanceof InternetAddress) {
                String domain = UriHelper.getEmailDomain(((InternetAddress) address).getAddress());
                domain = rootDomain(context, domain);
                if (domain != null)
                    result.add(domain);
            }
    }

    static String rootDomain(Context context, String value) {
        if (context == null || value == null)
            return null;
        String domain = value.trim().toLowerCase(Locale.ROOT);
        if (domain.isEmpty())
            return null;
        if (domain.startsWith("@"))
            domain = domain.substring(1);
        String root = UriHelper.getRootDomain(context, domain);
        if (root == null)
            root = domain;
        root = root.trim().toLowerCase(Locale.ROOT);
        return root.isEmpty() ? null : root;
    }

    /**
     * Infer a human/service token from conventions such as sd_ubuy@example.org.
     * This is deliberately weaker than an explicitly configured service domain.
     */
    static String serviceToken(String aliasAddress) {
        String address = AliasRegistry.normalizeAddress(aliasAddress);
        if (address == null)
            return null;
        int at = address.lastIndexOf('@');
        if (at <= 0)
            return null;
        String local = address.substring(0, at);
        if (local.toLowerCase(Locale.ROOT).startsWith("sd_") && local.length() > 3)
            local = local.substring(3);
        return normalizeComparable(local);
    }

    /**
     * Similarity between the alias service token and the sender root domain.
     * Exact domain-label matches score strongest; fuzzy comparison is bounded so
     * it remains evidence rather than a hard allow decision.
     */
    static double similarity(String serviceToken, String senderRootDomain) {
        String service = normalizeComparable(serviceToken);
        if (service == null || senderRootDomain == null)
            return 0;

        String domain = senderRootDomain.toLowerCase(Locale.ROOT);
        String[] labels = domain.split("\\.");
        double best = 0;
        for (String label : labels) {
            String candidate = normalizeComparable(label);
            if (candidate == null)
                continue;
            if (service.equals(candidate))
                return 1.0;
            if (service.length() >= 4 && candidate.length() >= 4 &&
                    (service.contains(candidate) || candidate.contains(service)))
                best = Math.max(best, 0.90);
            best = Math.max(best, 0.80 * diceBigrams(service, candidate));
        }
        return Math.min(0.90, best);
    }

    /**
     * Safe automatic fill for service_domain. Only an exact normalized label
     * match is promoted. Fuzzy matches remain suggestions for the UI.
     */
    static String inferServiceDomain(String aliasAddress, String senderRootDomain) {
        String token = serviceToken(aliasAddress);
        if (token == null || senderRootDomain == null)
            return null;
        for (String label : senderRootDomain.toLowerCase(Locale.ROOT).split("\\."))
            if (token.equals(normalizeComparable(label)))
                return senderRootDomain.toLowerCase(Locale.ROOT);
        return null;
    }

    static String incrementDomainCounter(String json, String domain, int delta) throws JSONException {
        if (domain == null || delta == 0)
            return json == null ? "{}" : json;

        JSONObject object;
        try {
            object = TextUtils.isEmpty(json) ? new JSONObject() : new JSONObject(json);
        } catch (JSONException ex) {
            object = new JSONObject();
        }

        int next = Math.max(0, object.optInt(domain, 0) + delta);
        if (next == 0)
            object.remove(domain);
        else
            object.put(domain, next);
        return object.toString();
    }

    private static String normalizeComparable(String value) {
        if (value == null)
            return null;
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.isEmpty() ? null : normalized;
    }

    private static double diceBigrams(String a, String b) {
        if (a.equals(b))
            return 1;
        if (a.length() < 2 || b.length() < 2)
            return 0;

        List<String> left = bigrams(a);
        List<String> right = bigrams(b);
        boolean[] used = new boolean[right.size()];
        int intersection = 0;
        for (String l : left)
            for (int i = 0; i < right.size(); i++)
                if (!used[i] && l.equals(right.get(i))) {
                    used[i] = true;
                    intersection++;
                    break;
                }
        return 2.0 * intersection / (left.size() + right.size());
    }

    private static List<String> bigrams(String value) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i + 1 < value.length(); i++)
            result.add(value.substring(i, i + 2));
        return result;
    }
}
