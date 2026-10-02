package dev.clearhouse.ledger.account;

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
import jakarta.persistence.Version;

@Entity
@Table(name = "accounts")
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private String ownerName;

	@Enumerated(EnumType.STRING)
	private AccountKind kind;

	@Enumerated(EnumType.STRING)
	private CurrencyCode currency;

	private long balanceMinor;

	/**
	 * Safety net behind the row locks in {@link AccountRepository#lockAllById}: if any code
	 * path ever updates a balance without locking first, a lost update fails loudly.
	 */
	@Version
	private long version;

	private Instant createdAt;

	protected Account() {
	}

	private Account(String ownerName, AccountKind kind, CurrencyCode currency) {
		this.ownerName = ownerName;
		this.kind = kind;
		this.currency = currency;
		this.createdAt = Timestamps.now();
	}

	public static Account customer(String ownerName, CurrencyCode currency) {
		return new Account(ownerName, AccountKind.CUSTOMER, currency);
	}

	public void debit(long amountMinor) {
		if (kind == AccountKind.CUSTOMER && balanceMinor < amountMinor) {
			throw new InsufficientFundsException(id, balanceMinor, amountMinor);
		}
		balanceMinor = Math.subtractExact(balanceMinor, amountMinor);
	}

	public void credit(long amountMinor) {
		balanceMinor = Math.addExact(balanceMinor, amountMinor);
	}

	public UUID getId() {
		return id;
	}

	public String getOwnerName() {
		return ownerName;
	}

	public AccountKind getKind() {
		return kind;
	}

	public CurrencyCode getCurrency() {
		return currency;
	}

	public long getBalanceMinor() {
		return balanceMinor;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
