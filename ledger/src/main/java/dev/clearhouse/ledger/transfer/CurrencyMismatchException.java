package dev.clearhouse.ledger.transfer;

import dev.clearhouse.ledger.CurrencyCode;

public class CurrencyMismatchException extends RuntimeException {

	CurrencyMismatchException(CurrencyCode requested, CurrencyCode from, CurrencyCode to) {
		super("Transfer is in %s but the accounts hold %s and %s".formatted(requested, from, to));
	}

}
