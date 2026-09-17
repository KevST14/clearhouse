package dev.clearhouse.ledger.transfer;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One side of a transfer: negative for the account money left, positive for the one it arrived in. */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID transferId;

	private UUID accountId;

	private long amountMinor;

	private Instant createdAt;

	protected LedgerEntry() {
	}

	LedgerEntry(Transfer transfer, UUID accountId, long amountMinor) {
		this.transferId = transfer.getId();
		this.accountId = accountId;
		this.amountMinor = amountMinor;
		this.createdAt = transfer.getCreatedAt();
	}

	public UUID getTransferId() {
		return transferId;
	}

	public UUID getAccountId() {
		return accountId;
	}

	public long getAmountMinor() {
		return amountMinor;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
