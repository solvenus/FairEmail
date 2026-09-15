package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Builds editable sender-domain suggestions from the alias delivery ledger. */
public final class AliasDomainSuggestions {
    private AliasDomainSuggestions() {
    }

    public static List<Suggestion> get(Context context,
                                       String accountUuid,
                                       String aliasAddress) {
        String alias = AliasRegistry.normalizeAddress(aliasAddress);
        if (context == null || accountUuid == null || alias == null)
            return Collections.emptyList();

        List<TupleAliasDomainStats> stats = SpamIntelligenceDB.getInstance(context)
                .alias().getDomainStats(accountUuid, alias);
        if (stats == null || stats.isEmpty())
            return Collections.emptyList();

        String token = AliasDomainAffinity.serviceToken(alias);
        List<Suggestion> result = new ArrayList<>();
        for (TupleAliasDomainStats stat : stats) {
            if (stat == null || stat.domain == null)
                continue;

            AliasDomainSuggestionScorer.Input input = new AliasDomainSuggestionScorer.Input();
            input.messages = stat.messages;
            input.unsubscribe = stat.unsubscribe;
            input.ham = stat.ham;
            input.spam = stat.spam;
            input.aliasDomainSimilarity = AliasDomainAffinity.similarity(token, stat.domain);
            input.exactAliasDomainMatch =
                    AliasDomainAffinity.inferServiceDomain(alias, stat.domain) != null;

            result.add(new Suggestion(
                    stat.domain,
                    stat.messages,
                    stat.unsubscribe,
                    stat.ham,
                    stat.spam,
                    AliasDomainSuggestionScorer.score(input)));
        }

        Collections.sort(result, new Comparator<Suggestion>() {
            @Override
            public int compare(Suggestion a, Suggestion b) {
                int recommended = Boolean.compare(b.score.recommended, a.score.recommended);
                if (recommended != 0)
                    return recommended;
                int confidence = Double.compare(b.score.confidence, a.score.confidence);
                if (confidence != 0)
                    return confidence;
                return a.domain.compareToIgnoreCase(b.domain);
            }
        });
        return Collections.unmodifiableList(result);
    }

    public static final class Suggestion {
        public final String domain;
        public final int messages;
        public final int unsubscribe;
        public final int ham;
        public final int spam;
        public final AliasDomainSuggestionScorer.Suggestion score;

        Suggestion(String domain,
                   int messages,
                   int unsubscribe,
                   int ham,
                   int spam,
                   AliasDomainSuggestionScorer.Suggestion score) {
            this.domain = domain;
            this.messages = messages;
            this.unsubscribe = unsubscribe;
            this.ham = ham;
            this.spam = spam;
            this.score = score;
        }
    }
}
