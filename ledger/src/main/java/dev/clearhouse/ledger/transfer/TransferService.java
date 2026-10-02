package dev.clearhouse.ledger.transfer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.clearhouse.ledger.account.Account;
import dev.clearhouse.ledger.account.AccountNotFoundException;
import dev.clearhouse.ledger.account.AccountRepository;
import dev.clearhouse.ledger.outbox.Outbox;
import dev.clearhouse.ledger.transfer.TransferEvents.TransferCreated;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransferService {

	private final AccountRepository accounts;

	private final TransferRepository transfers;

	private final LedgerEntryRepository entries;

	private final Outbox outbox;

	private final TransactionTemplate transaction;

	TransferService(AccountRepository accounts, TransferRepository transfers, LedgerEntryRepository entries,
			Outbox outbox, PlatformTransactionManager transactionManager) {
		this.accounts = accounts;
		this.transfers = transfers;
		this.entries = entries;
		this.outbox = outbox;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * Moves money between two accounts, at most once per idempotency key.
	 * <p>
	 * Both account rows are locked before their balances are read, so concurrent transfers
	 * touching the same account run one after another rather than overwriting each other.
	 */
	public TransferResult transfer(String idempotencyKey, TransferRequest request) {
		try {
			return transaction.execute(status -> transferOnce(idempotencyKey, request));
		}
		catch (DataIntegrityViolationException e) {
			// A concurrent request with the same key committed first, so answer with its result.
			Transfer original = transfers.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
			return replay(original, request);
		}
	}

	private TransferResult transferOnce(String idempotencyKey, TransferRequest request) {
		Optional<Transfer> existing = transfers.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent()) {
			return replay(existing.get(), request);
		}

		List<Account> locked = accounts.lockAllById(List.of(request.fromAccountId(), request.toAccountId()));
		Account from = find(locked, request.fromAccountId());
		Account to = find(locked, request.toAccountId());
		if (from.getCurrency() != request.currency() || to.getCurrency() != request.currency()) {
			throw new CurrencyMismatchException(request.currency(), from.getCurrency(), to.getCurrency());
		}

		long amount = request.amountMinor();
		from.debit(amount);
		to.credit(amount);

		Transfer transfer = transfers.save(new Transfer(idempotencyKey, request));
		entries.save(new LedgerEntry(transfer, from.getId(), -amount));
		entries.save(new LedgerEntry(transfer, to.getId(), amount));
		outbox.append(TransferEvents.TOPIC, from.getId().toString(), TransferEvents.TRANSFER_CREATED,
				transfer.getCreatedAt(), TransferCreated.from(transfer));
		return new TransferResult(transfer, false);
	}

	private static Account find(List<Account> accounts, UUID id) {
		return accounts.stream()
			.filter(account -> account.getId().equals(id))
			.findFirst()
			.orElseThrow(() -> new AccountNotFoundException(id));
	}

	private static TransferResult replay(Transfer original, TransferRequest request) {
		if (!original.matches(request)) {
			throw new IdempotencyKeyReusedException(original.getIdempotencyKey());
		}
		return new TransferResult(original, true);
	}

}
