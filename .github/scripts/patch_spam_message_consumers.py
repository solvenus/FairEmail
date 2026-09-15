from pathlib import Path
import re

ROOT = Path('app/src/main/java/eu/faircode/email')


def read(name):
    return (ROOT / name).read_text()


def write(name, text):
    (ROOT / name).write_text(text)


def once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, got {count}')
    return text.replace(old, new, 1)


def between(text, start, end, replacement, label):
    a = text.find(start)
    if a < 0:
        raise SystemExit(f'{label}: start not found')
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f'{label}: end not found')
    if text.find(start, a + 1) >= 0 and text.find(start, a + 1) < b:
        raise SystemExit(f'{label}: ambiguous start')
    return text[:a] + replacement + text[b:]

# ------------------------------------------------------------------
# SpamFamilyLabRepository: canonical message rows drive review/family UI.
# ------------------------------------------------------------------
name = 'SpamFamilyLabRepository.java'
text = read(name)

start = '''    /** Must be called off the Android main thread. */\n    public static List<Candidate> getReviewQueue'''
end = '''    /** Must be called off the Android main thread. */\n    public static List<Candidate> getCandidates'''
new = '''    /** Must be called off the Android main thread. */
    public static List<Candidate> getReviewQueue(Context context,
                                                  String accountUuid,
                                                  int requestedLimit) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty())
            return Collections.emptyList();
        int limit = Math.max(1, Math.min(MAX_CANDIDATES, requestedLimit));
        String account = accountUuid.trim();
        SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);
        boolean includeReviewed = !SpamControlPolicy.hideReviewed(context);
        DaoSpamMessage messageDao = intelligence.message();
        List<EntitySpamMessage> states = messageDao.getReviewQueue(account, includeReviewed, limit);
        if (states == null || states.isEmpty())
            return Collections.emptyList();

        DB mail = DB.getInstance(context);
        DaoAlias aliasDao = intelligence.alias();
        List<Candidate> result = new ArrayList<>(states.size());
        for (EntitySpamMessage original : states) {
            if (original == null)
                continue;
            EntitySpamMessage state = original;
            EntityMessage message = null;
            try {
                message = mail.message().getMessage(state.message_id);
            } catch (Throwable ex) {
                Log.w(ex);
            }
            if (message == null)
                continue;

            EntityAliasDelivery delivery = aliasDao.getDelivery(account, state.message_id);
            boolean suspiciousAlias = delivery != null &&
                    "SUSPICIOUS".equals(delivery.traffic_verdict);
            boolean historicalJunk = EntityFolder.JUNK.equals(state.folder_type);
            boolean exactPrediction = false;
            if (state.predicted_family_id != null) {
                if (SpamControlPolicy.exactFamilyDetection(context)) {
                    SpamFamilyIdentity.Identity identity =
                            SpamFamilyMessageAdapter.identityFromMessage(message);
                    if (identity != null) {
                        SpamFamilyStore.Match exact = SpamFamilyStore.matchIdentity(
                                context, account, state.message_id, identity.key);
                        exactPrediction = exact.familyId != null &&
                                exact.familyId.longValue() == state.predicted_family_id.longValue();
                    }
                }

                if (!exactPrediction) {
                    long now = System.currentTimeMillis();
                    messageDao.clearPrediction(account, state.message_id, now);
                    // alias_delivery is only a compatibility/enrichment mirror.
                    intelligence.family().clearFamilyMatch(account, state.message_id, now);
                    state = messageDao.get(account, state.message_id);
                    if (state == null)
                        continue;
                }
            }

            if (!suspiciousAlias && !historicalJunk && !exactPrediction)
                continue;

            EntityAlias alias = null;
            String aliasAddress = delivery == null ? state.delivered_to : delivery.address;
            if (aliasAddress != null) {
                try {
                    alias = aliasDao.getAlias(account, aliasAddress);
                } catch (Throwable ex) {
                    Log.w(ex);
                }
            }
            Long contextFamily = exactPrediction
                    ? state.predicted_family_id : state.family_id;
            result.add(Candidate.from(state, delivery, message, alias, contextFamily));
        }
        return result;
    }

'''
text = between(text, start, end, new, 'repository-review')

