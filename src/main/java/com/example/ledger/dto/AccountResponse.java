package com.example.ledger.dto;

import com.example.ledger.domain.Account;

public record AccountResponse(
        Long id,
        String owner,
        Long balanceCents
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getOwner(),
                account.getBalanceCents()
        );
    }
}
