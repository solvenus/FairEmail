package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;

/**
 * Human-facing corrections for Spam Control.
 *
 * These operations deliberately express what the user actually knows. In
 * particular, "other spam" means "this is spam, but not this spam group" and
 * must never be translated into HAM merely to satisfy the internal model.
 */
public final class SpamFamilyHumanActions {
    private SpamFamilyHumanActions() {
    }

    public static SpamFamilyLabRepository.ActionResult markOtherSpam(
            Context context,
            String accountUuid,
            long currentFamilyId,
            long messageId) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() ||
                currentFamilyId <= 0 || messageId <= 0)
            return SpamFamilyLabRepository.ActionResult.MESSAGE_MISSING;

        try {
            Context app = context.getApplicationContext();
            DB mail = DB.getInstance(app);
            EntityMessage message = mail.message().getMessage(messageId);
            if (message == null || message.account == null)
                return SpamFamilyLabRepository.ActionResult.MESSAGE_MISSING;

            EntityAccount account = mail.account().getAccount(message.account);
            if (account == null || account.uuid == null ||
                    !accountUuid.trim().equals(account.uuid))
                return SpamFamilyLabRepository.ActionResult.ACCOUNT_MISMATCH;

            SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(app);
            DaoSpamFamily familyDao = intelligence.family();
            EntitySpamFamily current = familyDao.getFamily(currentFamilyId);
            if (current == null || current.id == null ||
                    !account.uuid.equals(current.account_uuid))
                return SpamFamilyLabRepository.ActionResult.FAMILY_MISSING;

            EntityAliasDelivery before = intelligence.alias()
                    .getDelivery(account.uuid, messageId);
            if (before == null)
                return SpamFamilyLabRepository.ActionResult.MESSAGE_MISSING;

            // Already confirmed as spam in another group: preserve that truth and
            // simply make sure this group can no longer claim the message.
            if (before.label == EntityAliasDelivery.LABEL_SPAM &&
                    before.family_id != null && before.family_id != currentFamilyId) {
                boolean excluded = SpamIntelligence.excludeFromFamily(
                        app, account, message, currentFamilyId, "spam_control_other_spam");
                EntityAliasDelivery after = intelligence.alias()
                        .getDelivery(account.uuid, messageId);
                return excluded && after != null &&
                        after.label == EntityAliasDelivery.LABEL_SPAM &&
                        (after.family_id == null || after.family_id != currentFamilyId)
                        ? SpamFamilyLabRepository.ActionResult.APPLIED
                        : SpamFamilyLabRepository.ActionResult.REJECTED;
            }

            // If this message currently defines the selected group, record the
            // exclusion before detaching it. If detaching deletes an empty group,
            // its exclusion is harmlessly deleted with it and relearning creates a
            // new group instead.
            if (before.label == EntityAliasDelivery.LABEL_SPAM &&
                    before.family_id != null && before.family_id == currentFamilyId) {
                EntitySpamFamilyExclusion exclusion = new EntitySpamFamilyExclusion();
                exclusion.account_uuid = account.uuid;
                exclusion.message_id = messageId;
                exclusion.family_id = currentFamilyId;
                exclusion.created_at = System.currentTimeMillis();
                exclusion.reason = "spam_control_other_spam";
                familyDao.insertExclusion(exclusion);
                SpamIntelligence.clearLabel(app, account, message);
            } else {
                boolean excluded = SpamIntelligence.excludeFromFamily(
                        app, account, message, currentFamilyId, "spam_control_other_spam");
                if (!excluded)
                    return SpamFamilyLabRepository.ActionResult.REJECTED;
            }

            // Relearn as spam with the selected group excluded. The normal family
            // engine may join another existing group or create a new one.
            SpamIntelligence.learnSpam(app, account, message, null);

            EntityAliasDelivery after = intelligence.alias()
                    .getDelivery(account.uuid, messageId);
            return after != null &&
                    after.label == EntityAliasDelivery.LABEL_SPAM &&
                    (after.family_id == null || after.family_id != currentFamilyId)
                    ? SpamFamilyLabRepository.ActionResult.APPLIED
                    : SpamFamilyLabRepository.ActionResult.REJECTED;
        } catch (Throwable ex) {
            Log.e(ex);
            return SpamFamilyLabRepository.ActionResult.REJECTED;
        }
    }
}
