package eu.faircode.email;

import android.content.Context;
import android.text.TextUtils;

import java.util.Collections;
import java.util.List;

/**
 * Separates actual human decisions/errors from optional recommendations.
 * Recommendations are never authorization gates.
 */
public final class SpamControlAttentionStats {
    public static final Stats EMPTY = new Stats(0, 0, 0, 0);

    private SpamControlAttentionStats() {
    }

    /** Must be called off the Android main thread. */
    public static Stats compute(Context context, String accountUuid) {
        if (context == null || TextUtils.isEmpty(accountUuid))
            return EMPTY;

        DaoAlias dao = SpamIntelligenceDB.getInstance(context).alias();
        List<EntityAlias> aliases = dao.getAliases(accountUuid.trim());
        if (aliases == null)
            aliases = Collections.emptyList();

        int compromiseDecisions = 0;
        int serverFailures = 0;
        int replacementMissing = 0;
        int replacementUnverified = 0;
        boolean compromisePolicyEnabled = SpamControlPolicy.markAliasCompromised(context);

        for (EntityAlias alias : aliases) {
            if (alias == null)
                continue;

            if (compromisePolicyEnabled &&
                    AliasCompromiseReviewStore.needsReview(context, alias))
                compromiseDecisions++;

            if (alias.smtp_reject_state != null &&
                    alias.smtp_reject_state == EntityAlias.SMTP_REJECT_FAILED)
                serverFailures++;

            boolean compromised = alias.state != null &&
                    (alias.state == EntityAlias.STATE_COMPROMISED ||
                            alias.state == EntityAlias.STATE_REPLACED);
            if (!compromised)
                continue;

            if (TextUtils.isEmpty(alias.replaced_by)) {
                replacementMissing++;
                continue;
            }

            String replacementAddress = AliasRegistry.normalizeAddress(alias.replaced_by);
            EntityAlias replacement = replacementAddress == null
                    ? null : dao.getAlias(accountUuid.trim(), replacementAddress);
            if (!AliasReplacementVerifier.verify(alias, replacement).verified)
                replacementUnverified++;
        }

        return new Stats(compromiseDecisions, serverFailures,
                replacementMissing, replacementUnverified);
    }

    public static final class Stats {
        public final int compromiseDecisions;
        public final int serverFailures;
        public final int replacementMissingRecommendations;
        public final int replacementUnverifiedRecommendations;

        Stats(int compromiseDecisions,
              int serverFailures,
              int replacementMissingRecommendations,
              int replacementUnverifiedRecommendations) {
            this.compromiseDecisions = Math.max(0, compromiseDecisions);
            this.serverFailures = Math.max(0, serverFailures);
            this.replacementMissingRecommendations = Math.max(0, replacementMissingRecommendations);
            this.replacementUnverifiedRecommendations = Math.max(0, replacementUnverifiedRecommendations);
        }

        public int actualNeedsBeyondMail() {
            return compromiseDecisions + serverFailures;
        }

        public int recommendations() {
            return replacementMissingRecommendations + replacementUnverifiedRecommendations;
        }
    }
}
