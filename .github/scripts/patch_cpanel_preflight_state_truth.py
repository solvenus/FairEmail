from pathlib import Path

P = Path('app/src/main/java/eu/faircode/email/AliasBurnManager.java')
text = P.read_text()


def replace_once(old, new, label):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, found {count}')
    text = text.replace(old, new, 1)

replace_once(
'''        try {
            AliasServerActuator.Result result = actuator.burn(alias, reason);
            persistSnapshot(dao, accountUuid, alias, result.routeSnapshot);
            if (result.verified) {
                dao.markSmtpRejectVerified(accountUuid, alias, System.currentTimeMillis());
                return Outcome.verified(result.changed);
            }

            String error = sanitizeError(result.error);
            dao.markSmtpRejectFailed(accountUuid, alias, error);
            return Outcome.failed(error);
''',
'''        try {
            AliasServerActuator.Result result = actuator.burn(alias, reason);
            if (!result.verified && isUnsafePreflight(result.error)) {
                // No server mutation happened. This is a user-veto decision
                // point, not an SMTP failure and must not pollute lifecycle state.
                dao.clearSmtpReject(accountUuid, alias);
                return Outcome.failed(sanitizeError(result.error));
            }

            persistSnapshot(dao, accountUuid, alias, result.routeSnapshot);
            if (result.verified) {
                dao.markSmtpRejectVerified(accountUuid, alias, System.currentTimeMillis());
                return Outcome.verified(result.changed);
            }

            String error = sanitizeError(result.error);
            dao.markSmtpRejectFailed(accountUuid, alias, error);
            return Outcome.failed(error);
''',
'burn-preflight-state')

replace_once(
'''        dao.markSmtpRestorePending(accountUuid, alias);
        try {
            AliasServerActuator.Result result = actuator.restore(alias, entity.smtp_route_snapshot);
            if (result.verified) {
                dao.clearSmtpReject(accountUuid, alias);
                // Restore changes physical SMTP acceptance only. Semantic alias
                // state stays exactly as the user/learning layer left it.
                return Outcome.verified(result.changed);
            }

            String error = sanitizeError(result.error);
            dao.markSmtpRejectFailed(accountUuid, alias, error);
            return Outcome.failed(error);
''',
'''        Long previouslyVerifiedAt = entity.smtp_reject_verified_at;
        dao.markSmtpRestorePending(accountUuid, alias);
        try {
            AliasServerActuator.Result result = actuator.restore(alias, entity.smtp_route_snapshot);
            if (result.verified) {
                dao.clearSmtpReject(accountUuid, alias);
                // Restore changes physical SMTP acceptance only. Semantic alias
                // state stays exactly as the user/learning layer left it.
                return Outcome.verified(result.changed);
            }

            if (isUnsafeRestorePreflight(result.error)) {
                // Restore was refused before mutation because the original raw
                // route cannot be reproduced. The server is still hard-dead,
                // so preserve VERIFIED instead of inventing a server failure.
                dao.markSmtpRejectVerified(accountUuid, alias,
                        previouslyVerifiedAt == null
                                ? System.currentTimeMillis() : previouslyVerifiedAt);
                return Outcome.failed(sanitizeError(result.error));
            }

            String error = sanitizeError(result.error);
            dao.markSmtpRejectFailed(accountUuid, alias, error);
            return Outcome.failed(error);
''',
'restore-preflight-state')

needle = '''    /** Avoid accidentally persisting credentials or giant remote response bodies. */
    private static String sanitizeError(String error) {
'''
replacement = '''    private static boolean isUnsafePreflight(String error) {
        return error != null && error.startsWith("unsafe-required:");
    }

    private static boolean isUnsafeRestorePreflight(String error) {
        return error != null && error.startsWith("restore-unsafe-route:");
    }

    /** Avoid accidentally persisting credentials or giant remote response bodies. */
    private static String sanitizeError(String error) {
'''
replace_once(needle, replacement, 'preflight-classifiers')

P.write_text(text)
print('cPanel preflight lifecycle truth patch applied')