start = '''    /** Must be called off the Android main thread. */\n    public static List<Candidate> getCandidates'''
end = '''    /** Human statement: this message is spam. Exact identity chooses the family. */'''
new = '''    /** Must be called off the Android main thread. */
    public static List<Candidate> getCandidates(Context context,
                                                String accountUuid,
                                                long familyId,
                                                int requestedLimit) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() || familyId <= 0)
            return Collections.emptyList();

        int limit = Math.max(1, Math.min(MAX_CANDIDATES, requestedLimit));
        String account = accountUuid.trim();
        SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);
        List<EntitySpamMessage> states = intelligence.message()
                .getFamilyCandidates(account, familyId, limit);
        if (states == null || states.isEmpty())
            return Collections.emptyList();

        DB mail = DB.getInstance(context);
        DaoAlias aliasDao = intelligence.alias();
        List<Candidate> result = new ArrayList<>(states.size());
        for (EntitySpamMessage state : states) {
            if (state == null)
                continue;
            EntityMessage message = null;
            try {
                message = mail.message().getMessage(state.message_id);
            } catch (Throwable ex) {
                Log.w(ex);
            }

            EntityAliasDelivery delivery = aliasDao.getDelivery(account, state.message_id);
            EntityAlias alias = null;
            String aliasAddress = delivery == null ? state.delivered_to : delivery.address;
            if (aliasAddress != null) {
                try {
                    alias = aliasDao.getAlias(account, aliasAddress);
                } catch (Throwable ex) {
                    Log.w(ex);
                }
            }
            result.add(Candidate.from(state, delivery, message, alias, familyId));
        }
        return result;
    }

'''
text = between(text, start, end, new, 'repository-candidates')

start = '''    /** Human statement: this message is spam. Exact identity chooses the family. */\n    public static ActionResult markSpam'''
end = '''    /**\n     * One human Spam decision means the exact sender-name + subject identity is'''
new = '''    /** Human statement: this message is spam. Exact identity chooses the family. */
    public static ActionResult markSpam(Context context,
                                        String accountUuid,
                                        long messageId) {
        Resolved resolved = resolve(context, accountUuid, messageId);
        if (resolved.result != null)
            return resolved.result;
        SpamIntelligence.learnSpam(context, resolved.account, resolved.message, null);
        EntitySpamMessage after = SpamIntelligenceDB.getInstance(context)
                .message().get(resolved.account.uuid, messageId);
        return after != null && after.label == EntitySpamMessage.LABEL_SPAM
                ? ActionResult.APPLIED : ActionResult.REJECTED;
    }

'''
text = between(text, start, end, new, 'repository-mark-spam')

start = '''    /**\n     * One human Spam decision means the exact sender-name + subject identity is'''
end = '''    /** Explicitly confirm this locally available message as spam in this exact group. */'''
new = '''    /**
     * One human Spam decision means the exact sender-name + subject identity is
     * spam. Apply that truth to every retained UNKNOWN twin while preserving
     * explicit HAM exceptions.
     */
    public static BulkActionResult markSpamBulk(Context context,
                                                String accountUuid,
                                                long messageId) {
        Resolved resolved = resolve(context, accountUuid, messageId);
        if (resolved.result != null)
            return BulkActionResult.error(resolved.result);

        SpamIntelligence.learnSpam(context, resolved.account, resolved.message, null);
        SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);
        EntitySpamMessage after = intelligence.message().get(resolved.account.uuid, messageId);
        if (after == null || after.label != EntitySpamMessage.LABEL_SPAM)
            return BulkActionResult.rejected();

        EntityAliasDelivery aliasRow = intelligence.alias()
                .getDelivery(resolved.account.uuid, messageId);
        String seedAlias = aliasRow == null ? after.delivered_to : aliasRow.address;
        if (after.family_id == null)
            return new BulkActionResult(ActionResult.APPLIED, 1, seedAlias == null ? 0 : 1);

        SpamExactBulkPropagator.Result propagated = SpamExactBulkPropagator.propagate(
                context, resolved.account, resolved.message, after.family_id, seedAlias);
        int messages = Math.max(1, propagated.messages);
        int aliases = Math.max(seedAlias == null ? 0 : 1, propagated.aliases);
        return new BulkActionResult(ActionResult.APPLIED, messages, aliases);
    }

'''
text = between(text, start, end, new, 'repository-mark-spam-bulk')

