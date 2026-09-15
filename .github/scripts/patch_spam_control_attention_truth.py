from pathlib import Path

path = Path('app/src/main/java/eu/faircode/email/ActivitySpamControl.java')
text = path.read_text(encoding='utf-8')


def replace_once(old, new, label):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 match, got {count}')
    text = text.replace(old, new, 1)
    print('patched', label)

replace_once(
'''    private List<SpamNetworkAnalyzer.Network> networks = Collections.emptyList();\n    private List<EntitySpamActionHistory> recentActions = Collections.emptyList();''',
'''    private List<SpamNetworkAnalyzer.Network> networks = Collections.emptyList();\n    private List<EntitySpamActionHistory> recentActions = Collections.emptyList();\n    private SpamControlAttentionStats.Stats attentionStats = SpamControlAttentionStats.EMPTY;''',
'attention state field')

replace_once(
'''            List<SpamNetworkAnalyzer.Network> loadedNetworks;\n            List<EntitySpamActionHistory> loadedActions;''',
'''            List<SpamNetworkAnalyzer.Network> loadedNetworks;\n            List<EntitySpamActionHistory> loadedActions;\n            SpamControlAttentionStats.Stats loadedAttention;''',
'loaded attention declaration')

replace_once(
'''            try {\n                loadedActions = SpamIntelligenceDB.getInstance(getApplicationContext())\n                        .actions().getRecent(account.uuid, 50);\n                if (loadedActions == null)\n                    loadedActions = Collections.emptyList();\n            } catch (Throwable ex) {\n                Log.e(ex);\n                loadedActions = Collections.emptyList();\n            }\n            final List<SpamNetworkAnalyzer.Network> safeNetworks = loadedNetworks;\n            final List<EntitySpamActionHistory> safeActions = loadedActions;''',
'''            try {\n                loadedActions = SpamIntelligenceDB.getInstance(getApplicationContext())\n                        .actions().getRecent(account.uuid, 50);\n                if (loadedActions == null)\n                    loadedActions = Collections.emptyList();\n            } catch (Throwable ex) {\n                Log.e(ex);\n                loadedActions = Collections.emptyList();\n            }\n            try {\n                loadedAttention = SpamControlAttentionStats.compute(\n                        getApplicationContext(), account.uuid);\n            } catch (Throwable ex) {\n                Log.e(ex);\n                loadedAttention = SpamControlAttentionStats.EMPTY;\n            }\n            final List<SpamNetworkAnalyzer.Network> safeNetworks = loadedNetworks;\n            final List<EntitySpamActionHistory> safeActions = loadedActions;\n            final SpamControlAttentionStats.Stats safeAttention = loadedAttention;''',
'compute attention')

replace_once(
'''                networks = safeNetworks;\n                recentActions = safeActions;''',
'''                networks = safeNetworks;\n                recentActions = safeActions;\n                attentionStats = safeAttention;''',
'bind attention')

replace_once(
'''        executor.execute(() -> {\n            List<SpamFamilyLabRepository.Candidate> result;\n            try {\n                result = SpamFamilyLabRepository.getReviewQueue(\n                        getApplicationContext(), account.uuid, REVIEW_LIMIT);\n            } catch (Throwable ex) {\n                Log.e(ex);\n                result = Collections.emptyList();\n            }\n            final List<SpamFamilyLabRepository.Candidate> queue = result;''',
'''        executor.execute(() -> {\n            List<SpamFamilyLabRepository.Candidate> result;\n            int total;\n            try {\n                result = SpamFamilyLabRepository.getReviewQueue(\n                        getApplicationContext(), account.uuid, REVIEW_LIMIT);\n            } catch (Throwable ex) {\n                Log.e(ex);\n                result = Collections.emptyList();\n            }\n            try {\n                total = SpamControlQueueStats.countReview(\n                        getApplicationContext(), account.uuid);\n            } catch (Throwable ex) {\n                Log.e(ex);\n                total = result.size();\n            }\n            final List<SpamFamilyLabRepository.Candidate> queue = result;\n            final int exactTotal = Math.max(queue.size(), total);''',
'exact queue total')

replace_once(
'''                reviewQueue = new ArrayList<>(queue);\n                reviewCount = reviewQueue.size();''',
'''                reviewQueue = new ArrayList<>(queue);\n                reviewCount = exactTotal;''',
'bind exact queue total')

replace_once(
'''                    reviewCount = reviewQueue.size();''',
'''                    reviewCount = Math.max(0, reviewCount - 1);''',
'immediate review decrement')

replace_once(
'''        llPage.addView(statCard("Trenger deg", String.valueOf(reviewCount),\n                "meldinger i arbeidskøen", "Gjennomgå nå",\n                v -> setSection(Section.REVIEW)), matchWrapWithMargin(0, 0, 0, 8));''',
'''        int actualNeeds = reviewCount + attentionStats.actualNeedsBeyondMail();\n        String needsDetail = reviewCount + " meldinger · " +\n                attentionStats.compromiseDecisions + " aliasavgjørelser · " +\n                attentionStats.serverFailures + " serverfeil";\n        llPage.addView(statCard("Trenger deg", String.valueOf(actualNeeds),\n                needsDetail, actualNeeds == 0 ? null :\n                        (reviewCount > 0 ? "Gjennomgå nå" : "Se aliaser"),\n                actualNeeds == 0 ? null :\n                        (reviewCount > 0 ? v -> setSection(Section.REVIEW) : v -> setSection(Section.ALIASES))),\n                matchWrapWithMargin(0, 0, 0, 8));''',
'universal needs stat')

