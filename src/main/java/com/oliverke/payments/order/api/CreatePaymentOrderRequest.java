package com.oliverke.payments.order.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * @param amount   rejected rather than rounded when it carries more than four
 *                 decimal places. NUMERIC(19,4) would round 10.00005 on the way
 *                 in and the caller would never be told that the amount they
 *                 asked to be charged is not the amount that was charged.
 * @param currency validated here as well as by the CHECK constraint: the
 *                 constraint is the guarantee, this is the 400 instead of a 500.
 */
public record CreatePaymentOrderRequest(

        @NotBlank
        @Size(max = 64)
        String merchantId,

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO 4217 code")
        String currency
) {
}
