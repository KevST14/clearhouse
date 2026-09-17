package dev.clearhouse.ledger.transfer;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import dev.clearhouse.ledger.account.Account;
import dev.clearhouse.ledger.account.AccountNotFoundException;
import dev.clearhouse.ledger.account.AccountRepository;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransferService {

	private static final int MAX_ATTEMPTS = 10;

	private final AccountRepository accounts;

	private final TransferRepository transfers;

	private final LedgerEntryRepository entries;

	private final TransactionTemplate transaction;

	TransferService(AccountRepository accounts, TransferRepository transfers, LedgerEntryRepository entries,
			PlatformTransactionManager transactionManager) {
		this.accounts = accounts;
		this.transfers = transfers;
		this.entries = entries;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * Moves money between two accounts, at most once per idempotency key.
	 * <p>
	 * Each attempt runs in its own transaction. If another transfer changed either account
	 * after we read it, the optimistic lock fails at commit, everything rolls back, and we
	 * try again from a fresh read.
	 */
	public TransferResult transfer(String idempotencyKey, TransferRequest request) {
		for (int attempt = 1;; attempt++) {
			try {
				return transaction.execute(status -> transferOnce(idempotencyKey, request));
			}
			catch (DataIntegrityViolationException e) {
				// A concurrent request with the same key committed first, so answer with its result.
				Transfer original = transfers.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
				return replay(original, request);
			}
			catch (ConcurrencyFailureException e) {
				if (attempt == MAX_ATTEMPTS) {
					throw e;
				}
				backOff(attempt);
			}
		}
	}

	private TransferResult transferOnce(String idempotencyKey, TransferRequest request) {
		Optional<Transfer> existing = transfers.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent()) {
			return replay(existing.get(), request);
		}

		Account from = findAccount(request.fromAccountId());
		Account to = findAccount(request.toAccountId());
		if (from.getCurrency() != request.currency() || to.getCurrency() != request.currency()) {
			throw new CurrencyMismatchException(request.currency(), from.getCurrency(), to.getCurrency());
		}

		long amount = request.amountMinor();
		from.debit(amount);
		to.credit(amount);

		Transfer transfer = transfers.save(new Transfer(idempotencyKey, request));
		entries.save(new LedgerEntry(transfer, from.getId(), -amount));
		entries.save(new LedgerEntry(transfer, to.getId(), amount));
		return new TransferResult(transfer, false);
	}

	private Account findAccount(UUID id) {
		return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
	}

	private static TransferResult replay(Transfer original, TransferRequest request) {
		if (!original.matches(request)) {
			throw new IdempotencyKeyReusedException(original.getIdempotencyKey());
		}
		return new TransferResult(original, true);
	}

	/** Random, growing pause so competing retries don't collide again in lockstep. */
	private static void backOff(int attempt) {
		long maxMillis = Math.min(50, 1L << attempt);
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(maxMillis + 1));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while retrying a transfer", e);
		}
	}

}
