package dev.clearhouse.ledger.transfer;

import java.util.UUID;

import dev.clearhouse.ledger.CurrencyCode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
		@NotNull UUID fromAccountId,
		@NotNull UUID toAccountId,
		@NotNull @Positive Long amountMinor,
		@NotNull CurrencyCode currency) {

	@AssertTrue(message = "fromAccountId and toAccountId must be different accounts")
	boolean isBetweenDifferentAccounts() {
		return fromAccountId == null || !fromAccountId.equals(toAccountId);
	}

}
