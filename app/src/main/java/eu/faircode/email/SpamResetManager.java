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
 * Human-facing blank-slate reset. The reset is itself checkpointed by
 * SpamUndoManager and can be reversed with "Angre siste valg".
 */
public final class SpamResetManager {
    private SpamResetManager() {
    }

    public static boolean resetLearning(Context context, String historyAccountUuid) {
        return SpamUndoManager.resetAllLearning(context, historyAccountUuid);
    }
}
