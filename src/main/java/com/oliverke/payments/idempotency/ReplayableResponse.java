package com.oliverke.payments.idempotency;

/**
 * A response as stored and as returned: an HTTP status, the exact body bytes,
 * and whether this is the original execution or a replay of one.
 *
 * <p>Both paths hand back one of these, which is what makes a retry
 * byte-identical to the original. If the happy path returned an object and the
 * replay path returned a stored string, the two would drift the first time
 * serialisation changed - a new field, a different date format - and the promise
 * of idempotency is precisely that they do not drift.
 *
 * <p>{@code replayed} is surfaced as a response header. It deliberately does not
 * change the status code or the body: a retry is supposed to be indistinguishable
 * from the original request, and the header is an observability affordance for
 * the caller, not part of the contract.
 */
public record ReplayableResponse(int status, String body, boolean replayed) {

    public static ReplayableResponse fresh(int status, String body) {
        return new ReplayableResponse(status, body, false);
    }

    public static ReplayableResponse replay(int status, String body) {
        return new ReplayableResponse(status, body, true);
    }
}
