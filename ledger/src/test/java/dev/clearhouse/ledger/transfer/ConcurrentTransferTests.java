package dev.clearhouse.ledger.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import dev.clearhouse.ledger.CurrencyCode;
import dev.clearhouse.ledger.IntegrationTest;
import dev.clearhouse.ledger.LedgerInvariants;
import dev.clearhouse.ledger.account.Account;
import dev.clearhouse.ledger.account.AccountRepository;
import dev.clearhouse.ledger.account.InsufficientFundsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static dev.clearhouse.ledger.CurrencyCode.GBP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hammers the service from several threads at once. These are the bugs a single-threaded
 * test can't find: lost updates, double-spends and duplicate transfers.
 */
@IntegrationTest
class ConcurrentTransferTests {

	private static final UUID EXTERNAL_GBP = UUID.fromString("00000000-0000-0000-0000-000000000826");

	private static final int THREADS = 8;

	@Autowired
	TransferService transferService;

	@Autowired
	AccountRepository accounts;

	@Autowired
	JdbcClient jdbc;

	@AfterEach
	void booksStillBalance() {
		LedgerInvariants.assertBooksBalance(jdbc);
	}

	@Test
	void transfersInEveryDirectionAtOnceNeverLoseAnUpdate() throws Exception {
		List<UUID> ids = List.of(fundedAccount(10_000), fundedAccount(10_000), fundedAccount(10_000));
		List<Callable<TransferResult>> tasks = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			tasks.add(() -> {
				ThreadLocalRandom random = ThreadLocalRandom.current();
				int from = random.nextInt(3);
				int to = (from + 1 + random.nextInt(2)) % 3;
				return transferService.transfer(newKey(),
						new TransferRequest(ids.get(from), ids.get(to), random.nextLong(1, 100), GBP));
			});
		}

		for (Future<TransferResult> outcome : runConcurrently(tasks)) {
			assertThat(outcome.get().replayed()).isFalse();
		}

		long total = ids.stream().mapToLong(this::balanceOf).sum();
		assertThat(total).isEqualTo(30_000);
	}

	@Test
	void sameKeyFromManyThreadsCreatesExactlyOneTransfer() throws Exception {
		UUID alice = fundedAccount(1_000);
		UUID bob = openAccount();
		String key = newKey();
		TransferRequest request = new TransferRequest(alice, bob, 100L, GBP);

		List<Callable<TransferResult>> tasks = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			tasks.add(() -> transferService.transfer(key, request));
		}
		List<TransferResult> results = new ArrayList<>();
		for (Future<TransferResult> outcome : runConcurrently(tasks)) {
			results.add(outcome.get());
		}

		assertThat(results).extracting(result -> result.transfer().getId()).containsOnly(results.get(0).transfer().getId());
		assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
		assertThat(balanceOf(alice)).isEqualTo(900);
		assertThat(balanceOf(bob)).isEqualTo(100);
	}

	@Test
	void concurrentSpendingCannotOverdrawAnAccount() throws Exception {
		UUID alice = fundedAccount(100);
		UUID bob = openAccount();

		List<Callable<TransferResult>> tasks = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			tasks.add(() -> transferService.transfer(newKey(), new TransferRequest(alice, bob, 10L, GBP)));
		}
		int succeeded = 0;
		int rejected = 0;
		for (Future<TransferResult> outcome : runConcurrently(tasks)) {
			try {
				outcome.get();
				succeeded++;
			}
			catch (ExecutionException e) {
				assertThat(e.getCause()).isInstanceOf(InsufficientFundsException.class);
				rejected++;
			}
		}

		assertThat(succeeded).isEqualTo(10);
		assertThat(rejected).isEqualTo(10);
		assertThat(balanceOf(alice)).isZero();
		assertThat(balanceOf(bob)).isEqualTo(100);
	}

	private static <T> List<Future<T>> runConcurrently(List<Callable<T>> tasks) throws InterruptedException {
		try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
			return pool.invokeAll(tasks);
		}
	}

	private UUID openAccount() {
		return accounts.save(Account.customer("Test", CurrencyCode.GBP)).getId();
	}

	private UUID fundedAccount(long amountMinor) {
		UUID id = openAccount();
		transferService.transfer(newKey(), new TransferRequest(EXTERNAL_GBP, id, amountMinor, GBP));
		return id;
	}

	private long balanceOf(UUID accountId) {
		return accounts.findById(accountId).orElseThrow().getBalanceMinor();
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

}
