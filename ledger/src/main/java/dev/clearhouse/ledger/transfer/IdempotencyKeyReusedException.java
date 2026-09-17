package dev.clearhouse.ledger.transfer;

public class IdempotencyKeyReusedException extends RuntimeException {

	IdempotencyKeyReusedException(String idempotencyKey) {
		super("Idempotency key '%s' was already used for a different transfer".formatted(idempotencyKey));
	}

}
