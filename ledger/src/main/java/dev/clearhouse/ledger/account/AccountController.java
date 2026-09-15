package dev.clearhouse.ledger.account;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import dev.clearhouse.ledger.CurrencyCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
class AccountController {

	private final AccountRepository accounts;

	AccountController(AccountRepository accounts) {
		this.accounts = accounts;
	}

	@PostMapping
	ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
		Account account = accounts.save(Account.customer(request.ownerName(), request.currency()));
		return ResponseEntity.created(URI.create("/accounts/" + account.getId())).body(AccountResponse.from(account));
	}

	@GetMapping("/{id}")
	AccountResponse get(@PathVariable UUID id) {
		return accounts.findById(id).map(AccountResponse::from).orElseThrow(() -> new AccountNotFoundException(id));
	}

	record OpenAccountRequest(@NotBlank @Size(max = 200) String ownerName, @NotNull CurrencyCode currency) {
	}

	record AccountResponse(UUID id, String ownerName, AccountKind kind, CurrencyCode currency, long balanceMinor,
			Instant createdAt) {

		static AccountResponse from(Account account) {
			return new AccountResponse(account.getId(), account.getOwnerName(), account.getKind(),
					account.getCurrency(), account.getBalanceMinor(), account.getCreatedAt());
		}

	}

}