replace_once(
'''        llPage.addView(sectionTitle("Trenger oppmerksomhet"), matchWrap());\n        if (reviewCount > 0)\n            llPage.addView(attentionCard(reviewCount + " meldinger trenger vurdering",\n                    "Spamkontroll har samlet det som faktisk trenger et menneske.",\n                    "Start", v -> setSection(Section.REVIEW)), matchWrapWithMargin(0, 6, 0, 6));\n        if (replacementsMissing > 0)\n            llPage.addView(attentionCard(replacementsMissing + " kompromitterte aliaser mangler replacement",\n                    "Replacement anbefales før SMTP-burn, men du kan overstyre dette.",\n                    "Åpne", v -> setSection(Section.ALIASES)), matchWrapWithMargin(0, 0, 0, 6));\n        if (!SpamControlPolicy.hasCpanelConfig(this))\n            llPage.addView(attentionCard("cPanel er ikke konfigurert",\n                    "Aliasstyring virker lokalt. Server-side hard reject krever cPanel-konfigurasjon.",\n                    "Konfigurer", v -> showCpanelDialog()), matchWrapWithMargin(0, 0, 0, 10));\n        if (reviewCount == 0 && replacementsMissing == 0 && SpamControlPolicy.hasCpanelConfig(this)) {\n            TextView done = bodyText("✓ Ingen ting krever oppmerksomhet akkurat nå.");\n            done.setTypeface(Typeface.DEFAULT, Typeface.BOLD);\n            done.setPadding(0, dp(8), 0, dp(12));\n            llPage.addView(done, matchWrap());\n        }''',
'''        llPage.addView(sectionTitle("Trenger deg"), matchWrap());\n        if (reviewCount > 0)\n            llPage.addView(attentionCard(reviewCount + " meldinger trenger vurdering",\n                    "Dette er faktiske Spam/Ikke spam-beslutninger.",\n                    "Start", v -> setSection(Section.REVIEW)), matchWrapWithMargin(0, 6, 0, 6));\n        if (attentionStats.compromiseDecisions > 0)\n            llPage.addView(attentionCard(attentionStats.compromiseDecisions +\n                            " aliaser trenger kompromissavgjørelse",\n                    "Spam finnes, men appen kan ikke avgjøre alias-lekkasjen uten deg.",\n                    "Se aliaser", v -> setSection(Section.ALIASES)), matchWrapWithMargin(0, 0, 0, 6));\n        if (attentionStats.serverFailures > 0)\n            llPage.addView(attentionCard(attentionStats.serverFailures + " SMTP-operasjoner feilet",\n                    "Serverhandlingen trenger inspeksjon eller nytt forsøk.",\n                    "Se aliaser", v -> setSection(Section.ALIASES)), matchWrapWithMargin(0, 0, 0, 6));\n        if (actualNeeds == 0) {\n            TextView done = bodyText("✓ Ingen uløste beslutninger eller feil akkurat nå.");\n            done.setTypeface(Typeface.DEFAULT, Typeface.BOLD);\n            done.setPadding(0, dp(8), 0, dp(12));\n            llPage.addView(done, matchWrap());\n        }\n\n        int recommendations = attentionStats.recommendations() +\n                (SpamControlPolicy.hasCpanelConfig(this) ? 0 : 1);\n        if (recommendations > 0) {\n            llPage.addView(sectionTitle("Anbefalt"), matchWrapWithMargin(0, 10, 0, 0));\n            if (attentionStats.replacementMissingRecommendations > 0)\n                llPage.addView(attentionCard(attentionStats.replacementMissingRecommendations +\n                                " kompromitterte aliaser mangler replacement",\n                        "Anbefalt før SMTP-burn. Dette sperrer deg ikke.",\n                        "Se aliaser", v -> setSection(Section.ALIASES)), matchWrapWithMargin(0, 6, 0, 6));\n            if (attentionStats.replacementUnverifiedRecommendations > 0)\n                llPage.addView(attentionCard(attentionStats.replacementUnverifiedRecommendations +\n                                " replacements kan verifiseres",\n                        "Verifisering gir mer sikkerhet, men er ikke nødvendig for burn.",\n                        "Se aliaser", v -> setSection(Section.ALIASES)), matchWrapWithMargin(0, 0, 0, 6));\n            if (!SpamControlPolicy.hasCpanelConfig(this))\n                llPage.addView(attentionCard("cPanel er ikke konfigurert",\n                        "Konfigurer dette når du vil bruke server-side SMTP hard reject.",\n                        "Konfigurer", v -> showCpanelDialog()), matchWrapWithMargin(0, 0, 0, 10));\n        }''',
'attention vs recommendations sections')

path.write_text(text, encoding='utf-8')
print('truthful attention/queue patch complete')
