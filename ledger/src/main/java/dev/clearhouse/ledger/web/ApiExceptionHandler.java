package dev.clearhouse.ledger.web;

import dev.clearhouse.ledger.account.AccountNotFoundException;
import dev.clearhouse.ledger.account.InsufficientFundsException;
import dev.clearhouse.ledger.transfer.CurrencyMismatchException;
import dev.clearhouse.ledger.transfer.IdempotencyKeyReusedException;
import dev.clearhouse.ledger.transfer.TransferNotFoundException;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns domain errors into RFC 9457 problem responses. Extending
 * {@link ResponseEntityExceptionHandler} gives validation and malformed-request errors
 * the same shape.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler
	ProblemDetail accountNotFound(AccountNotFoundException e) {
		return problem(HttpStatus.NOT_FOUND, "Account not found", e);
	}

	@ExceptionHandler
	ProblemDetail transferNotFound(TransferNotFoundException e) {
		return problem(HttpStatus.NOT_FOUND, "Transfer not found", e);
	}

	@ExceptionHandler
	ProblemDetail insufficientFunds(InsufficientFundsException e) {
		return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds", e);
	}

	@ExceptionHandler
	ProblemDetail currencyMismatch(CurrencyMismatchException e) {
		return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Currency mismatch", e);
	}

	@ExceptionHandler
	ProblemDetail idempotencyKeyReused(IdempotencyKeyReusedException e) {
		return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency key reused", e);
	}

	@ExceptionHandler
	ProblemDetail concurrencyFailure(ConcurrencyFailureException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"The accounts were too busy to update. Retry with the same Idempotency-Key.");
		problem.setTitle("Concurrent update");
		return problem;
	}

	private static ProblemDetail problem(HttpStatus status, String title, RuntimeException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
		problem.setTitle(title);
		return problem;
	}

}
