package dev.clearhouse.ledger.outbox;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Copies committed outbox events to Kafka.
 * <p>
 * Delivery is at-least-once: if the process dies after Kafka accepted a batch but before
 * it was marked published, the batch is sent again on the next poll. Consumers dedupe on
 * the envelope's {@code eventId}.
 */
@Component
class OutboxPublisher {

	private static final int BATCH_SIZE = 100;

	private final JdbcClient jdbc;

	private final KafkaTemplate<String, String> kafka;

	private final TransactionTemplate transaction;

	OutboxPublisher(JdbcClient jdbc, KafkaTemplate<String, String> kafka, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.kafka = kafka;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@Scheduled(fixedDelayString = "${clearhouse.outbox.poll-interval:500ms}")
	void publishPending() {
		Integer published;
		do {
			published = transaction.execute(status -> publishBatch());
		}
		while (published != null && published == BATCH_SIZE);
	}

	/**
	 * Sends the oldest unpublished events and marks them published, in one transaction that
	 * row-locks the batch. {@code SKIP LOCKED} means a second publisher would take the next
	 * batch instead of waiting. If any send fails, nothing is marked and the batch is retried.
	 */
	private int publishBatch() {
		List<PendingEvent> batch = jdbc.sql("""
				select id, topic, message_key, payload::text as payload
				from outbox_events
				where published_at is null
				order by position
				limit :limit
				for update skip locked
				""").param("limit", BATCH_SIZE).query(PendingEvent.class).list();
		if (batch.isEmpty()) {
			return 0;
		}

		CompletableFuture<?>[] sends = batch.stream()
			.map(event -> kafka.send(event.topic(), event.messageKey(), event.payload()))
			.toArray(CompletableFuture[]::new);
		CompletableFuture.allOf(sends).join();

		jdbc.sql("update outbox_events set published_at = now() where id in (:ids)")
			.param("ids", batch.stream().map(PendingEvent::id).toList())
			.update();
		return batch.size();
	}

	record PendingEvent(UUID id, String topic, String messageKey, String payload) {
	}

}
