package com.oliverke.payments.recon;

import com.oliverke.payments.order.PaymentOrder;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Reads our side of the comparison.
 *
 * <p>A second repository over {@link PaymentOrder}, living in the reconciliation
 * package rather than beside the entity, so the dependency only ever points one
 * way: reconciliation knows about orders, and orders know nothing about
 * reconciliation. It also puts this query next to the only code that cares how
 * fast it is.
 *
 * <p>Extends {@code Repository} rather than {@code JpaRepository}: this interface
 * exists to run one query, and inheriting save, delete and findAll would offer a
 * reporting job a set of methods it must never call.
 */
public interface LocalSideRepository extends Repository<PaymentOrder, Long> {

    /**
     * The day's orders that actually reached the channel, already in the shape
     * the comparison wants.
     *
     * <p>Three deliberate differences from loading the entities, measured at 1.6x end to end
     * on a 38k-row day, and 3.8x on the query alone:
     *
     * <ul>
     *   <li><strong>A projection, not entities.</strong> Hibernate does not have
     *       to build 38k managed instances, register them with the persistence
     *       context, or dirty-check them at flush. Nothing here is ever modified -
     *       reconciliation reports and never repairs - so paying for managed
     *       objects buys nothing at all.</li>
     *   <li><strong>The null filter is in SQL.</strong> Orders that never reached
     *       the channel used to be fetched and then discarded in Java. Roughly 5%
     *       of the table, carried across the wire to be thrown away.</li>
     *   <li><strong>No ORDER BY.</strong> The previous query sorted by id, which
     *       spilled 5MB to disk on every run and was pure waste: the comparator
     *       indexes both sides into hash maps and sorts its own output, so the
     *       order rows arrive in cannot affect the result.</li>
     * </ul>
     *
     * <p>Half-open on created_at, {@code from} inclusive and {@code to} exclusive,
     * so an order created at exactly midnight belongs to one day and not two.
     */
    @Query("""
            select new com.oliverke.payments.recon.ReconRecord(
                       o.channelRef, o.orderNo, o.merchantId, o.amount, o.status)
              from PaymentOrder o
             where o.createdAt >= :from
               and o.createdAt <  :to
               and o.channelRef is not null
            """)
    List<ReconRecord> findReconcilableBetween(@Param("from") Instant from, @Param("to") Instant to);
}
