/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package eu.faircode.email;

import android.content.Context;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;

/**
 * Account-scoped inventory of real SMTP delivery aliases.
 *
 * This model is deliberately independent of spam-family identity. Every
 * received message can update the inventory. Spam intelligence is metadata
 * attached to the same alias identity, not the reason the alias exists.
 *
 * V0 can reconstruct itself from EntityMessage.deliveredto, which FairEmail
 * already stores from Envelope-To/X-Envelope-To/X-Original-To/Delivered-To.
 * User-managed state is still in-memory in V0; persistence belongs in the
 * dedicated alias table planned for the next integration step.
 */
public final class AliasRegistry {
    private static final Pattern EMAIL_FALLBACK = Pattern.compile(
            "(?i)([a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,})");

    private AliasRegistry() {
    }

    public enum State {
        ACTIVE,
        REPLACED,
        DISABLED,
        IGNORED
    }

    public enum Verdict {
        SPAM,
        HAM,
        UNKNOWN
    }

    public static final class Observation {
        public final long account;
        public final String deliveredTo;
        public final long received;
        public final String folderType;
        public final Verdict verdict;
        public final Long spamFamilyId;

        public Observation(long account, String deliveredTo, long received,
                           String folderType, Verdict verdict, Long spamFamilyId) {
            this.account = account;
            this.deliveredTo = deliveredTo;
            this.received = received;
            this.folderType = folderType;
            this.verdict = (verdict == null ? Verdict.UNKNOWN : verdict);
            this.spamFamilyId = spamFamilyId;
        }
    }

    /**
     * Rebuild an inventory from the messages FairEmail currently retains.
     *
     * Folder placement is deliberately conservative: Junk is positive spam
     * evidence, while every other inbound folder is UNKNOWN, not automatically
     * HAM. Explicit not-spam evidence can be fed into observe() later.
     *
     * Call this off the main thread.
     */
    public static Model fromDatabase(Context context) {
        Model model = new Model();
        String sql =
                "SELECT message.account AS account" +
                ", message.deliveredto AS delivered_to" +
                ", message.received AS received" +
                ", folder.type AS folder_type" +
                " FROM message" +
                " JOIN folder ON folder.id = message.folder" +
                " WHERE message.deliveredto IS NOT NULL" +
                " AND TRIM(message.deliveredto) <> ''" +
                " AND NOT message.ui_hide" +
                " AND folder.type NOT IN (?, ?, ?)";

        DB db = DB.getInstance(context);
        try (Cursor cursor = db.query(sql, new Object[]{
                EntityFolder.SENT,
                EntityFolder.DRAFTS,
                EntityFolder.OUTBOX
        })) {
            int cAccount = cursor.getColumnIndexOrThrow("account");
            int cDeliveredTo = cursor.getColumnIndexOrThrow("delivered_to");
            int cReceived = cursor.getColumnIndexOrThrow("received");
            int cFolderType = cursor.getColumnIndexOrThrow("folder_type");

            while (cursor.moveToNext()) {
                String folderType = cursor.getString(cFolderType);
                Verdict verdict = EntityFolder.JUNK.equals(folderType)
                        ? Verdict.SPAM
                        : Verdict.UNKNOWN;
                model.observe(new Observation(
                        cursor.getLong(cAccount),
                        cursor.getString(cDeliveredTo),
                        cursor.getLong(cReceived),
                        folderType,
                        verdict,
                        null));
            }
        }
        return model;
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
            if (observation.verdict == Verdict.SPAM)
                entry.spam++;
            else if (observation.verdict == Verdict.HAM)
                entry.ham++;
            else
                entry.unknown++;

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
            sortByRecent(result);
            return Collections.unmodifiableList(result);
        }

        public List<Entry> snapshot() {
            List<Entry> result = new ArrayList<>();
            for (MutableEntry value : entries.values())
                result.add(value.snapshot());
            sortByRecent(result);
            return Collections.unmodifiableList(result);
        }

