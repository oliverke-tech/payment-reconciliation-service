package com.oliverke.payments.channel;

/**
 * The four ways our records and the channel's can disagree. These are what the
 * generator injects on purpose and what the Step 8 job has to find - exactly
 * these, no more and no fewer.
 */
public enum DiscrepancyType {

    /** We have an order that reached the channel; the statement has no line for it. */
    LOCAL_ONLY,

    /** The statement has a line we have no order for at all. */
    CHANNEL_ONLY,

    /** Both sides have it, and the money does not agree. */
    AMOUNT_MISMATCH,

    /** Both sides have it, and the outcome does not agree. */
    STATUS_MISMATCH
}
