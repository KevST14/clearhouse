package dev.clearhouse.ledger.outbox;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records events to be published to Kafka once the surrounding transaction commits. */
@Component
public class Outbox {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	Outbox(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * Must run inside the transaction that makes the change, which is the whole point: if
	 * that transaction rolls back, the event goes with it.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void append(String topic, String key, String eventType, Instant occurredAt, Object data) {
		UUID eventId = UUID.randomUUID();
		String payload = json.writeValueAsString(new Envelope(eventId, eventType, occurredAt, data));
		jdbc.sql("""
				insert into outbox_events (id, topic, message_key, payload)
				values (:id, :topic, :key, cast(:payload as jsonb))
				""")
			.param("id", eventId)
			.param("topic", topic)
			.param("key", key)
			.param("payload", payload)
			.update();
	}

	/** What consumers receive. {@code eventId} is stable across redeliveries, so they can dedupe on it. */
	record Envelope(UUID eventId, String eventType, Instant occurredAt, Object data) {
	}

}
