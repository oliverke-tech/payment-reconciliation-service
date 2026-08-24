package com.oliverke.payments.order.api;

import com.oliverke.payments.idempotency.IdempotencyConflictException;
import com.oliverke.payments.idempotency.IdempotencyKeyReuseException;
import com.oliverke.payments.order.IllegalStatusTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the idempotency failures onto status codes. Pure plumbing - the
 * interesting decision is which status each case deserves, and that is encoded
 * in the exception types, not here.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail onConflict(IdempotencyConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Request already in progress");
        return problem;
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ProblemDetail onKeyReuse(IdempotencyKeyReuseException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle("Idempotency key reused with a different request");
        return problem;
    }

    @ExceptionHandler(IllegalStatusTransitionException.class)
    ProblemDetail onIllegalTransition(IllegalStatusTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Illegal order status transition");
        problem.setProperty("from", e.getFrom());
        problem.setProperty("to", e.getTo());
        return problem;
    }
}
