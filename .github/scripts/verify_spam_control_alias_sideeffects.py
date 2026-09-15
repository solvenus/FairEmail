#!/usr/bin/env python3
"""Regression contract for Spam Control's canonical-message + alias-enrichment split.

The canonical spam_message row owns message truth.  When an alias delivery exists,
all legacy alias-side effects must still happen; missing Envelope-To must not block
message learning.  This contract exists specifically to stop future refactors from
'cleaning up' the old behavior into a regression.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INTEL = (ROOT / 'app/src/main/java/eu/faircode/email/SpamIntelligence.java').read_text(encoding='utf-8')
ALIAS = (ROOT / 'app/src/main/java/eu/faircode/email/SpamAliasStore.java').read_text(encoding='utf-8')


def require(cond: bool, msg: str) -> None:
    if not cond:
        raise SystemExit('FAIL: ' + msg)


# Canonical message truth must exist independently of alias enrichment.
require('DaoSpamMessage messageDao = intelligence.message();' in INTEL,
        'SpamIntelligence must use canonical message DAO')
require('EntitySpamMessage beforeMessage = messageDao.get(account.uuid, message.id);' in INTEL,
        'setLabel must read canonical pre-state')
require('boolean messageChanged = SpamMessageStore.setLabel(' in INTEL,
        'message label must be written through canonical SpamMessageStore')

# Alias is optional: lack of delivery must not cancel the message action.
require('EntityAliasDelivery beforeAlias = dao.getDelivery(account.uuid, message.id);' in INTEL,
        'alias pre-state must be optional enrichment')
require('boolean aliasChanged = beforeAlias != null && SpamAliasStore.setLabel(' in INTEL,
        'legacy alias label/counters must mirror only when delivery exists')
require('if (!messageChanged && !aliasChanged)' in INTEL,
        'message truth must be able to change even when alias mirror is absent')

# Legacy side effects must survive when alias exists.
for token in [
    'SpamControlPolicy.markAliasCompromised(context)',
    'AliasCompromisePolicy.Decision compromise',
    'dao.markCompromised(account.uuid, after.address)',
    'AliasCompromiseReviewStore.markReviewedHealthy(context, currentAlias)',
    'refreshAssessment(context, account, message, false)',
]:
    require(token in INTEL, 'missing legacy alias side effect: ' + token)

# The known-good compromise adapter contract from the phone checkpoint.
for token in [
    'input.explicitSpam = true',
    'input.trafficSuspicious',
    'traffic.senderDomain',
    'traffic.serviceDomain',
    'traffic.trustedDomains',
    'input.aliasHam = traffic.aliasHam',
    'AliasCompromisePolicy.decide(input)',
]:
    require(token in INTEL, 'compromise adapter contract lost: ' + token)
require('AliasDomainAffinity.isTrustedSender' not in INTEL,
        'forbidden invented trusted-sender shortcut returned')

# Exact family state is message-level and must refresh even without alias.
require('refreshMessageFamilyState(context, account, message, false);' in INTEL,
        'canonical family refresh missing after label action')

# Protect the old atomic alias bookkeeping itself.  A refactor may call this
# conditionally, but must not strip what it does when an alias is available.
for token in [
    'dao.adjustLabels(account, delivery.address, spamDelta, hamDelta, delivery.received)',
    'changeFamilyCount(dao, account, delivery.address, oldFamily, -1)',
    'changeFamilyCount(dao, account, delivery.address, familyId, 1)',
    'dao.setDeliveryLabel(account, messageId, label,',
]:
    require(token in ALIAS, 'SpamAliasStore.setLabel side effect lost: ' + token)

print('PASS: canonical message truth is independent of alias availability')
print('PASS: legacy alias label/family counters remain mirrored when alias exists')
print('PASS: alias compromise policy still receives the historical evidence set')
