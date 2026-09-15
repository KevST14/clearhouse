package dev.clearhouse.ledger.account;

public enum AccountKind {

	/** Belongs to a customer. Can never go below zero. */
	CUSTOMER,

	/**
	 * Stands for the world outside the ledger, one per currency. Deposits are transfers
	 * out of it, so it goes negative; the sum of every balance in a currency stays zero.
	 */
	EXTERNAL
}
