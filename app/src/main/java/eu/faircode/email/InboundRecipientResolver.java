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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the SMTP recipient actually used for inbound delivery.
 *
 * This is deliberately separate from spam classification. The result can feed a general
 * alias registry even when a message is legitimate and even when raw headers are not retained.
 */
public final class InboundRecipientResolver {
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![a-z0-9.!#$%&'*+/=?^_`{|}~-])" +
                    "[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@" +
                    "[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?" +
                    "(?![a-z0-9.-])");
    private static final Pattern RECEIVED_BY = Pattern.compile(
            "(?i)(?:^|[\\s;(])by\\s+([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)");
    private static final Pattern RECEIVED_FOR = Pattern.compile(
            "(?i)(?:^|[\\s;(])for\\s+(?:<\\s*)?([^>;\\s]+@[^>;\\s]+)(?:\\s*>)?");

    private InboundRecipientResolver() {
    }

    public enum Source {
        ENVELOPE_TO,
        TRUSTED_RECEIVED_FOR,
        X_ORIGINAL_TO,
        DELIVERED_TO,
        VISIBLE_TO,
        NONE
    }

    public static Result resolve(String rawHeaders) {
        Map<String, List<String>> headers = parseHeaders(rawHeaders);

        LinkedHashSet<String> envelopeTo = addresses(headers.get("envelope-to"));
        LinkedHashSet<String> xOriginalTo = addresses(headers.get("x-original-to"));
        LinkedHashSet<String> deliveredTo = addresses(headers.get("delivered-to"));
        LinkedHashSet<String> visibleTo = addresses(headers.get("to"));

        List<ReceivedHop> hops = parseReceived(headers.get("received"));
        LinkedHashSet<String> localHosts = inferLocalDeliveryHosts(hops, deliveredTo);
        LinkedHashSet<String> trustedReceivedFor = new LinkedHashSet<>();
        if (!localHosts.isEmpty())
            for (ReceivedHop hop : hops)
                if (hop.byHost != null && localHosts.contains(hop.byHost))
                    trustedReceivedFor.addAll(hop.forRecipients);

        LinkedHashSet<String> trustedOriginal = new LinkedHashSet<>(trustedReceivedFor);
        trustedOriginal.removeAll(deliveredTo);

        Source source;
        LinkedHashSet<String> originals;
        if (!envelopeTo.isEmpty()) {
            source = Source.ENVELOPE_TO;
            originals = envelopeTo;
        } else if (!trustedOriginal.isEmpty()) {
            source = Source.TRUSTED_RECEIVED_FOR;
            originals = trustedOriginal;
        } else if (!xOriginalTo.isEmpty()) {
            source = Source.X_ORIGINAL_TO;
            originals = xOriginalTo;
        } else if (!deliveredTo.isEmpty()) {
            source = Source.DELIVERED_TO;
            originals = deliveredTo;
        } else if (!visibleTo.isEmpty()) {
            source = Source.VISIBLE_TO;
            originals = visibleTo;
        } else {
            source = Source.NONE;
            originals = new LinkedHashSet<>();
        }

        LinkedHashSet<String> allObserved = new LinkedHashSet<>();
        allObserved.addAll(envelopeTo);
        allObserved.addAll(trustedReceivedFor);
        allObserved.addAll(xOriginalTo);
        allObserved.addAll(deliveredTo);
        allObserved.addAll(visibleTo);

        boolean corroboratedByReceived = intersects(originals, trustedReceivedFor);
        boolean visibleToContainsOriginal = intersects(originals, visibleTo);

        return new Result(source, originals, deliveredTo, envelopeTo, trustedReceivedFor,
                xOriginalTo, visibleTo, localHosts, allObserved,
                corroboratedByReceived, visibleToContainsOriginal);
    }

