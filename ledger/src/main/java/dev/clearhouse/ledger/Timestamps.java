package dev.clearhouse.ledger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Timestamps {

	private Timestamps() {
	}

	/**
	 * The current time at the microsecond precision Postgres stores. On Linux
	 * {@link Instant#now()} has nanoseconds, so without this a freshly created record would
	 * report a different timestamp than the same record read back from the database.
	 */
	public static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

}
