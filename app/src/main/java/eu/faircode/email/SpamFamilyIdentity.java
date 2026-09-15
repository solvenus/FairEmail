package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Deterministic spam-family identity.
 *
 * For the user's real spam stream, campaign/family identity is defined by the
 * pair sender display name + subject. Sender address and delivery alias are
 * intentionally excluded because those rotate inside one campaign.
 */
public final class SpamFamilyIdentity {
    private static final String VERSION = "v1";

    private SpamFamilyIdentity() {
    }

    public static Identity fromRaw(String senderName, String subject) {
        String name = normalize(senderName);
        String title = normalize(subject);
        if (name == null || title == null)
            return null;
        String canonical = name + "\u001f" + title;
        return new Identity(name, title, VERSION + ":" + hex64(fnv1a64(canonical)));
    }

    public static String metaKey(String accountUuid, String identityKey) {
        if (accountUuid == null || identityKey == null)
            return null;
        return "family_identity:" + accountUuid.trim() + ":" + identityKey;
    }

    static String normalize(String value) {
        if (value == null)
            return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u00a0', ' ')
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static String hex64(long value) {
        return String.format(Locale.ROOT, "%016x", value);
    }

    public static final class Identity {
        public final String normalizedSenderName;
        public final String normalizedSubject;
        public final String key;

        private Identity(String normalizedSenderName,
                         String normalizedSubject,
                         String key) {
            this.normalizedSenderName = normalizedSenderName;
            this.normalizedSubject = normalizedSubject;
            this.key = key;
        }

        public String display() {
            return normalizedSenderName + " · " + normalizedSubject;
        }
    }
}