    private static Map<String, List<String>> parseHeaders(String raw) {
        if (raw == null || raw.isEmpty())
            return Collections.emptyMap();

        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        Map<String, List<String>> result = new LinkedHashMap<>();

        String name = null;
        StringBuilder value = null;
        for (String line : lines) {
            if (line.isEmpty())
                break;

            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && name != null) {
                value.append(' ').append(line.trim());
                continue;
            }

            commit(result, name, value);
            int colon = line.indexOf(':');
            if (colon <= 0) {
                name = null;
                value = null;
                continue;
            }

            name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            value = new StringBuilder(line.substring(colon + 1).trim());
        }
        commit(result, name, value);
        return result;
    }

    private static void commit(Map<String, List<String>> out, String name, StringBuilder value) {
        if (name == null || value == null)
            return;
        out.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value.toString());
    }

    private static LinkedHashSet<String> addresses(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values == null)
            return result;
        for (String value : values) {
            Matcher matcher = EMAIL.matcher(value);
            while (matcher.find())
                result.add(canonicalAddress(matcher.group()));
        }
        return result;
    }

    private static List<ReceivedHop> parseReceived(List<String> values) {
        if (values == null || values.isEmpty())
            return Collections.emptyList();
        List<ReceivedHop> result = new ArrayList<>();
        for (String value : values) {
            Matcher by = RECEIVED_BY.matcher(value);
            String byHost = (by.find() ? canonicalHost(by.group(1)) : null);

            LinkedHashSet<String> recipients = new LinkedHashSet<>();
            Matcher forMatcher = RECEIVED_FOR.matcher(value);
            while (forMatcher.find()) {
                Matcher address = EMAIL.matcher(forMatcher.group(1));
                if (address.find())
                    recipients.add(canonicalAddress(address.group()));
            }
            result.add(new ReceivedHop(byHost, recipients));
        }
        return result;
    }

    /**
     * Received headers are prepended. The first hop that both names a receiving host and confirms
     * Delivered-To anchors the local MTA. Only matching "by" hosts may corroborate an alias.
     */
    private static LinkedHashSet<String> inferLocalDeliveryHosts(List<ReceivedHop> hops,
                                                                 Set<String> deliveredTo) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (deliveredTo.isEmpty())
            return result;
        for (ReceivedHop hop : hops) {
            if (hop.byHost != null && intersects(hop.forRecipients, deliveredTo)) {
                result.add(hop.byHost);
                break;
            }
        }
        return result;
    }

    private static String canonicalAddress(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String canonicalHost(String value) {
        String host = value.trim().toLowerCase(Locale.ROOT);
        while (host.endsWith("."))
            host = host.substring(0, host.length() - 1);
        return host;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty())
            return false;
        Set<String> small = (a.size() <= b.size() ? a : b);
        Set<String> large = (a.size() <= b.size() ? b : a);
        for (String value : small)
            if (large.contains(value))
                return true;
        return false;
    }

    private static Set<String> immutable(Set<String> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    private static final class ReceivedHop {
        final String byHost;
        final Set<String> forRecipients;

        ReceivedHop(String byHost, Set<String> forRecipients) {
            this.byHost = byHost;
            this.forRecipients = forRecipients;
        }
    }

    public static final class Result {
        public final Source source;
        public final Set<String> originalRecipients;
        public final Set<String> deliveryMailboxes;
        public final Set<String> envelopeTo;
        public final Set<String> trustedReceivedFor;
        public final Set<String> xOriginalTo;
        public final Set<String> visibleTo;
        public final Set<String> localDeliveryHosts;
        public final Set<String> allObservedRecipients;
        public final boolean corroboratedByReceived;
        public final boolean visibleToContainsOriginal;

        private Result(Source source,
                       Set<String> originalRecipients,
                       Set<String> deliveryMailboxes,
                       Set<String> envelopeTo,
                       Set<String> trustedReceivedFor,
                       Set<String> xOriginalTo,
                       Set<String> visibleTo,
                       Set<String> localDeliveryHosts,
                       Set<String> allObservedRecipients,
                       boolean corroboratedByReceived,
                       boolean visibleToContainsOriginal) {
            this.source = source;
            this.originalRecipients = immutable(originalRecipients);
            this.deliveryMailboxes = immutable(deliveryMailboxes);
            this.envelopeTo = immutable(envelopeTo);
            this.trustedReceivedFor = immutable(trustedReceivedFor);
            this.xOriginalTo = immutable(xOriginalTo);
            this.visibleTo = immutable(visibleTo);
            this.localDeliveryHosts = immutable(localDeliveryHosts);
            this.allObservedRecipients = immutable(allObservedRecipients);
            this.corroboratedByReceived = corroboratedByReceived;
            this.visibleToContainsOriginal = visibleToContainsOriginal;
        }

        public String primaryOriginalRecipient() {
            return originalRecipients.isEmpty() ? null : originalRecipients.iterator().next();
        }
    }
}
