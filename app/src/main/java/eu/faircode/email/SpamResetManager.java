package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;

import java.util.concurrent.Callable;

/** Safely resets learned spam state without touching mail, user alias config or SMTP state. */
public final class SpamResetManager {
    private SpamResetManager() {
    }

    public static boolean resetLearning(Context context, String accountUuid) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty())
            return false;

        final Context app = context.getApplicationContext();
        final String account = accountUuid.trim();
        final SpamIntelligenceDB db = SpamIntelligenceDB.getInstance(app);
        try {
            return db.runInTransaction(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    DaoSpamFamily family = db.family();
                    DaoAlias alias = db.alias();

                    // Derived model state first, then retained delivery labels/scores.
                    family.deleteRescoreTasksForAccount(account);
                    family.deleteExclusionsForAccount(account);
                    family.deleteExemplarsForAccount(account);
                    family.deleteFamiliesForAccount(account);

                    alias.resetDeliveryLearning(account);
                    alias.resetAliasLearning(account);

                    // Pending operation intents predate the reset and must not
                    // resurrect old human labels after the reset completes.
                    alias.deleteAllSpamIntents();

                    // Reset intentionally clears the undo stack too: the user
                    // requested a genuinely blank learning history.
                    db.actions().deleteForAccount(account);
                    return true;
                }
            });
        } catch (Throwable ex) {
            Log.e(ex);
            return false;
        }
    }
}
