package dev.clearhouse.ledger.transfer;

import java.time.Instant;
import java.util.UUID;

import dev.clearhouse.ledger.CurrencyCode;
import dev.clearhouse.ledger.Timestamps;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "transfers")
public class Transfer {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private String idempotencyKey;

	private UUID fromAccountId;

	private UUID toAccountId;

	private long amountMinor;

	@Enumerated(EnumType.STRING)
	private CurrencyCode currency;

	private Instant createdAt;

	protected Transfer() {
	}

	Transfer(String idempotencyKey, TransferRequest request) {
		this.idempotencyKey = idempotencyKey;
		this.fromAccountId = request.fromAccountId();
		this.toAccountId = request.toAccountId();
		this.amountMinor = request.amountMinor();
		this.currency = request.currency();
		this.createdAt = Timestamps.now();
	}

	/** Whether a retried request with this transfer's idempotency key asked for the same thing. */
	boolean matches(TransferRequest request) {
		return fromAccountId.equals(request.fromAccountId()) && toAccountId.equals(request.toAccountId())
				&& amountMinor == request.amountMinor() && currency == request.currency();
	}

	public UUID getId() {
		return id;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public UUID getFromAccountId() {
		return fromAccountId;
	}

	public UUID getToAccountId() {
		return toAccountId;
	}

	public long getAmountMinor() {
		return amountMinor;
	}

	public CurrencyCode getCurrency() {
		return currency;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
