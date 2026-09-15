package eu.faircode.email;

/**
 * Tiny zero-dependency replay harness for SpamFamilyEngine.
 * The two spam fixtures are transcribed from real FairEmail screenshots supplied during development.
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
        require("sd_service@example.org".equals(alias),
                "alias normalization must survive display-name/address syntax");

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
        System.out.println("PASS family=" + first.familyId + " count=" + model.familyCount());
    }
}
