package dev.clearhouse.ledger.transfer;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.clearhouse.ledger.CurrencyCode;
import dev.clearhouse.ledger.account.AccountNotFoundException;
import dev.clearhouse.ledger.account.AccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TransferController {

	private final TransferService transferService;

	private final TransferRepository transfers;

	private final LedgerEntryRepository entries;

	private final AccountRepository accounts;

	TransferController(TransferService transferService, TransferRepository transfers, LedgerEntryRepository entries,
			AccountRepository accounts) {
		this.transferService = transferService;
		this.transfers = transfers;
		this.entries = entries;
		this.accounts = accounts;
	}

	@PostMapping("/transfers")
	ResponseEntity<TransferResponse> create(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
			@Valid @RequestBody TransferRequest request) {
		TransferResult result = transferService.transfer(idempotencyKey, request);
		TransferResponse body = TransferResponse.from(result.transfer());
		if (result.replayed()) {
			return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
		}
		return ResponseEntity.created(URI.create("/transfers/" + body.id())).body(body);
	}

	@GetMapping("/transfers/{id}")
	TransferResponse get(@PathVariable UUID id) {
		return transfers.findById(id).map(TransferResponse::from).orElseThrow(() -> new TransferNotFoundException(id));
	}

	/** The account's most recent 100 ledger entries, newest first. */
	@GetMapping("/accounts/{id}/entries")
	List<EntryResponse> entries(@PathVariable UUID id) {
		if (!accounts.existsById(id)) {
			throw new AccountNotFoundException(id);
		}
		return entries.findTop100ByAccountIdOrderByCreatedAtDesc(id).stream().map(EntryResponse::from).toList();
	}

	record TransferResponse(UUID id, UUID fromAccountId, UUID toAccountId, long amountMinor, CurrencyCode currency,
			Instant createdAt) {

		static TransferResponse from(Transfer transfer) {
			return new TransferResponse(transfer.getId(), transfer.getFromAccountId(), transfer.getToAccountId(),
					transfer.getAmountMinor(), transfer.getCurrency(), transfer.getCreatedAt());
		}

	}

	record EntryResponse(UUID transferId, long amountMinor, Instant createdAt) {

		static EntryResponse from(LedgerEntry entry) {
			return new EntryResponse(entry.getTransferId(), entry.getAmountMinor(), entry.getCreatedAt());
		}

	}

}
