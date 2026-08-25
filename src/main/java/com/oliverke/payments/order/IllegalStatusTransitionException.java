package com.oliverke.payments.order;

/**
 * An attempt to move an order somewhere its current state does not permit.
 * Maps to 409: the request is well-formed, but it conflicts with the state the
 * resource is actually in.
 */
public class IllegalStatusTransitionException extends RuntimeException {

    private final OrderStatus from;
    private final OrderStatus to;

    public IllegalStatusTransitionException(String orderNo, OrderStatus from, OrderStatus to) {
        super("order '%s' cannot move from %s to %s".formatted(orderNo, from, to));
        this.from = from;
        this.to = to;
    }

    public OrderStatus getFrom() {
        return from;
    }

    public OrderStatus getTo() {
        return to;
    }
}