old = '''        SpamIntelligence.learnHam(context, resolved.account, resolved.message);\n        EntityAliasDelivery after = SpamIntelligenceDB.getInstance(context)\n                .alias().getDelivery(resolved.account.uuid, messageId);\n        return after != null && after.label == EntityAliasDelivery.LABEL_HAM\n                ? ActionResult.APPLIED : ActionResult.REJECTED;\n'''
new = '''        SpamIntelligence.learnHam(context, resolved.account, resolved.message);\n        EntitySpamMessage after = SpamIntelligenceDB.getInstance(context)\n                .message().get(resolved.account.uuid, messageId);\n        return after != null && after.label == EntitySpamMessage.LABEL_HAM\n                ? ActionResult.APPLIED : ActionResult.REJECTED;\n'''
text = once(text, old, new, 'repository-mark-legitimate')

# Candidate projection: canonical message truth + optional alias evidence.
pattern = re.compile(r'''        private static Candidate from\(EntityAliasDelivery delivery,.*?\n        }\n    }\n}\s*$''', re.S)
m = pattern.search(text)
if not m:
    raise SystemExit('candidate-from: method tail not found')
new_tail = '''        private static Candidate from(EntitySpamMessage state,
                                      EntityAliasDelivery delivery,
                                      EntityMessage message,
                                      EntityAlias alias,
                                      Long familyId) {
            String sender = null;
            String subject = null;
            String preview = null;
            if (message != null) {
                subject = message.subject;
                preview = message.preview;
                Address[] from = message.from;
                if (from != null && from.length > 0 && from[0] != null)
                    sender = from[0].toString();
            }

            boolean predictedThisFamily = familyId != null &&
                    state.predicted_family_id != null &&
                    state.predicted_family_id.longValue() == familyId.longValue();
            Double selectedScore = predictedThisFamily ? state.family_score : null;
            double value = selectedScore == null ? -1.0 : selectedScore;
            boolean confirmed = familyId != null &&
                    state.label == EntitySpamMessage.LABEL_SPAM &&
                    state.family_id != null &&
                    state.family_id.longValue() == familyId.longValue();

            int aliasSpam = alias == null || alias.spam_hits == null ? 0 : alias.spam_hits;
            int aliasHam = alias == null || alias.ham_hits == null ? 0 : alias.ham_hits;
            int aliasState = alias == null || alias.state == null
                    ? EntityAlias.STATE_ACTIVE : alias.state;

            SpamDecisionScorer.ExplicitLabel explicitLabel;
            if (state.label == EntitySpamMessage.LABEL_SPAM)
                explicitLabel = SpamDecisionScorer.ExplicitLabel.SPAM;
            else if (state.label == EntitySpamMessage.LABEL_HAM)
                explicitLabel = SpamDecisionScorer.ExplicitLabel.HAM;
            else
                explicitLabel = SpamDecisionScorer.ExplicitLabel.UNKNOWN;

            Double aliasSpamSupport = delivery == null ? null : delivery.spam_support;
            Double aliasHamSupport = delivery == null ? null : delivery.ham_support;
            SpamDecisionScorer.Result overall = SpamDecisionScorer.score(
                    explicitLabel,
                    state.family_score,
                    aliasSpamSupport == null ? 0.0 : aliasSpamSupport,
                    aliasHamSupport == null ? 0.0 : aliasHamSupport);

            String address = delivery == null ? state.delivered_to : delivery.address;
            return new Candidate(
                    state.message_id,
                    state.received,
                    subject,
                    sender,
                    preview,
                    message != null,
                    address,
                    alias == null ? null : alias.service,
                    expectedDomains(alias),
                    aliasSpam,
                    aliasHam,
                    aliasState,
                    state.folder_type,
                    delivery == null ? null : delivery.sender_domain,
                    delivery != null && delivery.has_unsubscribe,
                    state.label,
                    state.family_id,
                    state.predicted_family_id,
                    aliasSpamSupport,
                    aliasHamSupport,
                    delivery == null ? null : delivery.traffic_verdict,
                    delivery == null ? null : delivery.traffic_reasons,
                    overall.spamSupport,
                    overall.hamSupport,
                    overall.verdict,
                    overall.reasons,
                    selectedScore,
                    predictedThisFamily && delivery != null ? delivery.family_score_raw : null,
                    predictedThisFamily && delivery != null ? delivery.family_text : null,
                    predictedThisFamily && delivery != null ? delivery.family_structure : null,
                    predictedThisFamily && delivery != null ? delivery.family_links : null,
                    predictedThisFamily && delivery != null ? delivery.family_sender : null,
                    state.family_assessed_at,
                    value >= STRONG_THRESHOLD,
                    confirmed,
                    state.label == EntitySpamMessage.LABEL_HAM);
        }
    }
}
'''
text = text[:m.start()] + new_tail
write(name, text)

