package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;

/** Canonical per-message state adapter for Spam Control. */
public final class SpamMessageStore {
    private SpamMessageStore() {
    }

    public static boolean observe(Context context,
                                  String accountUuid,
                                  long messageId,
                                  long received,
                                  String folderType,
                                  String deliveredTo) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() || messageId <= 0)
            return false;
        DaoSpamMessage dao = SpamIntelligenceDB.getInstance(context).message();
        EntitySpamMessage existing = dao.get(accountUuid, messageId);
        if (existing == null) {
            EntitySpamMessage row = new EntitySpamMessage();
            row.account_uuid = accountUuid.trim();
            row.message_id = messageId;
            row.received = received > 0 ? received : System.currentTimeMillis();
            row.folder_type = folderType;
            row.delivered_to = AliasRegistry.normalizeAddress(deliveredTo);
            return dao.insert(row) != -1;
        }
        String alias = AliasRegistry.normalizeAddress(deliveredTo);
        long timestamp = received > 0 ? received : existing.received;
        dao.updateObservation(accountUuid.trim(), messageId, timestamp, folderType, alias);
        return false;
    }

    public static boolean setLabel(Context context,
                                   String accountUuid,
                                   long messageId,
                                   int label,
                                   Long familyId) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() || messageId <= 0)
            return false;
        if (label != EntitySpamMessage.LABEL_UNKNOWN &&
                label != EntitySpamMessage.LABEL_HAM &&
                label != EntitySpamMessage.LABEL_SPAM)
            throw new IllegalArgumentException("label=" + label);
        DaoSpamMessage dao = SpamIntelligenceDB.getInstance(context).message();
        EntitySpamMessage before = dao.get(accountUuid.trim(), messageId);
        if (before == null)
            return false;
        Long effectiveFamily = label == EntitySpamMessage.LABEL_SPAM ? familyId : null;
        if (before.label == label && java.util.Objects.equals(before.family_id, effectiveFamily))
            return false;
        return dao.setLabel(accountUuid.trim(), messageId, label, effectiveFamily) > 0;
    }

    public static EntitySpamMessage get(Context context, String accountUuid, long messageId) {
        if (context == null || accountUuid == null || messageId <= 0)
            return null;
        return SpamIntelligenceDB.getInstance(context).message().get(accountUuid.trim(), messageId);
    }
}
