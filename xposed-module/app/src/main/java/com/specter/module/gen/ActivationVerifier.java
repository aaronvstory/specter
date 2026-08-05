package com.specter.module.gen;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Offline activation-code verifier. The operator signs a short code with an EC P-256 PRIVATE key held only
 * on their machine; the app ships only the PUBLIC key ({@link #PUBLIC_KEY_B64}), so a decompiled APK cannot
 * mint codes — verifying and signing are different keys. Everything here is local: no network call, no
 * server. See docs/DECISIONS.md for why P-256 (native on the API-30 fleet, minSdk 24) rather than Ed25519
 * (platform Signature support is API 33+, which the fleet does not have).
 *
 * <p>The signed message is a canonical ASCII string so Python (the generator) and Java (this verifier)
 * agree byte-for-byte:
 * <pre>SPECTER-ACT-1|{device_hash}|{expiry_epoch}|{tier}|{key_id}</pre>
 * The wire code is {@code base64url(payload) + "." + base64url(DER-signature)} — both sides speak X.509-DER
 * public keys and DER signatures, so there is no manual EC-point or DER↔raw conversion to get wrong.
 *
 * <p>{@link #verify} is pure (no Android types) so the JVM test can exercise every branch. The Android
 * entry point is {@link com.specter.module.ui.ActivationStore}, which supplies the real device hash and a
 * monotonic "now" that a rolled-back clock cannot cheat.
 */
public final class ActivationVerifier {
    private ActivationVerifier() {}

    /** Operator's EC P-256 PUBLIC key, X.509 SubjectPublicKeyInfo, base64. The matching PRIVATE key lives
     *  only on the operator's machine (scripts/make_activation.py reads it from outside this PUBLIC repo).
     *  Placeholder below is the DEV key committed for tests; a distributable build replaces it at release. */
    public static final String PUBLIC_KEY_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE2JmRF0gUe7m4lxhGoYY0LwvZif6OThsb+Dst2tG06m7bEhY+3f4Q6Qo93+HMM7j+jLmqVyqiastvfQQn8LEhPA==";

    public static final String PREFIX = "SPECTER-ACT-1";

    /** Result of a check. {@code valid} gates access; {@code until} is expiry epoch seconds; {@code reason}
     *  is a short human string for the UI on failure ("expired", "wrong device", "bad signature", …). */
    public static final class Result {
        public final boolean valid;
        public final long until;      // expiry epoch seconds (0 when invalid/unparseable)
        public final String tier;     // "1d" / "1w" / "1m" (empty when invalid)
        public final String keyId;    // operator's key id for the ledger (empty when invalid)
        public final String reason;   // "" when valid, else a short cause
        Result(boolean valid, long until, String tier, String keyId, String reason) {
            this.valid = valid; this.until = until; this.tier = tier; this.keyId = keyId; this.reason = reason;
        }
        static Result bad(String reason) { return new Result(false, 0, "", "", reason); }
    }

    /**
     * Verify a pasted code against the embedded public key, the real device hash, and the clock.
     *
     * @param code         the pasted activation code (may carry surrounding whitespace/newlines)
     * @param deviceHash   this device's real id hash (16 lowercase hex) — see {@link #deviceHash}
     * @param pubKeyB64    the operator public key (X.509 DER, base64); pass {@link #PUBLIC_KEY_B64}
     * @param effectiveNow epoch seconds to compare expiry against — the caller passes
     *                     {@code max(systemClock, highestClockEverSeen)} so rolling the clock back cannot
     *                     resurrect an expired code.
     */
    public static Result verify(String code, String deviceHash, String pubKeyB64, long effectiveNow) {
        if (code == null) return Result.bad("no code");
        String clean = code.trim().replaceAll("\\s+", "");
        if (clean.isEmpty()) return Result.bad("no code");
        int dot = clean.indexOf('.');
        if (dot <= 0 || dot == clean.length() - 1) return Result.bad("malformed code");

        byte[] payload, sig, pub;
        try {
            payload = Base64.getUrlDecoder().decode(clean.substring(0, dot));
            sig = Base64.getUrlDecoder().decode(clean.substring(dot + 1));
            pub = Base64.getDecoder().decode(pubKeyB64);
        } catch (RuntimeException e) {
            return Result.bad("malformed code");
        }

        // Signature must cover the exact payload bytes, verified against the operator's public key.
        try {
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(pub));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(key);
            s.update(payload);
            if (!s.verify(sig)) return Result.bad("bad signature");
        } catch (Exception e) {
            return Result.bad("bad signature");
        }

        // Only now trust the fields — the signature has proven they are the operator's.
        String msg = new String(payload, StandardCharsets.UTF_8);
        String[] f = msg.split("\\|", -1);
        if (f.length != 5 || !PREFIX.equals(f[0])) return Result.bad("unknown code format");
        String dh = f[1], tier = f[3], keyId = f[4];
        long expiry;
        try { expiry = Long.parseLong(f[2]); } catch (NumberFormatException e) { return Result.bad("unknown code format"); }

        if (!dh.equals(deviceHash)) return Result.bad("wrong device");
        if (expiry <= effectiveNow) return new Result(false, expiry, tier, keyId, "expired");
        return new Result(true, expiry, tier, keyId, "");
    }

    /** This device's binding hash: first 8 bytes of SHA-256(androidId), lowercase hex (16 chars). The raw
     *  android_id never leaves the device — the operator only ever sees this hash. Because Specter is not in
     *  its OWN LSPosed scope, a read from inside the app returns the REAL android_id, not a spoofed one
     *  (asserted on-device by the activation screen showing a stable hash with a profile applied). */
    public static String deviceHash(String androidId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(
                    (androidId == null ? "" : androidId).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(Character.forDigit((h[i] >> 4) & 0xf, 16))
                                         .append(Character.forDigit(h[i] & 0xf, 16));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
