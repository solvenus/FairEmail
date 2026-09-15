#!/usr/bin/env python3
"""Regression contract for Spam Control reset/undo semantics.

Reset clears learned/derived state, never the canonical message index or mail.
Undo must capture every learned state owner and must be account-local.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SNAP = (ROOT / 'app/src/main/java/eu/faircode/email/SpamLearningSnapshot.java').read_text(encoding='utf-8')
UNDO = (ROOT / 'app/src/main/java/eu/faircode/email/SpamUndoManager.java').read_text(encoding='utf-8')
DAO = (ROOT / 'app/src/main/java/eu/faircode/email/DaoSpamSnapshot.java').read_text(encoding='utf-8')
MSGDAO = (ROOT / 'app/src/main/java/eu/faircode/email/DaoSpamMessage.java').read_text(encoding='utf-8')


def require(cond: bool, msg: str) -> None:
    if not cond:
        raise SystemExit('FAIL: ' + msg)


# One-message undo must include canonical truth plus optional alias enrichment.
for token in [
    'root.put("families"',
    'root.put("exemplars"',
    'root.put("exclusions"',
    'root.put("meta"',
    'root.put("compromise_meta"',
    'root.put("spam_message"',
    'root.put("delivery"',
    'root.put("alias"',
]:
    require(token in SNAP, 'single-message snapshot owner missing: ' + token)

# Bulk/account undo must capture all state that one action can fan out across.
for token in [
    'root.put("spam_messages", spamMessages)',
    'root.put("deliveries", deliveries)',
    'root.put("aliases", aliases)',
    'root.put("full_account_learning", true)',
]:
    require(token in SNAP, 'full-account snapshot owner missing: ' + token)

# Exact identity and compromise-review state are first-class learned state.
require('SpamFamilyIdentity.accountMetaPrefix(account)' in SNAP,
        'exact identity mappings missing from account snapshot')
require('AliasCompromiseReviewStore.accountMetaPrefix(account)' in SNAP,
        'compromise-review metadata missing from account snapshot')
require('SpamFamilyIdentity.globalMetaPrefix()' in SNAP,
        'exact identity mappings missing from global snapshot')
require('AliasCompromiseReviewStore.globalMetaPrefix()' in SNAP,
        'compromise-review metadata missing from global snapshot')

# Reset clears learning in place.  It must never delete the canonical index.
for token in [
    'dao.resetAllSpamMessageLearning()',
    'dao.resetAllDeliveryLearning()',
    'dao.resetAllAliasLearning()',
    'dao.deleteAllFamilies()',
    'dao.deleteAllExemplars()',
    'dao.deleteAllExclusions()',
]:
    require(token in SNAP, 'reset contract missing: ' + token)
require('deleteAllSpamMessages' not in SNAP,
        'reset must never delete canonical spam_message rows')
require('DELETE FROM spam_message' not in MSGDAO,
        'canonical message DAO must not expose destructive index deletion')

# Undo must restore snapshot before marking history as undone, then rescore.
restore_pos = UNDO.find('SpamLearningSnapshot.restore(app, history.before_json)')
mark_pos = UNDO.find('db.actions().markUndone(history.id, System.currentTimeMillis())')
require(restore_pos >= 0 and mark_pos > restore_pos,
        'undo history must be marked undone only after snapshot restore succeeds')
require('SpamFamilyRescorer.enqueueAllActive(app, account)' in UNDO,
        'undo must rebuild derived predictions after restore')

# Critical cross-account invariant: account A cannot undo account B.
require('.actions().getLatestUndoable(accountUuid.trim())' in UNDO,
        'latest(account) must query account-local history')
require('db.actions().getLatestUndoable(accountUuid.trim())' in UNDO,
        'undoLatest(account) must query account-local history')

# Reset itself must be undoable: capture first, record action, then mutate.
capture = UNDO.find('String before = SpamLearningSnapshot.captureAll(app);')
record = UNDO.find('ACTION_RESET_ALL, "Nullstill all spamlæring", before')
reset = UNDO.find('SpamLearningSnapshot.resetAll(app);')
require(capture >= 0 and record > capture and reset > record,
        'reset must checkpoint all learning before mutation')

# Bulk action must snapshot account-wide state because one Spam decision can
# update multiple messages and aliases.
require('SpamLearningSnapshot.captureAccountAllLearning(' in UNDO,
        'bulk/account actions must use account-wide snapshot')

# Snapshot DAO must expose in-place reset/restore for canonical message learning.
for token in [
    'List<EntitySpamMessage> getSpamMessages',
    'List<EntitySpamMessage> getAllSpamMessages',
    'resetAccountSpamMessageLearning',
    'resetAllSpamMessageLearning',
    'restoreSpamMessageLearning',
]:
    require(token in DAO, 'snapshot DAO canonical message operation missing: ' + token)

print('PASS: reset preserves canonical message index and clears learning only')
print('PASS: snapshots cover exact identity, compromise, message, alias and family state')
print('PASS: undo is account-local and restores before marking history undone')