# ------------------------------------------------------------------
# Queue count: canonical message queue.
# ------------------------------------------------------------------
name = 'SpamControlQueueStats.java'
text = read(name)
text = once(text,
'''        return Math.max(0, SpamIntelligenceDB.getInstance(context)\n                .alias().countReviewQueue(accountUuid.trim(), includeReviewed));\n''',
'''        return Math.max(0, SpamIntelligenceDB.getInstance(context)\n                .message().countReviewQueue(accountUuid.trim(), includeReviewed));\n''',
'queue-stats-message-dao')
write(name, text)

# ------------------------------------------------------------------
# DaoSpamMessage: suspicious alias evidence can admit a canonical message too.
# ------------------------------------------------------------------
name = 'DaoSpamMessage.java'
text = read(name)
old = '''            " AND (folder_type = '" + EntityFolder.JUNK + "'" +\n            "   OR predicted_family_id IS NOT NULL)" +\n'''
new = '''            " AND (folder_type = '" + EntityFolder.JUNK + "'" +\n            "   OR predicted_family_id IS NOT NULL" +\n            "   OR EXISTS (SELECT 1 FROM alias_delivery d" +\n            "       WHERE d.account_uuid = spam_message.account_uuid" +\n            "       AND d.message_id = spam_message.message_id" +\n            "       AND d.traffic_verdict = 'SUSPICIOUS'))" +\n'''
count = text.count(old)
if count != 2:
    raise SystemExit(f'dao-message-review-predicate: expected 2 matches, got {count}')
text = text.replace(old, new)
write(name, text)

# ------------------------------------------------------------------
# Exact bulk propagation: canonical UNKNOWN messages, alias optional.
# ------------------------------------------------------------------
name = 'SpamExactBulkPropagator.java'
text = read(name)
text = once(text,
'''        DaoAlias aliasDao = SpamIntelligenceDB.getInstance(context).alias();\n        DB mail = DB.getInstance(context);\n''',
'''        SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);\n        DaoSpamMessage messageDao = intelligence.message();\n        DaoAlias aliasDao = intelligence.alias();\n        DB mail = DB.getInstance(context);\n''',
'bulk-daos')
text = once(text,
'''            List<EntityAliasDelivery> page = aliasDao.getUnknownDeliveriesAfter(\n                    account.uuid, afterMessageId, PAGE_SIZE);\n''',
'''            List<EntitySpamMessage> page = messageDao.getUnknownAfter(\n                    account.uuid, afterMessageId, PAGE_SIZE);\n''',
'bulk-page')
text = once(text,
'''            for (EntityAliasDelivery delivery : page) {\n                if (delivery == null)\n                    continue;\n                afterMessageId = Math.max(afterMessageId, delivery.message_id);\n                if (delivery.message_id == seed.id)\n                    continue;\n\n                try {\n                    EntityMessage message = mail.message().getMessage(delivery.message_id);\n''',
'''            for (EntitySpamMessage state : page) {\n                if (state == null)\n                    continue;\n                afterMessageId = Math.max(afterMessageId, state.message_id);\n                if (state.message_id == seed.id)\n                    continue;\n\n                try {\n                    EntityMessage message = mail.message().getMessage(state.message_id);\n''',
'bulk-loop')
text = once(text,
'''                    EntityAliasDelivery after = aliasDao.getDelivery(\n                            account.uuid, delivery.message_id);\n                    if (after != null &&\n                            after.label == EntityAliasDelivery.LABEL_SPAM &&\n                            after.family_id != null && after.family_id == familyId) {\n                        messages++;\n                        if (after.address != null)\n                            touchedAliases.add(after.address);\n                    }\n''',
'''                    EntitySpamMessage after = messageDao.get(account.uuid, state.message_id);\n                    if (after != null &&\n                            after.label == EntitySpamMessage.LABEL_SPAM &&\n                            after.family_id != null && after.family_id == familyId) {\n                        messages++;\n                        EntityAliasDelivery aliasRow = aliasDao.getDelivery(\n                                account.uuid, state.message_id);\n                        String address = aliasRow == null ? after.delivered_to : aliasRow.address;\n                        if (address != null)\n                            touchedAliases.add(address);\n                    }\n''',
'bulk-after')
write(name, text)

