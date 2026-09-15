package eu.faircode.email;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Idempotent historical importer for Spam Control.
 *
 * It observes retained FairEmail messages from Inbox and/or Junk exactly as if
 * they had arrived today. Folder location is evidence/context, never automatic
 * spam/ham truth. Existing human labels are therefore preserved.
 */
public final class SpamHistoricalScanner {
    private static final int PAGE_SIZE = 250;

    private SpamHistoricalScanner() {
    }

    public static Result scan(Context context,
                              EntityAccount account,
                              boolean includeInbox,
                              boolean includeJunk) {
        if (context == null || account == null || account.id == null || account.uuid == null)
            return Result.error("account-missing");
        if (!includeInbox && !includeJunk)
            return Result.error("no-source-selected");

        Context app = context.getApplicationContext();
        DB mail = DB.getInstance(app);
        SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(app);
        DaoAlias aliasDao = intelligence.alias();

        List<String> folderTypes = new ArrayList<>();
        if (includeInbox)
            folderTypes.add(EntityFolder.INBOX);
        if (includeJunk)
            folderTypes.add(EntityFolder.JUNK);

        long afterMessageId = 0L;
        int examined = 0;
        int observed = 0;
        int newlyImported = 0;
        int inbox = 0;
        int junk = 0;
        int skippedNoEnvelope = 0;
        int missingFolder = 0;
        Map<Long, EntityFolder> folderCache = new HashMap<>();

        try {
            while (true) {
                List<EntityMessage> page = mail.message().getSpamControlHistoricalPage(
                        account.id, folderTypes, afterMessageId, PAGE_SIZE);
                if (page == null || page.isEmpty())
                    break;

                for (EntityMessage message : page) {
                    if (message == null || message.id == null)
                        continue;
                    afterMessageId = Math.max(afterMessageId, message.id);
                    examined++;

                    EntityFolder folder = null;
                    if (message.folder != null) {
                        folder = folderCache.get(message.folder);
                        if (folder == null) {
                            folder = mail.folder().getFolder(message.folder);
                            if (folder != null)
                                folderCache.put(message.folder, folder);
                        }
                    }
                    if (folder == null) {
                        missingFolder++;
                        continue;
                    }

                    if (EntityFolder.INBOX.equals(folder.type))
                        inbox++;
                    else if (EntityFolder.JUNK.equals(folder.type))
                        junk++;

                    if (message.deliveredto == null) {
                        skippedNoEnvelope++;
                        continue;
                    }

                    EntityAliasDelivery before = aliasDao.getDelivery(account.uuid, message.id);
                    SpamIntelligence.observeMessage(app, account, folder, message);
                    EntityAliasDelivery after = aliasDao.getDelivery(account.uuid, message.id);
                    if (after != null) {
                        observed++;
                        if (before == null)
                            newlyImported++;
                    }
                }

                if (page.size() < PAGE_SIZE)
                    break;
            }

            // Re-evaluate retained observations after the whole history is present,
            // because alias/domain evidence gets stronger as the scan progresses.
            SpamFamilyRescorer.enqueueAllActive(app, account.uuid);
            SpamFamilyRescorer.start(app);

            return new Result(true, null, examined, observed, newlyImported,
                    inbox, junk, skippedNoEnvelope, missingFolder);
        } catch (Throwable ex) {
            Log.e(ex);
            return new Result(false, ex.getClass().getSimpleName(), examined, observed,
                    newlyImported, inbox, junk, skippedNoEnvelope, missingFolder);
        }
    }

    public static final class Result {
        public final boolean success;
        public final String error;
        public final int examined;
        public final int observed;
        public final int newlyImported;
        public final int inbox;
        public final int junk;
        public final int skippedNoEnvelope;
        public final int missingFolder;

        Result(boolean success, String error, int examined, int observed,
               int newlyImported, int inbox, int junk,
               int skippedNoEnvelope, int missingFolder) {
            this.success = success;
            this.error = error;
            this.examined = examined;
            this.observed = observed;
            this.newlyImported = newlyImported;
            this.inbox = inbox;
            this.junk = junk;
            this.skippedNoEnvelope = skippedNoEnvelope;
            this.missingFolder = missingFolder;
        }

        static Result error(String error) {
            return new Result(false, error, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
