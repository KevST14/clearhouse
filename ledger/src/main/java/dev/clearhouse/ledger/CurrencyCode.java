package dev.clearhouse.ledger;

/**
 * Currencies the ledger supports. Every amount is stored in the currency's minor unit
 * (pence, cents) as a {@code long}, never as a floating-point number.
 */
public enum CurrencyCode {
	GBP, EUR, USD
}
