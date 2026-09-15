#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


repository = Path("app/src/main/java/eu/faircode/email/SpamFamilyLabRepository.java")
activity = Path("app/src/main/java/eu/faircode/email/ActivitySpamFamilyLab.java")

replace_once(
    repository,
    '''        public final Double aliasSpamSupport;
        public final Double aliasHamSupport;
        public final String aliasVerdict;
        public final String aliasReasons;

        public final Double score;''',
    '''        public final Double aliasSpamSupport;
        public final Double aliasHamSupport;
        public final String aliasVerdict;
        public final String aliasReasons;

        public final double overallSpamSupport;
        public final double overallHamSupport;
        public final SpamDecisionScorer.Verdict overallVerdict;
        public final List<String> overallReasons;

        public final Double score;''',
    "candidate combined decision fields",
)

replace_once(
    repository,
    '''                          Double aliasSpamSupport,
                          Double aliasHamSupport,
                          String aliasVerdict,
                          String aliasReasons,
                          Double score,''',
    '''                          Double aliasSpamSupport,
                          Double aliasHamSupport,
                          String aliasVerdict,
                          String aliasReasons,
                          double overallSpamSupport,
                          double overallHamSupport,
                          SpamDecisionScorer.Verdict overallVerdict,
                          List<String> overallReasons,
                          Double score,''',
    "candidate combined decision constructor args",
)

replace_once(
    repository,
    '''            this.aliasSpamSupport = aliasSpamSupport;
            this.aliasHamSupport = aliasHamSupport;
            this.aliasVerdict = aliasVerdict;
            this.aliasReasons = aliasReasons;
            this.score = score;''',
    '''            this.aliasSpamSupport = aliasSpamSupport;
            this.aliasHamSupport = aliasHamSupport;
            this.aliasVerdict = aliasVerdict;
            this.aliasReasons = aliasReasons;
            this.overallSpamSupport = overallSpamSupport;
            this.overallHamSupport = overallHamSupport;
            this.overallVerdict = overallVerdict;
            this.overallReasons = overallReasons;
            this.score = score;''',
    "candidate combined decision assignments",
)

replace_once(
    repository,
    '''            int aliasState = alias == null || alias.state == null
                    ? EntityAlias.STATE_ACTIVE : alias.state;

            return new Candidate(''',
    '''            int aliasState = alias == null || alias.state == null
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

            return new Candidate(''',
    "derive combined spam decision from ledger truth",
)

replace_once(
    repository,
    '''                    delivery.spam_support,
                    delivery.ham_support,
                    delivery.traffic_verdict,
                    delivery.traffic_reasons,
                    selectedScore,''',
    '''                    delivery.spam_support,
                    delivery.ham_support,
                    delivery.traffic_verdict,
                    delivery.traffic_reasons,
                    overall.spamSupport,
                    overall.hamSupport,
                    overall.verdict,
                    overall.reasons,
                    selectedScore,''',
    "pass combined spam decision into candidate",
)

replace_once(
    activity,
    '''                .append("Aliasvurdering: ").append(empty(candidate.aliasVerdict, "ukjent")).append('\\n')
                .append("Aliasårsaker: ").append(empty(candidate.aliasReasons, "ingen")).append("\\n\\n")
                .append("Spamgruppelikhet: ").append(percent(candidate.score)).append('\\n')''',
    '''                .append("Aliasvurdering: ").append(empty(candidate.aliasVerdict, "ukjent")).append('\\n')
                .append("Aliasårsaker: ").append(empty(candidate.aliasReasons, "ingen")).append("\\n\\n")
                .append("SAMLET VURDERING\\n")
                .append("Spamstøtte: ").append(percent(candidate.overallSpamSupport)).append('\\n')
                .append("Legitimitetsstøtte: ").append(percent(candidate.overallHamSupport)).append('\\n')
                .append("Resultat: ").append(candidate.overallVerdict).append('\\n')
                .append("Årsaker: ").append(candidate.overallReasons.isEmpty()
                        ? "ingen" : android.text.TextUtils.join(", ", candidate.overallReasons))
                .append("\\n\\n")
                .append("Spamgruppelikhet: ").append(percent(candidate.score)).append('\\n')''',
    "show combined decision in technical details",
)

replace_once(
    activity,
    '''    private String messageState(SpamFamilyLabRepository.Candidate candidate) {
        if (candidate.explicitHam)
            return "✓ Du har merket denne som IKKE SPAM";
        if (candidate.confirmedThisFamily)
            return "✓ Bekreftet spam i denne gruppen";
        if (candidate.confirmedFamilyId != null)
            return "✓ Bekreftet spam i en annen spamgruppe";
        if (candidate.strong)
            return "⚠ Sterkt spamtreff · trenger din kontroll";
        return "Mulig spamtreff · trenger din kontroll";
    }''',
    '''    private String messageState(SpamFamilyLabRepository.Candidate candidate) {
        if (candidate.explicitHam)
            return "✓ Du har merket denne som IKKE SPAM";
        if (candidate.confirmedThisFamily)
            return "✓ Bekreftet spam i denne gruppen";
        if (candidate.confirmedFamilyId != null)
            return "✓ Bekreftet spam i en annen spamgruppe";
        if (candidate.overallVerdict == SpamDecisionScorer.Verdict.SUSPICIOUS)
            return "⚠ Høy spamrisiko · trenger din kontroll";
        if (candidate.overallVerdict == SpamDecisionScorer.Verdict.LIKELY_LEGIT)
            return "✓ Sterke legitimitetssignaler · kontroller før du endrer";
        return "Trenger din vurdering";
    }''',
    "human combined decision status",
)

print("PASS: integrated combined alias and spam-group decision into Spam Control")
