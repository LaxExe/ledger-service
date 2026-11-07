package com.example.ledger.controller;

import com.example.ledger.dto.CreateTransferRequest;
import com.example.ledger.dto.TransferResponse;
import com.example.ledger.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required and cannot be blank");
        }

        TransferResponse response = transferService.transfer(idempotencyKey.trim(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
