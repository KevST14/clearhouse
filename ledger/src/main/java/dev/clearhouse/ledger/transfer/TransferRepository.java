package dev.clearhouse.ledger.transfer;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

	Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

}