# ------------------------------------------------------------------
# Exact family rescorer: canonical messages, alias mirror optional.
# ------------------------------------------------------------------
name = 'SpamFamilyRescorer.java'
text = read(name)
text = once(text,
'''        DaoSpamFamily dao = intelligence.family();\n        EntitySpamRescoreTask task = dao.getNextRescoreTask();\n''',
'''        DaoSpamFamily dao = intelligence.family();\n        DaoSpamMessage messageDao = intelligence.message();\n        EntitySpamRescoreTask task = dao.getNextRescoreTask();\n''',
'rescorer-message-dao')
text = once(text,
'''        List<EntityAliasDelivery> page = dao.getRescorePage(\n                task.account_uuid, task.before_message_id, PAGE_SIZE);\n''',
'''        List<EntitySpamMessage> page = messageDao.getRescorePage(\n                task.account_uuid, task.before_message_id, PAGE_SIZE);\n''',
'rescorer-page')
text = once(text,
'''        for (EntityAliasDelivery delivery : page) {\n            if (delivery == null)\n                continue;\n            nextBefore = Math.min(nextBefore, delivery.message_id);\n            processed++;\n\n            try {\n                EntityMessage message = mail.message().getMessage(delivery.message_id);\n''',
'''        for (EntitySpamMessage state : page) {\n            if (state == null)\n                continue;\n            nextBefore = Math.min(nextBefore, state.message_id);\n            processed++;\n\n            try {\n                EntityMessage message = mail.message().getMessage(state.message_id);\n''',
'rescorer-loop')
text = once(text,
'''                if (!SpamControlPolicy.exactFamilyDetection(context)) {\n                    dao.clearFamilyMatch(task.account_uuid, delivery.message_id, assessedAt);\n                    continue;\n                }\n''',
'''                if (!SpamControlPolicy.exactFamilyDetection(context)) {\n                    messageDao.clearPrediction(task.account_uuid, state.message_id, assessedAt);\n                    dao.clearFamilyMatch(task.account_uuid, state.message_id, assessedAt);\n                    continue;\n                }\n''',
'rescorer-disabled')
text = text.replace('task.account_uuid, delivery.message_id, identity.key',
                    'task.account_uuid, state.message_id, identity.key')
text = once(text,
'''                if (best == null || best.familyId == null) {\n                    dao.clearFamilyMatch(task.account_uuid, delivery.message_id, assessedAt);\n                    continue;\n                }\n''',
'''                if (best == null || best.familyId == null) {\n                    messageDao.clearPrediction(task.account_uuid, state.message_id, assessedAt);\n                    dao.clearFamilyMatch(task.account_uuid, state.message_id, assessedAt);\n                    continue;\n                }\n''',
'rescorer-no-match')
text = once(text,
'''                SpamFamilyEngine.Score score = best.score;\n                dao.setFamilyMatch(task.account_uuid, delivery.message_id, best.familyId,\n                        score.value, score.raw, score.text, score.structure,\n                        score.links, score.sender, assessedAt);\n''',
'''                SpamFamilyEngine.Score score = best.score;\n                messageDao.setPrediction(task.account_uuid, state.message_id, best.familyId,\n                        score.value, assessedAt);\n                dao.setFamilyMatch(task.account_uuid, state.message_id, best.familyId,\n                        score.value, score.raw, score.text, score.structure,\n                        score.links, score.sender, assessedAt);\n''',
'rescorer-set')
write(name, text)

# ------------------------------------------------------------------
# Family overview/statistics use canonical message truth.
# ------------------------------------------------------------------
name = 'DaoSpamFamily.java'
text = read(name)
for old, new, label in [
    ('(SELECT COUNT(*) FROM alias_delivery d', '(SELECT COUNT(*) FROM spam_message d', 'family-overview-table'),
    ('d.label = " + EntityAliasDelivery.LABEL_UNKNOWN', 'd.label = " + EntitySpamMessage.LABEL_UNKNOWN', 'family-overview-unknown'),
    ('d.label = " + EntityAliasDelivery.LABEL_HAM', 'd.label = " + EntitySpamMessage.LABEL_HAM', 'family-overview-ham')]:
    if old not in text:
        raise SystemExit(f'{label}: pattern missing')
    text = text.replace(old, new)
text = once(text,
'''    @Query("SELECT COUNT(*) FROM alias_delivery" +\n            " WHERE family_id = :familyId" +\n            " AND label = " + EntityAliasDelivery.LABEL_SPAM)\n''',
'''    @Query("SELECT COUNT(*) FROM spam_message" +\n            " WHERE family_id = :familyId" +\n            " AND label = " + EntitySpamMessage.LABEL_SPAM)\n''',
'family-count-confirmed')
write(name, text)

print('patched canonical message consumers')
