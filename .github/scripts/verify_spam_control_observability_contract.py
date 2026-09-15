#!/usr/bin/env python3
"""Regression contract for Spam Control observability.

A failure that cannot explain what it attempted and what it received is itself a
product failure. This protects the Terminal/scan/cPanel instrumentation added
after the real-device bootstrap and UAPI failures.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCAN = (ROOT / 'app/src/main/java/eu/faircode/email/SpamHistoricalScanner.java').read_text(encoding='utf-8')
UI = (ROOT / 'app/src/main/java/eu/faircode/email/ActivitySpamControl.java').read_text(encoding='utf-8')
LOG = (ROOT / 'app/src/main/java/eu/faircode/email/SpamControlLog.java').read_text(encoding='utf-8')
CPANEL = (ROOT / 'app/src/main/java/eu/faircode/email/CpanelAliasActuator.java').read_text(encoding='utf-8')


def require(cond: bool, msg: str) -> None:
    if not cond:
        raise SystemExit('FAIL: ' + msg)


# Scanner ordering: canonical indexing must happen before Envelope-To branching.
observe_pos = SCAN.find('SpamMessageStore.observe(app, account.uuid, message.id')
envelope_pos = SCAN.find('if (message.deliveredto == null)')
require(observe_pos >= 0 and envelope_pos > observe_pos,
        'scanner must index message before checking missing Envelope-To')
require('missingEnvelope++' in SCAN,
        'scanner must count missing Envelope-To explicitly')
require('SpamIntelligence.refreshMessageFamilyState(app, account, message, false)' in SCAN,
        'aliasless messages must still receive family-state refresh')

# Scanner must emit a useful operational trace, not a single success/failure line.
for token in [
    'SpamControlLog.i(app, "SCAN",',
    'START account=',
    '< PAGE afterMessageId=',
    'SpamControlLog.t(app, "SCAN",',
    'INDEX message=',
    'DONE examined=',
    'FAILED examined=',
    'noEnvelope=',
    'missingFolder=',
]:
    require(token in SCAN, 'scanner observability lost: ' + token)

# UI before/after numbers must come from canonical message state, not alias ledger.
require('DaoSpamMessage messageDao = intelligence.message();' in UI,
        'historical scan UI must use canonical message DAO')
require('messageDao.countReviewQueue(account.uuid, includeReviewed)' in UI,
        'historical scan review before/after must use canonical queue count')
require('messageDao.count(account.uuid)' in UI,
        'historical scan indexed before/after must use canonical message count')
require('dao.countReviewQueue(' not in UI,
        'historical scan UI regressed to alias-ledger review count')
for token in [
    'UI RESULT sources=',
    'review=" + beforeReview + "->" + afterReview',
    'indexed=" + beforeIndexed + "->" + afterIndexed',
    'noEnvelope=" + result.skippedNoEnvelope',
]:
    require(token in UI, 'scan UI result telemetry lost: ' + token)

# Terminal must remain first-class navigation and expose operational controls.
for token in [
    'TERMINAL("Terminal")',
    'renderTerminal()',
    'SpamControlLog.read(getApplicationContext())',
    'SpamControlLog.clear(getApplicationContext())',
]:
    require(token in UI, 'Terminal contract lost: ' + token)

# Persistent log must support all levels and redact secrets.
for token in [
    'append(context, "ERROR"',
    'append(context, "WARN"',
    'append(context, "INFO"',
    'append(context, "DEBUG"',
    'append(context, "TRACE"',
    '<redacted>',
    'MAX_BYTES',
]:
    require(token in LOG, 'diagnostic log capability lost: ' + token)

# cPanel must capture response shape before parser failure INSIDE call().
call_start = CPANEL.find('private JSONObject call(String module, String function, Map<String, String> args)')
call_end = CPANEL.find('\n    private static String topLevelKeys', call_start)
require(call_start >= 0 and call_end > call_start, 'cPanel call() method boundaries not found')
CALL = CPANEL[call_start:call_end]
request = CALL.find('SpamControlLog.d(context, "CPANEL",\n                "> GET "')
response = CALL.find('SpamControlLog.d(context, "CPANEL",\n                    "< HTTP "')
shape_check = CALL.find('JSONObject result = root.optJSONObject("result")')
require(request >= 0, 'cPanel request endpoint logging missing')
require(response >= 0 and shape_check > response,
        'cPanel HTTP/shape telemetry must happen before UAPI result validation')
for token in [
    'final=" + connection.getURL()',
    'contentType=" + connection.getContentType()',
    'topLevel=" + topLevelKeys(root)',
    'SpamControlLog.t(context, "CPANEL",',
    '< BODY " + SpamControlLog.sanitize(body)',
]:
    require(token in CALL, 'cPanel call telemetry lost: ' + token)
for token in [
    'root.has("cpanelresult")',
    'root.has("metadata") && root.has("data")',
    'cPanel UAPI response missing result. endpoint=',
]:
    require(token in CPANEL, 'cPanel diagnostic shape contract lost: ' + token)

print('PASS: scanner indexes before Envelope-To branching and logs every stage')
print('PASS: scan UI reports canonical before/after counts')
print('PASS: Terminal remains persistent/filterable observability surface')
print('PASS: cPanel logs response shape before parser failure')
