package com.example.ledger.dto;

import com.example.ledger.domain.Transfer;
import java.time.Instant;

public record TransferResponse(
        Long id,
        Long fromAccountId,
        Long toAccountId,
        Long amountCents,
        String idempotencyKey,
        Instant createdAt
) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getFromAccountId(),
                transfer.getToAccountId(),
                transfer.getAmountCents(),
                transfer.getIdempotencyKey(),
                transfer.getCreatedAt()
        );
    }
}
