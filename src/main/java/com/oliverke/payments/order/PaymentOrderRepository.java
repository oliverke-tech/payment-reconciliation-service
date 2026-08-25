package com.oliverke.payments.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByOrderNo(String orderNo);

    /**
     * Orders belonging to one business day, as a half-open interval on
     * created_at: {@code from} inclusive, {@code to} exclusive.
     *
     * <p>Half-open rather than BETWEEN on purpose. BETWEEN is inclusive at both
     * ends, so an order created at exactly midnight would be reconciled on two
     * consecutive days - counted twice, and reported as a discrepancy on at least
     * one of them.
     *
     * <p>The caller decides what a day means. This deliberately takes instants
     * rather than a LocalDate so the timezone the business day is defined in is a
     * decision made once, in application code, and not inherited from whatever
     * the database server happens to be set to.
     */
    @Query("""
            select o from PaymentOrder o
            where o.createdAt >= :from and o.createdAt < :to
            order by o.id
            """)
    List<PaymentOrder> findCreatedBetween(@Param("from") Instant from, @Param("to") Instant to);
}
