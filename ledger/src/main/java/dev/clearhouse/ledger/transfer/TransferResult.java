package dev.clearhouse.ledger.transfer;

/**
 * @param replayed true when the idempotency key had already been used, so no money moved
 * and this is the transfer created by the original request
 */
public record TransferResult(Transfer transfer, boolean replayed) {
}
