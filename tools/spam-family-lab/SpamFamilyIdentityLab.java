package eu.faircode.email;

public final class SpamFamilyIdentityLab {
    private SpamFamilyIdentityLab() {
    }

    public static void main(String[] args) {
        SpamFamilyIdentity.Identity base = SpamFamilyIdentity.fromRaw(
                "Premium Tracker", "Track Anything From Your Phone");
        SpamFamilyIdentity.Identity normalized = SpamFamilyIdentity.fromRaw(
                "  PREMIUM   TRACKER  ", "  Track   Anything From Your Phone  ");
        SpamFamilyIdentity.Identity otherSubject = SpamFamilyIdentity.fromRaw(
                "Premium Tracker", "Never Lose Your Keys Again");
        SpamFamilyIdentity.Identity otherSender = SpamFamilyIdentity.fromRaw(
                "Other Brand", "Track Anything From Your Phone");

        require(base != null, "base identity");
        require(normalized != null, "normalized identity");
        require(base.key.equals(normalized.key),
                "case and whitespace must normalize to one family");
        require(otherSubject != null && !base.key.equals(otherSubject.key),
                "different subject must be a different family");
        require(otherSender != null && !base.key.equals(otherSender.key),
                "different sender display name must be a different family");
        require(SpamFamilyIdentity.fromRaw(null, "Subject") == null,
                "missing sender name cannot define exact family");
        require(SpamFamilyIdentity.fromRaw("Sender", null) == null,
                "missing subject cannot define exact family");
        require(SpamFamilyIdentity.fromRaw("Sender", "   ") == null,
                "blank subject cannot define exact family");

        String isolatedA = SpamFamilyIdentity.isolatedMessageKey(101L);
        String isolatedB = SpamFamilyIdentity.isolatedMessageKey(102L);
        require(isolatedA != null && isolatedB != null && !isolatedA.equals(isolatedB),
                "incomplete identities must be isolated per message");
        require(!isolatedA.equals(base.key),
                "isolated fallback key must never collide with normal exact identity");
        require(SpamFamilyIdentity.isolatedMessageKey(0L) == null,
                "invalid message id cannot define isolated family");

        String key = SpamFamilyIdentity.metaKey("account-1", base.key);
        require(key != null && key.contains("account-1") && key.endsWith(base.key),
                "mapping key must be account-scoped");

        System.out.println("PASS: exact spam family identity invariants");
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
