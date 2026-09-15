package eu.faircode.email;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Remembers that the user reviewed an ambiguous alias-compromise question at a
 * particular spam-hit count. New spam evidence automatically makes it reviewable
 * again. COMPROMISED itself remains represented by EntityAlias.state.
 */
public final class AliasCompromiseReviewStore {
    private static final String PREFIX = "alias_compromise_review:";

    private AliasCompromiseReviewStore() {
    }

    public static boolean needsReview(Context context, EntityAlias alias) {
        if (context == null || alias == null || alias.account_uuid == null || alias.address == null)
            return false;
        int spam = alias.spam_hits == null ? 0 : alias.spam_hits;
        if (spam <= 0 || alias.state != EntityAlias.STATE_ACTIVE)
            return false;
        Long reviewedAtSpamCount = SpamIntelligenceDB.getInstance(context)
                .alias().getMetaLong(key(alias.account_uuid, alias.address));
        return reviewedAtSpamCount == null || reviewedAtSpamCount < spam;
    }

    public static boolean markReviewedHealthy(Context context, EntityAlias alias) {
        if (context == null || alias == null || alias.account_uuid == null || alias.address == null)
            return false;
        DaoAlias dao = SpamIntelligenceDB.getInstance(context).alias();
        String metaKey = key(alias.account_uuid, alias.address);
        long currentSpam = alias.spam_hits == null ? 0L : alias.spam_hits;
        Long previous = dao.getMetaLong(metaKey);
        if (previous != null && previous == currentSpam)
            return false;
        EntitySpamMeta meta = new EntitySpamMeta();
        meta.key = metaKey;
        meta.long_value = currentSpam;
        dao.putMeta(meta);
        return true;
    }

    public static String accountMetaPrefix(String accountUuid) {
        if (accountUuid == null)
            return PREFIX;
        return PREFIX + accountUuid.trim() + ":";
    }

    public static String globalMetaPrefix() {
        return PREFIX;
    }

    private static String key(String accountUuid, String address) {
        return PREFIX + accountUuid.trim() + ":" + hex64(fnv1a64(address.trim().toLowerCase(Locale.ROOT)));
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
}
