package dev.clearhouse.ledger.transfer;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

	List<LedgerEntry> findTop100ByAccountIdOrderByCreatedAtDesc(UUID accountId);

}
