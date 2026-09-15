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

/**
 * Coordinates local alias lifecycle with a remote SMTP rejection actuator.
 *
 * The manager is intentionally synchronous. Callers must run it on a background
 * executor and can therefore decide when/how UI progress is shown.
 */
public final class AliasBurnManager {
    public static final String DEFAULT_FAILURE_MESSAGE = "No such person at this address";

    private AliasBurnManager() {
    }

    public static Outcome burn(Context context,
                               String accountUuid,
                               String address,
                               AliasServerActuator actuator,
                               String failureMessage) {
        String alias = AliasRegistry.normalizeAddress(address);
        if (context == null || TextUtils.isEmpty(accountUuid) || alias == null || actuator == null)
            return Outcome.failed("invalid-arguments");

        DaoAlias dao = SpamIntelligenceDB.getInstance(context).alias();
        EntityAlias entity = dao.getAlias(accountUuid, alias);
        if (entity == null)
            return Outcome.failed("unknown-alias");
        if (entity.state == EntityAlias.STATE_IGNORED)
            return Outcome.failed("ignored-alias");
        if (entity.smtp_reject_state == EntityAlias.SMTP_REJECT_VERIFIED)
            return Outcome.verified(false);

        String reason = TextUtils.isEmpty(failureMessage)
                ? DEFAULT_FAILURE_MESSAGE
                : failureMessage.trim();
        long now = System.currentTimeMillis();

        dao.markCompromised(accountUuid, alias);
        dao.markSmtpRejectPending(accountUuid, alias, actuator.provider(), reason, now);

        try {
            AliasServerActuator.Result result = actuator.burn(alias, reason);
            persistSnapshot(dao, accountUuid, alias, result.routeSnapshot);
            if (result.verified) {
                dao.markSmtpRejectVerified(accountUuid, alias, System.currentTimeMillis());
                return Outcome.verified(result.changed);
            }

            String error = sanitizeError(result.error);
            dao.markSmtpRejectFailed(accountUuid, alias, error);
            return Outcome.failed(error);
        } catch (Throwable ex) {
            String error = sanitizeError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            dao.markSmtpRejectFailed(accountUuid, alias, error);
            Log.e(ex);
            return Outcome.failed(error);
        }
    }

    public static Outcome restore(Context context,
                                  String accountUuid,
                                  String address,
                                  AliasServerActuator actuator) {
        String alias = AliasRegistry.normalizeAddress(address);
        if (context == null || TextUtils.isEmpty(accountUuid) || alias == null || actuator == null)
            return Outcome.failed("invalid-arguments");

        DaoAlias dao = SpamIntelligenceDB.getInstance(context).alias();
        EntityAlias entity = dao.getAlias(accountUuid, alias);
        if (entity == null)
            return Outcome.failed("unknown-alias");
        if (entity.smtp_reject_state == EntityAlias.SMTP_REJECT_NONE)
            return Outcome.verified(false);
        if (!actuator.provider().equals(entity.smtp_reject_provider))
            return Outcome.failed("provider-mismatch");

        dao.markSmtpRestorePending(accountUuid, alias);
        try {
            AliasServerActuator.Result result = actuator.restore(alias, entity.smtp_route_snapshot);
            if (result.verified) {
                dao.clearSmtpReject(accountUuid, alias);
                // A restored compromised alias stays COMPROMISED. Restoring SMTP
                // acceptance is not evidence that the leak disappeared.
                return Outcome.verified(result.changed);
            }

            String error = sanitizeError(result.error);
            dao.markSmtpRejectFailed(accountUuid, alias, error);
            return Outcome.failed(error);
        } catch (Throwable ex) {
            String error = sanitizeError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            dao.markSmtpRejectFailed(accountUuid, alias, error);
            Log.e(ex);
            return Outcome.failed(error);
        }
    }

    private static void persistSnapshot(DaoAlias dao,
                                        String accountUuid,
                                        String address,
                                        String snapshot) {
        if (snapshot == null)
            return;
        EntityAlias entity = dao.getAlias(accountUuid, address);
        if (entity == null)
            return;
        entity.smtp_route_snapshot = snapshot;
        dao.updateAlias(entity);
    }

    /** Avoid accidentally persisting credentials or giant remote response bodies. */
    private static String sanitizeError(String error) {
        if (TextUtils.isEmpty(error))
            return "unknown-error";
        String value = error.replaceAll("(?i)(authorization|token|password)\\s*[:=]\\s*[^\\s,;]+", "$1=<redacted>");
        value = value.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    public static final class Outcome {
        public final boolean success;
        public final boolean changed;
        public final String error;

        private Outcome(boolean success, boolean changed, String error) {
            this.success = success;
            this.changed = changed;
            this.error = error;
        }

        static Outcome verified(boolean changed) {
            return new Outcome(true, changed, null);
        }

        static Outcome failed(String error) {
            return new Outcome(false, false, error);
        }
    }
}
