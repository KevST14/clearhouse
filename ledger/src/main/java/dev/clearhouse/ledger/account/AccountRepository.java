package dev.clearhouse.ledger.account;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AccountRepository extends JpaRepository<Account, UUID> {

	/**
	 * Row-locks the accounts until the transaction ends. Locks are taken in id order, so
	 * two transfers between the same pair of accounts always queue instead of deadlocking.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Account a where a.id in :ids order by a.id")
	List<Account> lockAllById(Collection<UUID> ids);

}
