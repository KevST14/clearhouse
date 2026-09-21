package dev.clearhouse.ledger.transfer;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import dev.clearhouse.ledger.IntegrationTest;
import dev.clearhouse.ledger.LedgerInvariants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@IntegrationTest
class TransferApiTests {

	private static final String EXTERNAL_GBP = "00000000-0000-0000-0000-000000000826";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@AfterEach
	void booksStillBalance() {
		LedgerInvariants.assertBooksBalance(jdbc);
	}

	@Test
	void newAccountStartsEmpty() throws Exception {
		String alice = openAccount("Alice", "GBP");

		assertThat(mvc.get().uri("/accounts/{id}", alice)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.balanceMinor")
			.isEqualTo(0);
	}

	@Test
	void depositComesOutOfTheExternalAccountAsTwoEntries() throws Exception {
		String alice = openAccount("Alice", "GBP");
		long externalBefore = balanceOf(EXTERNAL_GBP);

		assertThat(transfer(newKey(), EXTERNAL_GBP, alice, 2_500, "GBP")).hasStatus(HttpStatus.CREATED);

		assertThat(balanceOf(alice)).isEqualTo(2_500);
		assertThat(balanceOf(EXTERNAL_GBP)).isEqualTo(externalBefore - 2_500);
		assertThat(mvc.get().uri("/accounts/{id}/entries", alice)).hasStatusOk()
			.bodyJson()
			.extractingPath("$[*].amountMinor")
			.asArray()
			.containsExactly(2_500);
	}

	@Test
	void transferMovesMoneyBetweenCustomers() throws Exception {
		String alice = fundedAccount("Alice", 10_000);
		String bob = openAccount("Bob", "GBP");

		MvcTestResult result = transfer(newKey(), alice, bob, 2_500, "GBP");

		assertThat(result).hasStatus(HttpStatus.CREATED);
		String transferId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		assertThat(result).headers().hasValue("Location", "/transfers/" + transferId);
		assertThat(balanceOf(alice)).isEqualTo(7_500);
		assertThat(balanceOf(bob)).isEqualTo(2_500);
		assertThat(mvc.get().uri("/transfers/{id}", transferId)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.amountMinor")
			.isEqualTo(2_500);
	}

	@Test
	void transferThatWouldOverdrawIsRejected() throws Exception {
		String alice = fundedAccount("Alice", 1_000);
		String bob = openAccount("Bob", "GBP");

		assertThat(transfer(newKey(), alice, bob, 1_001, "GBP")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson()
			.extractingPath("$.title")
			.isEqualTo("Insufficient funds");

		assertThat(balanceOf(alice)).isEqualTo(1_000);
		assertThat(balanceOf(bob)).isZero();
	}

	@Test
	void retryWithTheSameKeyReturnsTheOriginalTransferAndMovesMoneyOnce() throws Exception {
		String alice = fundedAccount("Alice", 10_000);
		String bob = openAccount("Bob", "GBP");
		String key = newKey();

		MvcTestResult first = transfer(key, alice, bob, 2_500, "GBP");
		MvcTestResult retry = transfer(key, alice, bob, 2_500, "GBP");

		assertThat(first).hasStatus(HttpStatus.CREATED);
		assertThat(retry).hasStatusOk().headers().hasValue("Idempotent-Replayed", "true");
		assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
		assertThat(balanceOf(alice)).isEqualTo(7_500);
		assertThat(balanceOf(bob)).isEqualTo(2_500);
	}

	@Test
	void reusingAKeyForADifferentTransferIsRejected() throws Exception {
		String alice = fundedAccount("Alice", 10_000);
		String bob = openAccount("Bob", "GBP");
		String key = newKey();

		assertThat(transfer(key, alice, bob, 2_500, "GBP")).hasStatus(HttpStatus.CREATED);
		assertThat(transfer(key, alice, bob, 9_999, "GBP")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson()
			.extractingPath("$.title")
			.isEqualTo("Idempotency key reused");

		assertThat(balanceOf(alice)).isEqualTo(7_500);
	}

	@Test
	void transferBetweenDifferentCurrenciesIsRejected() throws Exception {
		String alice = fundedAccount("Alice", 10_000);
		String pierre = openAccount("Pierre", "EUR");

		assertThat(transfer(newKey(), alice, pierre, 100, "GBP")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson()
			.extractingPath("$.title")
			.isEqualTo("Currency mismatch");
	}

	@Test
	void malformedTransfersAreBadRequests() throws Exception {
		String alice = fundedAccount("Alice", 10_000);
		String bob = openAccount("Bob", "GBP");

		assertThat(transfer(newKey(), alice, alice, 100, "GBP")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(transfer(newKey(), alice, bob, 0, "GBP")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(transfer(newKey(), alice, bob, -100, "GBP")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(transfer(newKey(), alice, bob, 100, "XYZ")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.post()
			.uri("/transfers")
			.contentType(APPLICATION_JSON)
			.content(transferJson(alice, bob, 100, "GBP"))).hasStatus(HttpStatus.BAD_REQUEST);

		assertThat(balanceOf(alice)).isEqualTo(10_000);
	}

	@Test
	void unknownAccountsAreNotFound() throws Exception {
		String alice = fundedAccount("Alice", 10_000);
		String nobody = UUID.randomUUID().toString();

		assertThat(mvc.get().uri("/accounts/{id}", nobody)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(transfer(newKey(), alice, nobody, 100, "GBP")).hasStatus(HttpStatus.NOT_FOUND);
	}

	private String openAccount(String ownerName, String currency) throws Exception {
		MvcTestResult result = mvc.post()
			.uri("/accounts")
			.contentType(APPLICATION_JSON)
			.content("""
					{"ownerName": "%s", "currency": "%s"}""".formatted(ownerName, currency))
			.exchange();
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}

	private String fundedAccount(String ownerName, long amountMinor) throws Exception {
		String account = openAccount(ownerName, "GBP");
		assertThat(transfer(newKey(), EXTERNAL_GBP, account, amountMinor, "GBP")).hasStatus(HttpStatus.CREATED);
		return account;
	}

	private MvcTestResult transfer(String key, String from, String to, long amountMinor, String currency) {
		return mvc.post()
			.uri("/transfers")
			.header("Idempotency-Key", key)
			.contentType(APPLICATION_JSON)
			.content(transferJson(from, to, amountMinor, currency))
			.exchange();
	}

	private long balanceOf(String accountId) throws Exception {
		MvcTestResult result = mvc.get().uri("/accounts/{id}", accountId).exchange();
		Number balance = JsonPath.read(result.getResponse().getContentAsString(), "$.balanceMinor");
		return balance.longValue();
	}

	private static String transferJson(String from, String to, long amountMinor, String currency) {
		return """
				{"fromAccountId": "%s", "toAccountId": "%s", "amountMinor": %d, "currency": "%s"}"""
			.formatted(from, to, amountMinor, currency);
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

}
