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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Account-scoped inventory of real SMTP delivery aliases.
 *
 * This model is deliberately independent of spam classification. Every
 * received message can update the inventory. Spam intelligence is metadata
 * attached to the same alias identity, not the reason the alias exists.
 */
public final class AliasRegistry {
    private AliasRegistry() {
    }

    public enum State {
        ACTIVE,
        REPLACED,
        DISABLED,
        IGNORED
    }

    public static final class Observation {
        public final long account;
        public final String deliveredTo;
        public final long received;
        public final String folderType;
        public final boolean spam;
        public final Long spamFamilyId;

        public Observation(long account, String deliveredTo, long received,
                           String folderType, boolean spam, Long spamFamilyId) {
            this.account = account;
            this.deliveredTo = deliveredTo;
            this.received = received;
            this.folderType = folderType;
            this.spam = spam;
            this.spamFamilyId = spamFamilyId;
        }
    }

    public static final class Model {
        private final Map<Key, MutableEntry> entries = new HashMap<>();

        public Entry observe(Observation observation) {
            if (observation == null) throw new IllegalArgumentException("observation");
            String alias = normalizeAddress(observation.deliveredTo);
            if (alias == null) return null;

            Key key = new Key(observation.account, alias);
            MutableEntry entry = entries.get(key);
            if (entry == null) {
                entry = new MutableEntry(observation.account, alias);
                entries.put(key, entry);
            }

            long timestamp = observation.received > 0 ? observation.received : System.currentTimeMillis();
            if (entry.firstSeen == 0 || timestamp < entry.firstSeen) entry.firstSeen = timestamp;
            if (timestamp > entry.lastSeen) entry.lastSeen = timestamp;
            entry.messages++;
            if (observation.spam) entry.spam++;
            else entry.ham++;

            String folder = normalizeToken(observation.folderType);
            if (folder != null)
                entry.folders.put(folder, entry.folders.containsKey(folder) ? entry.folders.get(folder) + 1 : 1);

            if (observation.spamFamilyId != null) {
                Long id = observation.spamFamilyId;
                entry.spamFamilies.put(id,
                        entry.spamFamilies.containsKey(id) ? entry.spamFamilies.get(id) + 1 : 1);
            }

            return entry.snapshot();
        }

        public Entry get(long account, String deliveredTo) {
            String alias = normalizeAddress(deliveredTo);
            if (alias == null) return null;
            MutableEntry entry = entries.get(new Key(account, alias));
            return entry == null ? null : entry.snapshot();
        }

        public Entry setState(long account, String deliveredTo, State state) {
            if (state == null) throw new IllegalArgumentException("state");
            String alias = normalizeAddress(deliveredTo);
            if (alias == null) return null;
            Key key = new Key(account, alias);
            MutableEntry entry = entries.get(key);
            if (entry == null) {
                entry = new MutableEntry(account, alias);
                entries.put(key, entry);
            }
            entry.state = state;
            return entry.snapshot();
        }

        public Entry setServiceName(long account, String deliveredTo, String serviceName) {
            String alias = normalizeAddress(deliveredTo);
            if (alias == null) return null;
            Key key = new Key(account, alias);
            MutableEntry entry = entries.get(key);
            if (entry == null) {
                entry = new MutableEntry(account, alias);
                entries.put(key, entry);
            }
            entry.serviceName = cleanLabel(serviceName);
            return entry.snapshot();
        }

        public List<Entry> snapshot(long account) {
            List<Entry> result = new ArrayList<>();
            for (MutableEntry value : entries.values())
                if (value.account == account)
                    result.add(value.snapshot());
            Collections.sort(result, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    int byLast = Long.compare(b.lastSeen, a.lastSeen);
                    if (byLast != 0) return byLast;
                    return a.alias.compareTo(b.alias);
                }
            });
            return Collections.unmodifiableList(result);
        }

        public List<Entry> spamAffected(long account) {
            List<Entry> result = new ArrayList<>();
            for (Entry entry : snapshot(account))
                if (entry.spam > 0)
                    result.add(entry);
            Collections.sort(result, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    int bySpam = Integer.compare(b.spam, a.spam);
                    if (bySpam != 0) return bySpam;
                    return Long.compare(b.lastSeen, a.lastSeen);
                }
            });
            return Collections.unmodifiableList(result);
        }
    }

    public static final class Entry {
        public final long account;
        public final String alias;
        public final String serviceName;
        public final State state;
        public final long firstSeen;
        public final long lastSeen;
        public final int messages;
        public final int spam;
        public final int ham;
        public final Map<String, Integer> folders;
        public final Map<Long, Integer> spamFamilies;

        private Entry(long account, String alias, String serviceName, State state,
                      long firstSeen, long lastSeen, int messages, int spam, int ham,
                      Map<String, Integer> folders, Map<Long, Integer> spamFamilies) {
            this.account = account;
            this.alias = alias;
            this.serviceName = serviceName;
            this.state = state;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.messages = messages;
            this.spam = spam;
            this.ham = ham;
            this.folders = Collections.unmodifiableMap(new LinkedHashMap<>(folders));
            this.spamFamilies = Collections.unmodifiableMap(new LinkedHashMap<>(spamFamilies));
        }

        public double spamRate() {
            return messages == 0 ? 0 : (double) spam / messages;
        }

        public int familyCount() {
            return spamFamilies.size();
        }
    }

    static String normalizeAddress(String value) {
        if (value == null) return null;
        String address = value.trim().toLowerCase(Locale.ROOT);
        if (address.startsWith("<") && address.endsWith(">") && address.length() > 2)
            address = address.substring(1, address.length() - 1).trim();
        int comma = address.indexOf(',');
        if (comma >= 0) address = address.substring(0, comma).trim();
        return address.indexOf('@') > 0 ? address : null;
    }

    /**
     * Best-effort convenience label for the user's sd_<service>@... convention.
     * The stored alias remains canonical; this is display metadata only.
     */
    public static String inferServiceName(String deliveredTo) {
        String address = normalizeAddress(deliveredTo);
        if (address == null) return null;
        String local = address.substring(0, address.indexOf('@'));
        if (local.startsWith("sd_") && local.length() > 3)
            return local.substring(3);
        return null;
    }

    private static String normalizeToken(String value) {
        if (value == null) return null;
        String token = value.trim().toLowerCase(Locale.ROOT);
        return token.isEmpty() ? null : token;
    }

    private static String cleanLabel(String value) {
        if (value == null) return null;
        String label = value.trim();
        return label.isEmpty() ? null : label;
    }

    private static final class Key {
        final long account;
        final String alias;

        Key(long account, String alias) {
            this.account = account;
            this.alias = alias;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return account == key.account && alias.equals(key.alias);
        }

        @Override
        public int hashCode() {
            int result = Long.valueOf(account).hashCode();
            result = 31 * result + alias.hashCode();
            return result;
        }
    }

    private static final class MutableEntry {
        final long account;
        final String alias;
        String serviceName;
        State state = State.ACTIVE;
        long firstSeen;
        long lastSeen;
        int messages;
        int spam;
        int ham;
        final Map<String, Integer> folders = new HashMap<>();
        final Map<Long, Integer> spamFamilies = new HashMap<>();

        MutableEntry(long account, String alias) {
            this.account = account;
            this.alias = alias;
            this.serviceName = inferServiceName(alias);
        }

        Entry snapshot() {
            return new Entry(account, alias, serviceName, state,
                    firstSeen, lastSeen, messages, spam, ham,
                    folders, spamFamilies);
        }
    }
}
