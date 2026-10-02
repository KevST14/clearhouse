package dev.clearhouse.ledger.transfer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import dev.clearhouse.ledger.CurrencyCode;
import dev.clearhouse.ledger.IntegrationTest;
import dev.clearhouse.ledger.LedgerInvariants;
import dev.clearhouse.ledger.account.Account;
import dev.clearhouse.ledger.account.AccountRepository;
import dev.clearhouse.ledger.account.InsufficientFundsException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.jdbc.core.simple.JdbcClient;

import static dev.clearhouse.ledger.CurrencyCode.GBP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.fail;

@IntegrationTest
class TransferEventsTests {

	private static final UUID EXTERNAL_GBP = UUID.fromString("00000000-0000-0000-0000-000000000826");

	@Autowired
	TransferService transferService;

	@Autowired
	AccountRepository accounts;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	KafkaConnectionDetails kafka;

	@AfterEach
	void booksStillBalance() {
		LedgerInvariants.assertBooksBalance(jdbc);
	}

	@Test
	void transferIsPublishedToKafkaKeyedByThePayer() {
		UUID alice = fundedAccount(1_000);
		UUID bob = openAccount();

		Transfer transfer = transferService.transfer(newKey(), new TransferRequest(alice, bob, 250L, GBP)).transfer();

		ConsumerRecord<String, String> record = awaitEventFor(transfer.getId());
		DocumentContext event = JsonPath.parse(record.value());
		assertThat(record.key()).isEqualTo(alice.toString());
		assertThat(event.read("$.eventType", String.class)).isEqualTo("transfer.created");
		assertThat(event.read("$.data.fromAccountId", String.class)).isEqualTo(alice.toString());
		assertThat(event.read("$.data.toAccountId", String.class)).isEqualTo(bob.toString());
		assertThat(event.read("$.data.amountMinor", Long.class)).isEqualTo(250);
		assertThat(event.read("$.data.currency", String.class)).isEqualTo("GBP");
		assertThat(Instant.parse(event.read("$.occurredAt", String.class))).isEqualTo(transfer.getCreatedAt());

		String eventId = event.read("$.eventId");
		assertThat(jdbc.sql("select published_at is not null from outbox_events where id = :id")
			.param("id", UUID.fromString(eventId))
			.query(Boolean.class)
			.single()).as("outbox row marked published").isTrue();
	}

	@Test
	void rejectedTransferWritesNoEvent() {
		UUID alice = fundedAccount(100);
		UUID bob = openAccount();

		assertThatExceptionOfType(InsufficientFundsException.class)
			.isThrownBy(() -> transferService.transfer(newKey(), new TransferRequest(alice, bob, 101L, GBP)));

		assertThat(eventsFrom(alice)).isZero();
	}

	@Test
	void replayedTransferWritesOneEvent() {
		UUID alice = fundedAccount(1_000);
		UUID bob = openAccount();
		String key = newKey();
		TransferRequest request = new TransferRequest(alice, bob, 250L, GBP);

		transferService.transfer(key, request);
		transferService.transfer(key, request);

		assertThat(eventsFrom(alice)).isEqualTo(1);
	}

	private long eventsFrom(UUID accountId) {
		return jdbc.sql("select count(*) from outbox_events where payload -> 'data' ->> 'fromAccountId' = :id")
			.param("id", accountId.toString())
			.query(Long.class)
			.single();
	}

	private ConsumerRecord<String, String> awaitEventFor(UUID transferId) {
		Map<String, Object> config = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
				new StringDeserializer())) {
			// Read every partition from the start, without joining a consumer group.
			List<TopicPartition> partitions = consumer.partitionsFor(TransferEvents.TOPIC)
				.stream()
				.map(partition -> new TopicPartition(partition.topic(), partition.partition()))
				.toList();
			consumer.assign(partitions);
			consumer.seekToBeginning(partitions);
			Instant deadline = Instant.now().plusSeconds(20);
			while (Instant.now().isBefore(deadline)) {
				for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
					if (transferId.toString().equals(JsonPath.read(record.value(), "$.data.transferId"))) {
						return record;
					}
				}
			}
		}
		return fail("No event for transfer %s reached Kafka within 20 seconds", transferId);
	}

	private UUID openAccount() {
		return accounts.save(Account.customer("Test", CurrencyCode.GBP)).getId();
	}

	private UUID fundedAccount(long amountMinor) {
		UUID id = openAccount();
		transferService.transfer(newKey(), new TransferRequest(EXTERNAL_GBP, id, amountMinor, GBP));
		return id;
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

}
