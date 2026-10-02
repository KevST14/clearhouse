package dev.clearhouse.ledger;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/** Rules that must hold for the whole database after any sequence of transfers. */
public final class LedgerInvariants {

	private LedgerInvariants() {
	}

	public static void assertBooksBalance(JdbcClient jdbc) {
		assertThat(jdbc.sql("""
				select transfer_id from ledger_entries
				group by transfer_id
				having count(*) <> 2 or sum(amount_minor) <> 0
				""").query(UUID.class).list())
			.as("transfers whose entries don't net to zero")
			.isEmpty();

		assertThat(jdbc.sql("""
				select a.id from accounts a
				left join ledger_entries e on e.account_id = a.id
				group by a.id, a.balance_minor
				having a.balance_minor <> coalesce(sum(e.amount_minor), 0)
				""").query(UUID.class).list())
			.as("accounts whose balance differs from the sum of their entries")
			.isEmpty();

		assertThat(jdbc.sql("""
				select currency from accounts
				group by currency
				having sum(balance_minor) <> 0
				""").query(String.class).list())
			.as("currencies where money was created or destroyed")
			.isEmpty();

		assertThat(jdbc.sql("""
				select t.id from transfers t
				left join outbox_events o on o.payload -> 'data' ->> 'transferId' = t.id::text
				group by t.id
				having count(o.id) <> 1
				""").query(UUID.class).list())
			.as("transfers without exactly one outbox event")
			.isEmpty();

		assertThat(jdbc.sql("""
				select o.id from outbox_events o
				left join transfers t on t.id::text = o.payload -> 'data' ->> 'transferId'
				where t.id is null
				""").query(UUID.class).list())
			.as("outbox events for transfers that were rolled back")
			.isEmpty();
	}

}
