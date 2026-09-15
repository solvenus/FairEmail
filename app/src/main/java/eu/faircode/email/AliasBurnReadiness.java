package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;

/** Single adapter from persistent alias state to the pure-Java burn policy. */
public final class AliasBurnReadiness {
    private AliasBurnReadiness() {
    }

    /**
     * Context-aware readiness used by the UI and actuator path. A configured
     * replacement becomes verified only after Spam Control has actually
     * observed legitimate/expected traffic on that replacement alias.
     */
    public static AliasBurnPolicy.Result evaluate(Context context, EntityAlias alias) {
        if (alias == null)
            throw new IllegalArgumentException("alias");

        AliasBurnPolicy.Input input = baseInput(alias);
        if (context != null)
            input.compromiseNeedsReview = AliasCompromiseReviewStore.needsReview(context, alias);
        if (context != null && input.replacementConfigured &&
                alias.account_uuid != null && alias.replaced_by != null) {
            EntityAlias replacement = SpamIntelligenceDB.getInstance(context)
                    .alias().getAlias(alias.account_uuid, AliasRegistry.normalizeAddress(alias.replaced_by));
            input.replacementVerified = AliasReplacementVerifier.verify(alias, replacement).verified;
        }
        return AliasBurnPolicy.evaluate(input);
    }

    /** Conservative pure adapter retained for non-UI callers/tests. */
    public static AliasBurnPolicy.Result evaluate(EntityAlias alias) {
        if (alias == null)
            throw new IllegalArgumentException("alias");
        return AliasBurnPolicy.evaluate(baseInput(alias));
    }

    private static AliasBurnPolicy.Input baseInput(EntityAlias alias) {
        AliasBurnPolicy.Input input = new AliasBurnPolicy.Input();
        input.spamHits = alias.spam_hits == null ? 0 : alias.spam_hits;
        input.hamHits = alias.ham_hits == null ? 0 : alias.ham_hits;
        input.aliasCompromised = alias.state != null &&
                (alias.state == EntityAlias.STATE_COMPROMISED ||
                        alias.state == EntityAlias.STATE_REPLACED);
        input.compromiseNeedsReview = !input.aliasCompromised && input.spamHits > 0;
        input.serviceDomainKnown = !TextUtils.isEmpty(alias.service_domain);
        input.trustedDomainsConfigured = hasTrustedDomains(alias.trusted_domains);
        input.replacementConfigured = !TextUtils.isEmpty(alias.replaced_by);
        input.replacementVerified = false;
        input.serverState = mapServerState(alias.smtp_reject_state);
        return input;
    }

    static boolean hasTrustedDomains(String json) {
        if (TextUtils.isEmpty(json))
            return false;
        try {
            JSONArray values = new JSONArray(json);
            for (int i = 0; i < values.length(); i++)
                if (!TextUtils.isEmpty(values.optString(i, null)))
                    return true;
        } catch (Throwable ex) {
            // Corrupt optional metadata must not accidentally authorize burn.
            Log.w(ex);
        }
        return false;
    }

    static AliasBurnPolicy.ServerState mapServerState(Integer state) {
        int value = state == null ? EntityAlias.SMTP_REJECT_NONE : state;
        if (value == EntityAlias.SMTP_REJECT_PENDING)
            return AliasBurnPolicy.ServerState.REJECT_PENDING;
        if (value == EntityAlias.SMTP_REJECT_VERIFIED)
            return AliasBurnPolicy.ServerState.REJECT_VERIFIED;
        if (value == EntityAlias.SMTP_REJECT_FAILED)
            return AliasBurnPolicy.ServerState.FAILED;
        if (value == EntityAlias.SMTP_RESTORE_PENDING)
            return AliasBurnPolicy.ServerState.RESTORE_PENDING;
        return AliasBurnPolicy.ServerState.NONE;
    }
}
