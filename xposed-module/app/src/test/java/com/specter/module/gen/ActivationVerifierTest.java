package com.specter.module.gen;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Plain-JVM tests for the offline activation verifier. Generates a P-256 keypair in-process, signs codes
 * with the private key, and checks every branch of {@link ActivationVerifier#verify} — so the crypto path
 * is exercised with no Python dependency (CI has only pytest). The signed-message FORMAT is pinned to a
 * literal that the Python test (test_activation.py) pins to the same string, so the two stay byte-parallel.
 */
public class ActivationVerifierTest {
    static int passed = 0, failed = 0;
    static void check(boolean cond, String name) {
        if (cond) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    static String pubB64(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());   // X.509 SubjectPublicKeyInfo DER
    }

    /** Build a wire code the same way scripts/make_activation.py does: b64url(payload).b64url(DER-sig). */
    static String makeCode(KeyPair kp, String canonical) throws Exception {
        byte[] payload = canonical.getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate());
        s.update(payload);
        byte[] sig = s.sign();
        Base64.Encoder u = Base64.getUrlEncoder().withoutPadding();
        return u.encodeToString(payload) + "." + u.encodeToString(sig);
    }

    static String canonical(String dh, long exp, String tier, String keyId) {
        return "SPECTER-ACT-1|" + dh + "|" + exp + "|" + tier + "|" + keyId;
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        KeyPair other = g.generateKeyPair();
        String pub = pubB64(kp);

        final String DH = "00112233aabbccdd";
        final long FUTURE = 1893456000L;   // 2030-01-01

        // FORMAT PARITY: the exact string that gets signed, pinned to a literal the Python test also pins.
        check(canonical(DH, FUTURE, "1w", "deadbeef")
                .equals("SPECTER-ACT-1|00112233aabbccdd|1893456000|1w|deadbeef"), "canonical format literal");

        // Happy path: signed for THIS device, not yet expired.
        String code = makeCode(kp, canonical(DH, FUTURE, "1w", "deadbeef"));
        ActivationVerifier.Result ok = ActivationVerifier.verify(code, DH, pub, 1000);
        check(ok.valid, "valid code accepted");
        check(ok.until == FUTURE, "valid code reports expiry");
        check("1w".equals(ok.tier), "valid code reports tier");
        check("deadbeef".equals(ok.keyId), "valid code reports key_id");
        check(ok.reason.isEmpty(), "valid code has no reason");

        // Whitespace/newlines around a pasted code are tolerated.
        check(ActivationVerifier.verify("  \n" + code + "\n ", DH, pub, 1000).valid, "surrounding whitespace tolerated");

        // Expiry is compared to the EFFECTIVE now the caller passes (the clock-rollback guard's leverage):
        // a now past expiry is expired even though the same code was valid at now=1000.
        ActivationVerifier.Result exp = ActivationVerifier.verify(code, DH, pub, FUTURE + 1);
        check(!exp.valid && "expired".equals(exp.reason), "expired when effectiveNow > expiry");
        check(exp.until == FUTURE, "expired result still carries expiry for the UI");

        // Bound to the device: the same signed code on a different phone is rejected.
        check("wrong device".equals(ActivationVerifier.verify(code, "ffffffffffffffff", pub, 1000).reason),
                "wrong device rejected");

        // Signed by a DIFFERENT operator key -> signature fails against the embedded public key.
        String forged = makeCode(other, canonical(DH, FUTURE, "1w", "deadbeef"));
        check("bad signature".equals(ActivationVerifier.verify(forged, DH, pub, 1000).reason),
                "code signed by wrong key rejected");

        // Tampered payload (extend the expiry) with the original signature -> signature no longer matches.
        String[] parts = code.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                canonical(DH, FUTURE + 999999, "1m", "deadbeef").getBytes(StandardCharsets.UTF_8));
        check("bad signature".equals(
                ActivationVerifier.verify(tamperedPayload + "." + parts[1], DH, pub, 1000).reason),
                "tampered payload rejected");

        // Malformed inputs never throw — they return a clean reason.
        check("no code".equals(ActivationVerifier.verify(null, DH, pub, 1000).reason), "null code");
        check("no code".equals(ActivationVerifier.verify("   ", DH, pub, 1000).reason), "blank code");
        check("malformed code".equals(ActivationVerifier.verify("notadot", DH, pub, 1000).reason), "no dot");
        check("malformed code".equals(ActivationVerifier.verify("@@@.@@@", DH, pub, 1000).reason), "bad base64");

        // deviceHash: 16 lowercase hex, deterministic, differs per input.
        String h1 = ActivationVerifier.deviceHash("abc123");
        check(h1.length() == 16 && h1.matches("[0-9a-f]{16}"), "deviceHash is 16 lowercase hex");
        check(h1.equals(ActivationVerifier.deviceHash("abc123")), "deviceHash deterministic");
        check(!h1.equals(ActivationVerifier.deviceHash("abc124")), "deviceHash varies with input");
        // Cross-language parity: Python's hashlib.sha256(b"abc123").digest()[:8].hex() == this literal.
        check("6ca13d52ca70c883".equals(h1), "deviceHash matches the Python reference vector");

        System.out.println("ActivationVerifierTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
