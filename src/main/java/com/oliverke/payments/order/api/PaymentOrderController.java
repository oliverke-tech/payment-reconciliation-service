package com.oliverke.payments.order.api;

import com.oliverke.payments.idempotency.IdempotentOrderCreation;
import com.oliverke.payments.idempotency.ReplayableResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/payment-orders")
public class PaymentOrderController {

    /** Matches idempotency_record.idempotency_key in V2. */
    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotentOrderCreation creation;

    PaymentOrderController(IdempotentOrderCreation creation) {
        this.creation = creation;
    }

    /**
     * The header is required. Creating a payment without an idempotency key is a
     * bug in the caller, and quietly falling back to a non-idempotent create
     * would mean the one endpoint that must never double-charge has a mode in
     * which it does. A missing header is a 400 from Spring before this method
     * runs.
     *
     * <p>Returns the stored bytes rather than an object so that a replay is
     * byte-for-byte the original response - see {@link ReplayableResponse}.
     */
    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentOrderRequest request) {

        validate(idempotencyKey);

        ReplayableResponse response = creation.create(request, idempotencyKey);

        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replay", Boolean.toString(response.replayed()))
                .body(response.body());
    }

    private static void validate(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must not be blank");
        }
        // Rejected here rather than at the insert: an over-long key violates the
        // column length, which surfaces as the same DataIntegrityViolationException
        // used to detect a duplicate key, and a caller error should not have to be
        // disentangled from a concurrency signal.
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
    }
}
