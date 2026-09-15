package eu.faircode.email;

/**
 * Tiny zero-dependency replay harness for SpamFamilyEngine and alias evidence.
 * Spam fixtures are sanitized derivatives of examples supplied during development.
 */
public final class SpamFamilyLab {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static SpamFamilyFingerprint akusoli() {
        String html = "<html><body><table><tr><td>" +
                "<h2>Komfort og støtte – hver dag.</h2>" +
                "<p>Prøv Akusoli innleggssåler med innebygd massasje.</p>" +
                "<img src='cid:product'>" +
                "<p><a href='https://offer-one.example/click/847291'>Føttene dine fortjener en pause &gt;&gt;&gt;</a></p>" +
                "</td></tr></table></body></html>";
        return SpamFamilyFingerprint.fromRaw(
                "ycruvjf@pure-host.xin",
                "Akusoli",
                "Hjelper ikke vanlige innleggssåler lenger?",
                "Komfort og støtte – hver dag. Prøv Akusoli innleggssåler med innebygd massasje. " +
                        "Føttene dine fortjener en pause >>>",
                html);
    }

    private static SpamFamilyFingerprint wifiBooster() {
        String html = "<html><body><table><tr><td>" +
                "<h2>Svakt Wi-Fi er skikkelig irriterende.</h2>" +
                "<p>Wi-Fi forsvinner akkurat når du trenger det mest. Én liten forsterker. Sterkt signal i hele hjemmet.</p>" +
                "<img src='cid:product'>" +
                "<p>Bare koble den til og bruk den.</p>" +
                "<p><a href='https://offer-two.example/go/991144'>WiFi Boost Pro &gt;</a></p>" +
                "</td></tr></table></body></html>";
        return SpamFamilyFingerprint.fromRaw(
                "randomized-sender@another-host.example",
                "Wifi Booster",
                "Svakt Wi-Fi er skikkelig irriterende.",
                "Wi-Fi forsvinner akkurat når du trenger det mest. Én liten forsterker. " +
                        "Sterkt signal i hele hjemmet. Bare koble den til og bruk den. WiFi Boost Pro >",
                html);
    }

    private static SpamFamilyFingerprint legitimate() {
        return SpamFamilyFingerprint.fromRaw(
                "kari@example.org",
                "Kari",
                "Møte torsdag",
                "Hei! Kan vi flytte møtet til torsdag klokken 14? Jeg sender referatet etterpå.",
                "<html><body><p>Hei!</p><p>Kan vi flytte møtet til torsdag klokken 14?</p>" +
                        "<p>Jeg sender referatet etterpå.</p></body></html>");
    }

    private static SpamFamilyFingerprint mutatedAkusoli() {
        String html = "<html><body><table><tr><td>" +
                "<h2>Mykere steg i hverdagen.</h2>" +
                "<p>Oppdag FootRelief komfortsåler for lange dager.</p>" +
                "<img src='cid:new-product'>" +
                "<p><a href='https://third-domain.invalid/r/ABCD123456789012'>Se dagens løsning &gt;</a></p>" +
                "</td></tr></table></body></html>";
        return SpamFamilyFingerprint.fromRaw(
                "pkxvnmq@fresh-domain.invalid",
                "FootRelief",
                "Tunge føtter etter en lang dag?",
                "Mykere steg i hverdagen. Oppdag FootRelief komfortsåler for lange dager. Se dagens løsning >",
                html);
    }

    private static void aliasReputationChecks() {
        String alias = SpamAliasReputation.normalizeAddress(
                "Example Service <SD_Service@Example.org>");
        require("SD_Service@example.org".equals(alias),
                "alias normalization must preserve local-part case and normalize the domain");

        SpamAliasReputation.Model reputation = new SpamAliasReputation.Model();
        for (int i = 0; i < 12; i++)
            reputation.observe(alias, "expected.example", false);
        for (int i = 0; i < 6; i++)
            reputation.observe(alias, "abuse.example", true);

        SpamAliasReputation.Reputation known = reputation.get(alias, "expected.example");
        SpamAliasReputation.Reputation newSender = reputation.get(alias, "never-seen.example");
        SpamAliasReputation.Reputation abusive = reputation.get(alias, "abuse.example");

        require(known.senderHam == 12,
                "known sender must retain legitimate history");
        require(abusive.senderSpam == 6,
                "abusive sender must retain spam history");
        require(newSender.unexpectedSender > known.unexpectedSender + 0.30,
                "unfamiliar sender should be anomalous on an established alias");
        require(reputation.get(alias).effectiveRisk > 0.10,
                "repeated spam should lift alias risk above the prior");
    }

