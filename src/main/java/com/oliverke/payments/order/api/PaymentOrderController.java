package com.oliverke.payments.order.api;

import com.oliverke.payments.order.PaymentOrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment-orders")
public class PaymentOrderController {

    private final PaymentOrderService service;

    PaymentOrderController(PaymentOrderService service) {
        this.service = service;
    }

    /**
     * The header is accepted from the very first version even though Step 2
     * ignores it, so that the Step 3 and Step 5 load tests can be the same
     * script hitting the same endpoint with the same key. The only thing that
     * changes between the "before" and "after" numbers is the implementation.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentOrderResponse create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentOrderRequest request) {

        return service.create(request, idempotencyKey);
    }
}
