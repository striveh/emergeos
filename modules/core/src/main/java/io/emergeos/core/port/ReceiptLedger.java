package io.emergeos.core.port;

import io.emergeos.core.domain.Receipt;
import java.util.Optional;

public interface ReceiptLedger {

  void append(Receipt receipt);

  Optional<Receipt> findByIdempotencyKey(String idempotencyKey);
}