    private static void aliasTrafficChecks() {
        // Ubuy-like legitimate newsletter. Even when the alias itself has some
        // spam exposure, exact domain agreement + legitimate history +
        // unsubscribe must keep this message on the legitimate side.
        AliasTrafficScorer.Input ubuy = new AliasTrafficScorer.Input();
        ubuy.senderDomainKnown = true;
        ubuy.serviceDomainKnown = true;
        ubuy.serviceDomainMatch = true;
        ubuy.trustedDomainsConfigured = true;
        ubuy.trustedDomainMatch = true;
        ubuy.aliasDomainSimilarity = 1.0;
        ubuy.aliasSpamRisk = 0.35;
        ubuy.senderSpamConfidence = 0.0;
        ubuy.senderHamConfidence = 0.85;
        ubuy.unexpectedSender = 0.05;
        ubuy.hasUnsubscribe = true;
        AliasTrafficScorer.Assessment legit = AliasTrafficScorer.assess(ubuy);
        require(legit.verdict == AliasTrafficScorer.Verdict.LIKELY_LEGIT,
                "matching service newsletter with unsubscribe must be strongly legitimate");
        require(legit.hamSupport > 0.90 && legit.net < -0.65,
                "Ubuy-style hard negative must beat generic alias spam exposure by a large margin");

        // Leaked service alias hit by an unrelated sender after the expected
        // domain has been established.
        AliasTrafficScorer.Input leaked = new AliasTrafficScorer.Input();
        leaked.senderDomainKnown = true;
        leaked.serviceDomainKnown = true;
        leaked.serviceDomainMatch = false;
        leaked.trustedDomainsConfigured = true;
        leaked.trustedDomainMatch = false;
        leaked.aliasDomainSimilarity = 0.0;
        leaked.aliasSpamRisk = 0.75;
        leaked.senderSpamConfidence = 0.80;
        leaked.senderHamConfidence = 0.0;
        leaked.unexpectedSender = 0.90;
        leaked.hasUnsubscribe = false;
        AliasTrafficScorer.Assessment suspicious = AliasTrafficScorer.assess(leaked);
        require(suspicious.verdict == AliasTrafficScorer.Verdict.SUSPICIOUS,
                "established alias plus unrelated domain must be suspicious");
        require(suspicious.spamSupport > 0.95,
                "independent mismatch and spam-history signals should compound strongly");

        // A repeatedly abusive sender domain is meaningful even before the user
        // assigns an explicit service domain to the alias.
        AliasTrafficScorer.Input knownAbuse = new AliasTrafficScorer.Input();
        knownAbuse.senderDomainKnown = true;
        knownAbuse.aliasSpamRisk = 0.45;
        knownAbuse.senderSpamConfidence = 0.90;
        AliasTrafficScorer.Assessment abusive = AliasTrafficScorer.assess(knownAbuse);
        require(abusive.verdict == AliasTrafficScorer.Verdict.SUSPICIOUS,
                "repeated sender-domain spam history should independently become suspicious");

        // A brand-new alias with no learned/explicit domain relationship must
        // not be convicted from lack of text overlap alone.
        AliasTrafficScorer.Input unknown = new AliasTrafficScorer.Input();
        unknown.senderDomainKnown = true;
        unknown.aliasDomainSimilarity = 0.0;
        AliasTrafficScorer.Assessment coldStart = AliasTrafficScorer.assess(unknown);
        require(coldStart.verdict == AliasTrafficScorer.Verdict.UNKNOWN,
                "cold-start domain mismatch must not become an automatic spam rule");
        require(coldStart.spamSupport < 0.20,
                "cold-start must remain low confidence");

        System.out.println("Alias legit      : " + legit);
        System.out.println("Alias suspicious : " + suspicious);
        System.out.println("Alias known abuse: " + abusive);
        System.out.println("Alias cold start : " + coldStart);
    }

    public static void main(String[] args) {
        SpamFamilyFingerprint a = akusoli();
        SpamFamilyFingerprint b = wifiBooster();
        SpamFamilyFingerprint c = legitimate();
        SpamFamilyFingerprint d = mutatedAkusoli();

        SpamFamilyEngine.Score ab = SpamFamilyEngine.compare(a, b);
        SpamFamilyEngine.Score ac = SpamFamilyEngine.compare(a, c);
        SpamFamilyEngine.Score ad = SpamFamilyEngine.compare(a, d);

        System.out.println("Akusoli vs Wifi Booster: " + ab);
        System.out.println("Akusoli vs legitimate : " + ac);
        System.out.println("Akusoli vs mutation   : " + ad);

        require(ab.value > ac.value + 0.20,
                "real spam pair should be much closer than legitimate mail");
        require(ad.value > ac.value + 0.20,
                "mutated campaign should remain much closer than legitimate mail");

        SpamFamilyEngine.Model model = new SpamFamilyEngine.Model(0.50, 0.56, 16);
        SpamFamilyEngine.Assignment first = model.learnSpam(a);
        require(first.created, "first spam must create a family");

        SpamFamilyEngine.Assignment second = model.learnSpam(b);
        require(!second.created, "real spam pair should join the learned family");
        require(first.familyId == second.familyId, "real spam pair should share family id");

        SpamFamilyEngine.Match mutation = model.match(d);
        require(mutation.spamLike, "mutated campaign should be recognized");
        require(mutation.familyId != null && mutation.familyId == first.familyId,
                "mutation should map back to the learned spam family");

        SpamFamilyEngine.Match ham = model.match(c);
        require(!ham.spamLike, "legitimate mail must not match this spam family");

        aliasReputationChecks();
        aliasTrafficChecks();
        System.out.println("PASS family=" + first.familyId + " count=" + model.familyCount());
    }
}
