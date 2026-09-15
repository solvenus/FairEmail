package eu.faircode.email;

/** Zero-dependency round-trip checks for persisted spam-family fingerprints. */
public final class SpamFamilySerializationLab {
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    public static void main(String[] args) {
        SpamFamilyFingerprint original = SpamFamilyFingerprint.fromRaw(
                "randomized@sender.invalid",
                "Offer Team",
                "Ny løsning 2026",
                "Dette er en test med https://example.invalid/click/123456 og produkt 42.",
                "<html><body><table><tr><td><a href='https://example.invalid/click/123456'>Se tilbud</a></td></tr></table></body></html>");

        byte[] encoded = original.toBytes();
        SpamFamilyFingerprint decoded = SpamFamilyFingerprint.fromBytes(encoded);

        require(original.text.equals(decoded.text), "text hashes must round-trip losslessly");
        require(original.structure.equals(decoded.structure), "structure hashes must round-trip losslessly");
        require(original.links.equals(decoded.links), "link hashes must round-trip losslessly");
        require(original.sender.equals(decoded.sender), "sender hashes must round-trip losslessly");
        require(java.util.Arrays.equals(encoded, decoded.toBytes()),
                "serialization must be deterministic across decode/encode");

        boolean corruptRejected = false;
        try {
            SpamFamilyFingerprint.fromBytes(new byte[]{0, 1, 2, 3});
        } catch (IllegalArgumentException expected) {
            corruptRejected = true;
        }
        require(corruptRejected, "corrupt fingerprint payload must fail closed");

        System.out.println("PASS fingerprint-bytes=" + encoded.length +
                " evidence=" + original.evidenceCount());
    }
}
