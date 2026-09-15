package dev.clearhouse.ledger.account;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

	public InsufficientFundsException(UUID accountId, long balanceMinor, long requestedMinor) {
		super("Account %s has %d available but the transfer needs %d".formatted(accountId, balanceMinor, requestedMinor));
	}

}
