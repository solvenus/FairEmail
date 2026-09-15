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
 * Narrow integration facade between FairEmail and the custom intelligence
 * subsystem. Mail synchronization should only need to call this facade.
 */
public final class SpamIntelligence {
    private SpamIntelligence() {
    }

    /**
     * Convenience ingress for existing FairEmail paths that only have a
     * message/folder pair. Account UUID is resolved from FairEmail's main DB.
     */
    public static void observeMessage(Context context,
                                      EntityFolder folder,
                                      EntityMessage message) {
        try {
            if (context == null || folder == null || message == null || message.account == null)
                return;
            EntityAccount account = DB.getInstance(context).account().getAccount(message.account);
            observeMessage(context, account, folder, message);
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    /** Record envelope-alias metadata once the FairEmail message has a DB id. */
    public static void observeMessage(Context context,
                                      EntityAccount account,
                                      EntityFolder folder,
                                      EntityMessage message) {
        try {
            if (context == null || account == null || folder == null || message == null ||
                    message.id == null || account.uuid == null)
                return;

            SpamAliasStore.observeDelivery(
                    context,
                    account.uuid,
                    message.id,
                    message.deliveredto,
                    message.received,
                    folder.type);
        } catch (Throwable ex) {
            // Intelligence must never be able to break mail synchronization.
            Log.e(ex);
        }
    }

    public static void learnSpam(Context context,
                                 EntityAccount account,
                                 EntityMessage message,
                                 Long familyId) {
        setLabel(context, account, message, EntityAliasDelivery.LABEL_SPAM, familyId);
    }

    public static void learnHam(Context context,
                                EntityAccount account,
                                EntityMessage message) {
        setLabel(context, account, message, EntityAliasDelivery.LABEL_HAM, null);
    }

    public static void clearLabel(Context context,
                                  EntityAccount account,
                                  EntityMessage message) {
        setLabel(context, account, message, EntityAliasDelivery.LABEL_UNKNOWN, null);
    }

    private static void setLabel(Context context,
                                 EntityAccount account,
                                 EntityMessage message,
                                 int label,
                                 Long familyId) {
        try {
            if (context == null || account == null || message == null ||
                    message.id == null || account.uuid == null)
                return;

            SpamAliasStore.setLabel(
                    context,
                    account.uuid,
                    message.id,
                    label,
                    familyId);
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }
}
