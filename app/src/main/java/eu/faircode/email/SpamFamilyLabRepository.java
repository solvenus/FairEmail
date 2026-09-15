package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;

import androidx.lifecycle.LiveData;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;

/** Read/write facade for the human-facing Spam Control UI. */
public final class SpamFamilyLabRepository {
    public static final double STRONG_THRESHOLD = SpamFamilyEngine.DEFAULT_DETECT_THRESHOLD;
    private static final int MAX_CANDIDATES = 500;

    public enum ActionResult {
        APPLIED,
        MESSAGE_MISSING,
        ACCOUNT_MISMATCH,
        FAMILY_MISSING,
        REJECTED
    }

    public static final class BulkActionResult {
        public final ActionResult result;
        public final int messages;
        public final int aliases;

        BulkActionResult(ActionResult result, int messages, int aliases) {
            this.result = result == null ? ActionResult.REJECTED : result;
            this.messages = Math.max(0, messages);
            this.aliases = Math.max(0, aliases);
        }

        public static BulkActionResult rejected() {
            return new BulkActionResult(ActionResult.REJECTED, 0, 0);
        }

        static BulkActionResult error(ActionResult result) {
            return new BulkActionResult(result, 0, 0);
        }
    }

    private SpamFamilyLabRepository() {
    }

