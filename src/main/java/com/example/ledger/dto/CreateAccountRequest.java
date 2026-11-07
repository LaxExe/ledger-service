package com.example.ledger.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAccountRequest(
        @NotBlank(message = "Owner name is required")
        String owner,

        @NotNull(message = "Initial balance is required")
        @Min(value = 0, message = "Initial balance cannot be negative")
        Long initialBalanceCents
) {
}
