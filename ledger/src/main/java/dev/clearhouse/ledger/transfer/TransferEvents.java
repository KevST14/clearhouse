package dev.clearhouse.ledger.transfer;

import java.time.Instant;
import java.util.UUID;

import dev.clearhouse.ledger.CurrencyCode;
import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** The events the ledger publishes about transfers, and the topic they go to. */
@Configuration(proxyBeanMethods = false)
class TransferEvents {

	static final String TOPIC = "ledger.transfers";

	static final String TRANSFER_CREATED = "transfer.created";

	/**
	 * Keyed by the paying account, so each account's outgoing transfers land on one
	 * partition and consumers see them in order.
	 */
	@Bean
	NewTopic transfersTopic() {
		// One replica suits the single-node local Redpanda; production would use three.
		return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
	}

	record TransferCreated(UUID transferId, UUID fromAccountId, UUID toAccountId, long amountMinor,
			CurrencyCode currency, Instant createdAt) {

		static TransferCreated from(Transfer transfer) {
			return new TransferCreated(transfer.getId(), transfer.getFromAccountId(), transfer.getToAccountId(),
					transfer.getAmountMinor(), transfer.getCurrency(), transfer.getCreatedAt());
		}

	}

}
