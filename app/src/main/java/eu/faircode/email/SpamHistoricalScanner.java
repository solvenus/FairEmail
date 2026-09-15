package eu.faircode.email;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Idempotent historical importer for Spam Control.
 *
 * Every retained Inbox/Junk message is indexed at message level. Envelope-To
 * enriches alias intelligence when available, but is never required for the
 * message to exist in Spam Control.
 *
 * Folder location is evidence/context, never automatic spam/ham truth.
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
        DaoSpamMessage messageDao = intelligence.message();

        List<String> folderTypes = new ArrayList<>();
        if (includeInbox)
            folderTypes.add(EntityFolder.INBOX);
        if (includeJunk)
            folderTypes.add(EntityFolder.JUNK);

        long afterMessageId = 0L;
        int examined = 0;
        int indexed = 0;
        int newlyIndexed = 0;
        int aliasObserved = 0;
        int newlyImportedAlias = 0;
        int inbox = 0;
        int junk = 0;
        int missingEnvelope = 0;
        int missingFolder = 0;
        Map<Long, EntityFolder> folderCache = new HashMap<>();

        SpamControlLog.i(app, "SCAN",
                "START account=" + account.uuid +
                        " inbox=" + includeInbox + " junk=" + includeJunk);
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

                    EntitySpamMessage beforeMessage = messageDao.get(account.uuid, message.id);
                    SpamMessageStore.observe(app, account.uuid, message.id,
                            message.received, folder.type, message.deliveredto);
                    EntitySpamMessage afterMessage = messageDao.get(account.uuid, message.id);
                    if (afterMessage != null) {
                        indexed++;
                        if (beforeMessage == null)
                            newlyIndexed++;
                    }

                    if (message.deliveredto == null) {
                        missingEnvelope++;
                        SpamIntelligence.refreshMessageFamilyState(app, account, message, false);
                        continue;
                    }

                    EntityAliasDelivery beforeAlias = aliasDao.getDelivery(account.uuid, message.id);
                    SpamIntelligence.observeMessage(app, account, folder, message);
                    EntityAliasDelivery afterAlias = aliasDao.getDelivery(account.uuid, message.id);
                    if (afterAlias != null) {
                        aliasObserved++;
                        if (beforeAlias == null)
                            newlyImportedAlias++;
                    }
                }

                if (page.size() < PAGE_SIZE)
                    break;
            }

            // Re-evaluate retained messages after the whole history is indexed.
            SpamFamilyRescorer.enqueueAllActive(app, account.uuid);
            SpamFamilyRescorer.start(app);

            Result result = new Result(true, null, examined,
                    indexed, newlyIndexed, aliasObserved, newlyImportedAlias,
                    inbox, junk, missingEnvelope, missingFolder);
            SpamControlLog.i(app, "SCAN",
                    "DONE examined=" + examined +
                            " messageIndexed=" + indexed +
                            " messageNew=" + newlyIndexed +
                            " aliasObserved=" + aliasObserved +
                            " aliasNew=" + newlyImportedAlias +
                            " inbox=" + inbox + " junk=" + junk +
                            " noEnvelope=" + missingEnvelope +
                            " missingFolder=" + missingFolder);
            return result;
        } catch (Throwable ex) {
            Log.e(ex);
            SpamControlLog.e(app, "SCAN",
                    "FAILED examined=" + examined +
                            " messageIndexed=" + indexed +
                            " messageNew=" + newlyIndexed +
                            " aliasObserved=" + aliasObserved +
                            " aliasNew=" + newlyImportedAlias +
                            " inbox=" + inbox + " junk=" + junk +
                            " noEnvelope=" + missingEnvelope +
                            " missingFolder=" + missingFolder, ex);
            return new Result(false, ex.getClass().getSimpleName() + ": " +
                    String.valueOf(ex.getMessage()), examined,
                    indexed, newlyIndexed, aliasObserved, newlyImportedAlias,
                    inbox, junk, missingEnvelope, missingFolder);
        }
    }

    public static final class Result {
        public final boolean success;
        public final String error;
        public final int examined;
        /** Messages present in canonical spam_message after scan. */
        public final int indexed;
        public final int newlyIndexed;
        /** Alias-enriched messages, for backwards-compatible UI/reporting. */
        public final int observed;
        public final int newlyImported;
        public final int inbox;
        public final int junk;
        public final int skippedNoEnvelope;
        public final int missingFolder;

        Result(boolean success, String error, int examined,
               int indexed, int newlyIndexed,
               int observed, int newlyImported,
               int inbox, int junk,
               int skippedNoEnvelope, int missingFolder) {
            this.success = success;
            this.error = error;
            this.examined = examined;
            this.indexed = indexed;
            this.newlyIndexed = newlyIndexed;
            this.observed = observed;
            this.newlyImported = newlyImported;
            this.inbox = inbox;
            this.junk = junk;
            this.skippedNoEnvelope = skippedNoEnvelope;
            this.missingFolder = missingFolder;
        }

        static Result error(String error) {
            return new Result(false, error, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