        public List<Entry> spamAffected(long account) {
            List<Entry> result = new ArrayList<>();
            for (Entry entry : snapshot(account))
                if (entry.spam > 0)
                    result.add(entry);
            sortBySpam(result);
            return Collections.unmodifiableList(result);
        }

        public List<Entry> spamAffected() {
            List<Entry> result = new ArrayList<>();
            for (Entry entry : snapshot())
                if (entry.spam > 0)
                    result.add(entry);
            sortBySpam(result);
            return Collections.unmodifiableList(result);
        }

        public int size() {
            return entries.size();
        }

        private static void sortByRecent(List<Entry> entries) {
            Collections.sort(entries, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    int byLast = Long.compare(b.lastSeen, a.lastSeen);
                    if (byLast != 0) return byLast;
                    int byAccount = Long.compare(a.account, b.account);
                    if (byAccount != 0) return byAccount;
                    return a.alias.compareTo(b.alias);
                }
            });
        }

        private static void sortBySpam(List<Entry> entries) {
            Collections.sort(entries, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    int bySpam = Integer.compare(b.spam, a.spam);
                    if (bySpam != 0) return bySpam;
                    int byFamilies = Integer.compare(b.familyCount(), a.familyCount());
                    if (byFamilies != 0) return byFamilies;
                    return Long.compare(b.lastSeen, a.lastSeen);
                }
            });
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
        public final int unknown;
        public final Map<String, Integer> folders;
        public final Map<Long, Integer> spamFamilies;

        private Entry(long account, String alias, String serviceName, State state,
                      long firstSeen, long lastSeen, int messages, int spam, int ham, int unknown,
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
            this.unknown = unknown;
            this.folders = Collections.unmodifiableMap(new LinkedHashMap<>(folders));
            this.spamFamilies = Collections.unmodifiableMap(new LinkedHashMap<>(spamFamilies));
        }

        /** Fraction of all retained traffic that has positive spam evidence. */
        public double spamTrafficFraction() {
            return messages == 0 ? 0 : (double) spam / messages;
        }

        /** Fraction of explicitly labeled SPAM/HAM evidence only. */
        public double spamLabeledFraction() {
            int labeled = spam + ham;
            return labeled == 0 ? 0 : (double) spam / labeled;
        }

        public boolean isSpamAffected() {
            return spam > 0;
        }

        public int familyCount() {
            return spamFamilies.size();
        }
    }

    static String normalizeAddress(String value) {
        if (value == null) return null;
        String raw = value.trim();
        if (raw.isEmpty()) return null;

        try {
            Address[] parsed = InternetAddress.parseHeader(raw, false);
            if (parsed != null)
                for (Address item : parsed)
                    if (item instanceof InternetAddress) {
                        String address = ((InternetAddress) item).getAddress();
                        String canonical = canonicalAddress(address);
                        if (canonical != null)
                            return canonical;
                    }
        } catch (Throwable ex) {
            Log.w(ex);
        }

        Matcher fallback = EMAIL_FALLBACK.matcher(raw);
        return fallback.find() ? canonicalAddress(fallback.group(1)) : null;
    }

    private static String canonicalAddress(String value) {
        if (value == null) return null;
        String address = value.trim();
        int at = address.lastIndexOf('@');
        if (at <= 0 || at + 1 >= address.length())
            return null;
        return address.substring(0, at).toLowerCase(Locale.ROOT) + "@" +
                address.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Best-effort convenience label for the sd_<service>@... convention.
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
        int unknown;
        final Map<String, Integer> folders = new HashMap<>();
        final Map<Long, Integer> spamFamilies = new HashMap<>();

        MutableEntry(long account, String alias) {
            this.account = account;
            this.alias = alias;
            this.serviceName = inferServiceName(alias);
        }

        Entry snapshot() {
            return new Entry(account, alias, serviceName, state,
                    firstSeen, lastSeen, messages, spam, ham, unknown,
                    folders, spamFamilies);
        }
    }
}
