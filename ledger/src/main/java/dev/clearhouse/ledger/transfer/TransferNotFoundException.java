package dev.clearhouse.ledger.transfer;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

	TransferNotFoundException(UUID transferId) {
		super("No transfer with id " + transferId);
	}

}