    public static LiveData<List<TupleSpamFamilyOverview>> liveOverview(
            Context context, String accountUuid) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty())
            return null;
        return SpamIntelligenceDB.getInstance(context).family()
                .liveFamilyOverview(accountUuid.trim(), STRONG_THRESHOLD);
    }

    /** Must be called off the Android main thread. */
    public static List<Candidate> getReviewQueue(Context context,
                                                  String accountUuid,
                                                  int requestedLimit) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty())
            return Collections.emptyList();
        int limit = Math.max(1, Math.min(MAX_CANDIDATES, requestedLimit));
        String account = accountUuid.trim();
        SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);
        boolean includeReviewed = !SpamControlPolicy.hideReviewed(context);
        List<EntityAliasDelivery> deliveries = intelligence.alias()
                .getReviewQueue(account, includeReviewed, limit);
        if (deliveries == null || deliveries.isEmpty())
            return Collections.emptyList();

        DB mail = DB.getInstance(context);
        DaoAlias aliasDao = intelligence.alias();
        List<Candidate> result = new ArrayList<>(deliveries.size());
        for (EntityAliasDelivery delivery : deliveries) {
            if (delivery == null)
                continue;
            EntityMessage message = null;
            try {
                message = mail.message().getMessage(delivery.message_id);
            } catch (Throwable ex) {
                Log.w(ex);
            }
            if (message == null)
                continue;

            boolean suspiciousAlias = "SUSPICIOUS".equals(delivery.traffic_verdict);
            boolean historicalJunk = EntityFolder.JUNK.equals(delivery.folder_type);
            boolean exactPrediction = false;
            if (delivery.predicted_family_id != null) {
                if (SpamControlPolicy.exactFamilyDetection(context)) {
                    SpamFamilyIdentity.Identity identity =
                            SpamFamilyMessageAdapter.identityFromMessage(message);
                    if (identity != null) {
                        SpamFamilyStore.Match exact = SpamFamilyStore.matchIdentity(
                                context, account, delivery.message_id, identity.key);
                        exactPrediction = exact.familyId != null &&
                                exact.familyId.longValue() == delivery.predicted_family_id.longValue();
                    }
                }

                if (!exactPrediction) {
                    // Legacy fuzzy predictions are derived cache, never human truth.
                    // Clear them eagerly so even a historical 100% fuzzy score cannot
                    // masquerade as an exact sender-name + subject match.
                    intelligence.family().clearFamilyMatch(
                            account, delivery.message_id, System.currentTimeMillis());
                    delivery = aliasDao.getDelivery(account, delivery.message_id);
                    if (delivery == null)
                        continue;
                }
            }

            if (!suspiciousAlias && !historicalJunk && !exactPrediction)
                continue;

            EntityAlias alias = null;
            try {
                alias = aliasDao.getAlias(account, delivery.address);
            } catch (Throwable ex) {
                Log.w(ex);
            }
            Long contextFamily = exactPrediction
                    ? delivery.predicted_family_id : delivery.family_id;
            result.add(Candidate.from(delivery, message, alias, contextFamily));
        }
        return result;
    }

    /** Must be called off the Android main thread. */
    public static List<Candidate> getCandidates(Context context,
                                                String accountUuid,
                                                long familyId,
                                                int requestedLimit) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() || familyId <= 0)
            return Collections.emptyList();

        int limit = Math.max(1, Math.min(MAX_CANDIDATES, requestedLimit));
        String account = accountUuid.trim();
        SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);
        List<EntityAliasDelivery> deliveries = intelligence.family()
                .getFamilyCandidates(account, familyId, limit);
        if (deliveries == null || deliveries.isEmpty())
            return Collections.emptyList();

        DB mail = DB.getInstance(context);
        DaoAlias aliasDao = intelligence.alias();
        List<Candidate> result = new ArrayList<>(deliveries.size());
        for (EntityAliasDelivery delivery : deliveries) {
            if (delivery == null)
                continue;
            EntityMessage message = null;
            try {
                message = mail.message().getMessage(delivery.message_id);
            } catch (Throwable ex) {
                Log.w(ex);
            }

            EntityAlias alias = null;
            try {
                alias = aliasDao.getAlias(account, delivery.address);
            } catch (Throwable ex) {
                Log.w(ex);
            }
            result.add(Candidate.from(delivery, message, alias, familyId));
        }
        return result;
    }

    /** Human statement: this message is spam. Exact identity chooses the family. */
    public static ActionResult markSpam(Context context,
                                        String accountUuid,
                                        long messageId) {
        Resolved resolved = resolve(context, accountUuid, messageId);
        if (resolved.result != null)
            return resolved.result;
        SpamIntelligence.learnSpam(context, resolved.account, resolved.message, null);
        EntityAliasDelivery after = SpamIntelligenceDB.getInstance(context)
                .alias().getDelivery(resolved.account.uuid, messageId);
        return after != null && after.label == EntityAliasDelivery.LABEL_SPAM
                ? ActionResult.APPLIED : ActionResult.REJECTED;
    }

    /**
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
        DaoAlias aliasDao = SpamIntelligenceDB.getInstance(context).alias();
        EntityAliasDelivery after = aliasDao.getDelivery(resolved.account.uuid, messageId);
        if (after == null || after.label != EntityAliasDelivery.LABEL_SPAM)
            return BulkActionResult.rejected();

        if (after.family_id == null)
            return new BulkActionResult(ActionResult.APPLIED, 1, after.address == null ? 0 : 1);

        SpamExactBulkPropagator.Result propagated = SpamExactBulkPropagator.propagate(
                context, resolved.account, resolved.message, after.family_id, after.address);
        int messages = Math.max(1, propagated.messages);
        int aliases = Math.max(after.address == null ? 0 : 1, propagated.aliases);
        return new BulkActionResult(ActionResult.APPLIED, messages, aliases);
    }

    /** Explicitly confirm this locally available message as spam in this exact group. */
    public static ActionResult confirmSpam(Context context,
                                           String accountUuid,
                                           long familyId,
                                           long messageId) {
        Resolved resolved = resolve(context, accountUuid, messageId);
        if (resolved.result != null)
            return resolved.result;

        SpamIntelligenceDB intelligence = SpamIntelligenceDB.getInstance(context);
        EntitySpamFamily family = intelligence.family().getFamily(familyId);
        if (family == null || family.id == null ||
                !resolved.account.uuid.equals(family.account_uuid))
            return ActionResult.FAMILY_MISSING;

        EntityAliasDelivery before = intelligence.alias()
                .getDelivery(resolved.account.uuid, messageId);
        if (before != null &&
                before.label == EntityAliasDelivery.LABEL_SPAM &&
                before.family_id != null && before.family_id != familyId) {
            SpamFamilyReassigner.Result reassigned = SpamFamilyReassigner.reassign(
                    context, resolved.account, resolved.message, familyId);
            if (!reassigned.applied())
                return ActionResult.REJECTED;
        } else
            SpamIntelligence.learnSpam(context, resolved.account, resolved.message, familyId);

        EntityAliasDelivery after = intelligence.alias()
                .getDelivery(resolved.account.uuid, messageId);
        return after != null &&
                after.label == EntityAliasDelivery.LABEL_SPAM &&
                after.family_id != null && after.family_id == familyId
                ? ActionResult.APPLIED : ActionResult.REJECTED;
    }

    public static ActionResult markOtherSpam(Context context,
                                             String accountUuid,
                                             long currentFamilyId,
                                             long messageId) {
        return SpamFamilyHumanActions.markOtherSpam(
                context, accountUuid, currentFamilyId, messageId);
    }

    public static ActionResult markLegitimate(Context context,
                                              String accountUuid,
                                              long messageId) {
        Resolved resolved = resolve(context, accountUuid, messageId);
        if (resolved.result != null)
            return resolved.result;

        SpamIntelligence.learnHam(context, resolved.account, resolved.message);
        EntityAliasDelivery after = SpamIntelligenceDB.getInstance(context)
                .alias().getDelivery(resolved.account.uuid, messageId);
        return after != null && after.label == EntityAliasDelivery.LABEL_HAM
                ? ActionResult.APPLIED : ActionResult.REJECTED;
    }

    public static ActionResult excludeFromFamily(Context context,
                                                 String accountUuid,
                                                 long familyId,
                                                 long messageId) {
        Resolved resolved = resolve(context, accountUuid, messageId);
        if (resolved.result != null)
            return resolved.result;

        EntitySpamFamily family = SpamIntelligenceDB.getInstance(context)
                .family().getFamily(familyId);
        if (family == null || family.id == null ||
                !resolved.account.uuid.equals(family.account_uuid))
            return ActionResult.FAMILY_MISSING;

        boolean accepted = SpamIntelligence.excludeFromFamily(
                context, resolved.account, resolved.message, familyId, "spam_control");
        int stored = SpamIntelligenceDB.getInstance(context).family()
                .countExclusion(resolved.account.uuid, messageId, familyId);
        return accepted && stored > 0 ? ActionResult.APPLIED : ActionResult.REJECTED;
    }

    public static void renameFamily(Context context, long familyId, String name) {
        if (context == null || familyId <= 0)
            return;
        String value = name == null ? null : name.trim();
        if (value != null && value.isEmpty())
            value = null;
        SpamIntelligenceDB.getInstance(context).family().setFamilyName(familyId, value);
    }

    public static void setFamilyActive(Context context, long familyId, boolean active) {
        if (context == null || familyId <= 0)
            return;
        SpamIntelligenceDB.getInstance(context).family()
                .setFamilyActive(familyId, active, System.currentTimeMillis());
    }

    public static void requestRescore(Context context, String accountUuid, long familyId) {
        SpamFamilyRescorer.enqueue(context, accountUuid, familyId);
    }

    /** Human-readable representative identity for family administration. Off-main-thread only. */
    public static String describeFamily(Context context, long familyId) {
        if (context == null || familyId <= 0)
            return null;
        try {
            List<EntitySpamFamilyExemplar> exemplars = SpamIntelligenceDB.getInstance(context)
                    .family().getExemplars(familyId);
            if (exemplars == null)
                return null;
            DB mail = DB.getInstance(context);
            for (EntitySpamFamilyExemplar exemplar : exemplars) {
                if (exemplar == null)
                    continue;
                EntityMessage message = mail.message().getMessage(exemplar.source_message_id);
                if (message == null)
                    continue;
                String senderName = null;
                Address[] from = message.from;
                if (from != null && from.length > 0 && from[0] instanceof InternetAddress)
                    senderName = ((InternetAddress) from[0]).getPersonal();
                if (senderName == null)
                    senderName = "(uten avsendernavn)";
                String subject = message.subject == null ? "(uten emne)" : message.subject;
                return senderName + "\n" + subject;
            }
        } catch (Throwable ex) {
            Log.w(ex);
        }
        return null;
    }

    private static Resolved resolve(Context context, String accountUuid, long messageId) {
        if (context == null || accountUuid == null || accountUuid.trim().isEmpty() || messageId <= 0)
            return Resolved.error(ActionResult.MESSAGE_MISSING);
        try {
            DB db = DB.getInstance(context);
            EntityMessage message = db.message().getMessage(messageId);
            if (message == null || message.account == null)
                return Resolved.error(ActionResult.MESSAGE_MISSING);
            EntityAccount account = db.account().getAccount(message.account);
            if (account == null || account.uuid == null ||
                    !accountUuid.trim().equals(account.uuid))
                return Resolved.error(ActionResult.ACCOUNT_MISMATCH);
            return new Resolved(account, message, null);
        } catch (Throwable ex) {
            Log.e(ex);
            return Resolved.error(ActionResult.MESSAGE_MISSING);
        }
    }

    private static final class Resolved {
        final EntityAccount account;
        final EntityMessage message;
        final ActionResult result;

        Resolved(EntityAccount account, EntityMessage message, ActionResult result) {
            this.account = account;
            this.message = message;
            this.result = result;
        }

        static Resolved error(ActionResult result) {
            return new Resolved(null, null, result);
        }
    }

    private static String expectedDomains(EntityAlias alias) {
        if (alias == null)
            return null;
        Set<String> domains = new LinkedHashSet<>();
        if (alias.service_domain != null && !alias.service_domain.trim().isEmpty())
            domains.add(alias.service_domain.trim());
        try {
            JSONArray trusted = new JSONArray(alias.trusted_domains == null ? "[]" : alias.trusted_domains);
            for (int i = 0; i < trusted.length(); i++) {
                String domain = trusted.optString(i, null);
                if (domain != null && !domain.trim().isEmpty())
                    domains.add(domain.trim());
            }
        } catch (Throwable ex) {
            Log.w(ex);
        }
        return domains.isEmpty() ? null : android.text.TextUtils.join(", ", domains);
    }

    public static final class Candidate {
        public final long messageId;
        public final long received;
        public final String subject;
        public final String sender;
        public final String preview;
        public final boolean messagePresent;

        public final String alias;
        public final String service;
        public final String expectedDomains;
        public final int aliasSpamHits;
        public final int aliasHamHits;
        public final int aliasState;
        public final String folderType;
        public final String senderDomain;
        public final boolean hasUnsubscribe;
        public final int label;
        public final Long confirmedFamilyId;
        public final Long predictedFamilyId;

        public final Double aliasSpamSupport;
        public final Double aliasHamSupport;
        public final String aliasVerdict;
        public final String aliasReasons;

        public final double overallSpamSupport;
        public final double overallHamSupport;
        public final SpamDecisionScorer.Verdict overallVerdict;
        public final List<String> overallReasons;

        public final Double score;
        public final Double raw;
        public final Double text;
        public final Double structure;
        public final Double links;
        public final Double senderScore;
        public final Long assessedAt;

        public final boolean strong;
        public final boolean confirmedThisFamily;
        public final boolean explicitHam;

        private Candidate(long messageId,
                          long received,
                          String subject,
                          String sender,
                          String preview,
                          boolean messagePresent,
                          String alias,
                          String service,
                          String expectedDomains,
                          int aliasSpamHits,
                          int aliasHamHits,
                          int aliasState,
                          String folderType,
                          String senderDomain,
                          boolean hasUnsubscribe,
                          int label,
                          Long confirmedFamilyId,
                          Long predictedFamilyId,
                          Double aliasSpamSupport,
                          Double aliasHamSupport,
                          String aliasVerdict,
                          String aliasReasons,
                          double overallSpamSupport,
                          double overallHamSupport,
                          SpamDecisionScorer.Verdict overallVerdict,
                          List<String> overallReasons,
                          Double score,
                          Double raw,
                          Double text,
                          Double structure,
                          Double links,
                          Double senderScore,
                          Long assessedAt,
                          boolean strong,
                          boolean confirmedThisFamily,
                          boolean explicitHam) {
            this.messageId = messageId;
            this.received = received;
            this.subject = subject;
            this.sender = sender;
            this.preview = preview;
            this.messagePresent = messagePresent;
            this.alias = alias;
            this.service = service;
            this.expectedDomains = expectedDomains;
            this.aliasSpamHits = aliasSpamHits;
            this.aliasHamHits = aliasHamHits;
            this.aliasState = aliasState;
            this.folderType = folderType;
            this.senderDomain = senderDomain;
            this.hasUnsubscribe = hasUnsubscribe;
            this.label = label;
            this.confirmedFamilyId = confirmedFamilyId;
            this.predictedFamilyId = predictedFamilyId;
            this.aliasSpamSupport = aliasSpamSupport;
            this.aliasHamSupport = aliasHamSupport;
            this.aliasVerdict = aliasVerdict;
            this.aliasReasons = aliasReasons;
            this.overallSpamSupport = overallSpamSupport;
            this.overallHamSupport = overallHamSupport;
            this.overallVerdict = overallVerdict;
            this.overallReasons = overallReasons;
            this.score = score;
            this.raw = raw;
            this.text = text;
            this.structure = structure;
            this.links = links;
            this.senderScore = senderScore;
            this.assessedAt = assessedAt;
            this.strong = strong;
            this.confirmedThisFamily = confirmedThisFamily;
            this.explicitHam = explicitHam;
        }

        private static Candidate from(EntityAliasDelivery delivery,
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
                    delivery.predicted_family_id != null &&
                    delivery.predicted_family_id.longValue() == familyId.longValue();
            Double selectedScore = predictedThisFamily ? delivery.family_score : null;
            double value = selectedScore == null ? -1.0 : selectedScore;
            boolean confirmed = familyId != null &&
                    delivery.label == EntityAliasDelivery.LABEL_SPAM &&
                    delivery.family_id != null &&
                    delivery.family_id.longValue() == familyId.longValue();

            int aliasSpam = alias == null || alias.spam_hits == null ? 0 : alias.spam_hits;
            int aliasHam = alias == null || alias.ham_hits == null ? 0 : alias.ham_hits;
            int aliasState = alias == null || alias.state == null
                    ? EntityAlias.STATE_ACTIVE : alias.state;

            SpamDecisionScorer.ExplicitLabel explicitLabel;
            if (delivery.label == EntityAliasDelivery.LABEL_SPAM)
                explicitLabel = SpamDecisionScorer.ExplicitLabel.SPAM;
            else if (delivery.label == EntityAliasDelivery.LABEL_HAM)
                explicitLabel = SpamDecisionScorer.ExplicitLabel.HAM;
            else
                explicitLabel = SpamDecisionScorer.ExplicitLabel.UNKNOWN;

            SpamDecisionScorer.Result overall = SpamDecisionScorer.score(
                    explicitLabel,
                    delivery.family_score,
                    delivery.spam_support == null ? 0.0 : delivery.spam_support,
                    delivery.ham_support == null ? 0.0 : delivery.ham_support);

            return new Candidate(
                    delivery.message_id,
                    delivery.received,
                    subject,
                    sender,
                    preview,
                    message != null,
                    delivery.address,
                    alias == null ? null : alias.service,
                    expectedDomains(alias),
                    aliasSpam,
                    aliasHam,
                    aliasState,
                    delivery.folder_type,
                    delivery.sender_domain,
                    delivery.has_unsubscribe,
                    delivery.label,
                    delivery.family_id,
                    delivery.predicted_family_id,
                    delivery.spam_support,
                    delivery.ham_support,
                    delivery.traffic_verdict,
                    delivery.traffic_reasons,
                    overall.spamSupport,
                    overall.hamSupport,
                    overall.verdict,
                    overall.reasons,
                    selectedScore,
                    predictedThisFamily ? delivery.family_score_raw : null,
                    predictedThisFamily ? delivery.family_text : null,
                    predictedThisFamily ? delivery.family_structure : null,
                    predictedThisFamily ? delivery.family_links : null,
                    predictedThisFamily ? delivery.family_sender : null,
                    predictedThisFamily ? delivery.family_assessed_at : null,
                    value >= STRONG_THRESHOLD,
                    confirmed,
                    delivery.label == EntityAliasDelivery.LABEL_HAM);
        }
    }
}
