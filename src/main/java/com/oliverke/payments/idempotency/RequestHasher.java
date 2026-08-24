package com.oliverke.payments.idempotency;

import com.oliverke.payments.order.api.CreatePaymentOrderRequest;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fingerprints a request so that reuse of an idempotency key with a different
 * body can be detected.
 */
@Component
public class RequestHasher {

    /**
     * ASCII unit separator (0x1F). Joining with a character that cannot occur in
     * any of the fields is what stops ("ab", "c") and ("a", "bc") hashing to the
     * same value - a collision that would let one request be mistaken for
     * another. Currency and merchant id are constrained to safe alphabets, so
     * this is belt and braces, but the cost is one character.
     */
    private static final String SEPARATOR = String.valueOf((char) 0x1F);

    public String hash(CreatePaymentOrderRequest request) {
        String canonical = String.join(SEPARATOR,
                request.merchantId(),
                canonicalAmount(request),
                request.currency());

        return sha256Hex(canonical);
    }

    /**
     * BigDecimal carries its scale, so "10", "10.00" and "10.0000" are three
     * different strings for the same amount of money. Hashing them as they
     * arrive would hand a 422 to a caller who retried with "10" after first
     * sending "10.00" - the same payment, rejected as a different one.
     *
     * <p>Normalising to the scale of the column, NUMERIC(19,4), makes the
     * fingerprint agree with what the database will actually store.
     * RoundingMode.UNNECESSARY rather than HALF_UP on purpose: the @Digits
     * constraint has already rejected anything with more than four decimal
     * places, so rounding here could only ever mask a validation bug, and
     * throwing is the louder failure.
     */
    private static String canonicalAmount(CreatePaymentOrderRequest request) {
        return request.amount()
                .setScale(4, RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    private static String sha256Hex(String canonical) {
        try {
            // MessageDigest is stateful and not thread-safe, so it is created
            // per call rather than held as a field on this singleton bean.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            // Lower-case hex, matching the CHECK constraint in V2.
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
