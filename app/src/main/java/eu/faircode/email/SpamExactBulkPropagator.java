package eu.faircode.email;

import android.content.Context;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies one explicit spam decision to every retained UNKNOWN delivery with
 * the same exact sender-name + subject identity. Explicit HAM is never
 * overwritten. This is intentionally separate from fuzzy/network similarity.
 */
public final class SpamExactBulkPropagator {
    private static final int PAGE_SIZE = 256;

    private SpamExactBulkPropagator() {
    }

    public static Result propagate(Context context,
                                   EntityAccount account,
                                   EntityMessage seed,
                                   long familyId,
                                   String seedAlias) {
        if (context == null || account == null || account.uuid == null ||
                seed == null || seed.id == null || familyId <= 0)
            return Result.NONE;

        SpamFamilyIdentity.Identity identity =
                SpamFamilyMessageAdapter.identityFromMessage(seed);
        if (identity == null)
            return Result.NONE;

        DaoAlias aliasDao = SpamIntelligenceDB.getInstance(context).alias();
        DB mail = DB.getInstance(context);
        long afterMessageId = 0L;
        int messages = 1;
        Set<String> touchedAliases = new HashSet<>();
        if (seedAlias != null)
            touchedAliases.add(seedAlias);

        while (true) {
            List<EntityAliasDelivery> page = aliasDao.getUnknownDeliveriesAfter(
                    account.uuid, afterMessageId, PAGE_SIZE);
            if (page == null || page.isEmpty())
                break;

            for (EntityAliasDelivery delivery : page) {
                if (delivery == null)
                    continue;
                afterMessageId = Math.max(afterMessageId, delivery.message_id);
                if (delivery.message_id == seed.id)
                    continue;

                try {
                    EntityMessage message = mail.message().getMessage(delivery.message_id);
                    if (message == null)
                        continue;
                    SpamFamilyIdentity.Identity candidate =
                            SpamFamilyMessageAdapter.identityFromMessage(message);
                    if (candidate == null || !identity.key.equals(candidate.key))
                        continue;

                    // The query only returns UNKNOWN rows. Human HAM decisions are
                    // therefore never silently overwritten by propagation.
                    SpamIntelligence.learnSpam(context, account, message, familyId);
                    EntityAliasDelivery after = aliasDao.getDelivery(
                            account.uuid, delivery.message_id);
                    if (after != null &&
                            after.label == EntityAliasDelivery.LABEL_SPAM &&
                            after.family_id != null && after.family_id == familyId) {
                        messages++;
                        if (after.address != null)
                            touchedAliases.add(after.address);
                    }
                } catch (Throwable ex) {
                    // One malformed/missing retained message must not prevent the
                    // rest of an exact identity batch from being learned.
                    Log.w(ex);
                }
            }

            if (page.size() < PAGE_SIZE)
                break;
        }

        return new Result(messages, touchedAliases.size());
    }

    public static final class Result {
        static final Result NONE = new Result(0, 0);
        public final int messages;
        public final int aliases;

        Result(int messages, int aliases) {
            this.messages = Math.max(0, messages);
            this.aliases = Math.max(0, aliases);
        }
    }
}
