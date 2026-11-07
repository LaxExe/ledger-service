package com.example.ledger.controller;

import com.example.ledger.dto.CreateTransferRequest;
import com.example.ledger.dto.TransferResponse;
import com.example.ledger.exception.AccountNotFoundException;
import com.example.ledger.exception.InsufficientFundsException;
import com.example.ledger.exception.InvalidTransferException;
import com.example.ledger.service.TransferService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransferService transferService;

    @Test
    void createTransferReturnsCreated() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 2500L);
        TransferResponse response = new TransferResponse(10L, 1L, 2L, 2500L, "key-123", Instant.now());

        when(transferService.transfer(eq("key-123"), any(CreateTransferRequest.class))).thenReturn(response);

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.fromAccountId").value(1))
                .andExpect(jsonPath("$.toAccountId").value(2))
                .andExpect(jsonPath("$.amountCents").value(2500))
                .andExpect(jsonPath("$.idempotencyKey").value("key-123"));
    }

    @Test
    void createTransferRejectsNegativeOrZeroAmount() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 0L);

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.amountCents").exists());
    }

    @Test
    void createTransferReturns400OnMissingIdempotencyHeader() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 500L);

        mockMvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTransferReturns400OnInsufficientFunds() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 50000L);
        when(transferService.transfer(any(), any(CreateTransferRequest.class)))
                .thenThrow(new InsufficientFundsException(1L, 50000L, 1000L));

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Account 1 has insufficient funds. Required: 50000 cents, Available: 1000 cents"));
    }

    @Test
    void createTransferReturns404WhenAccountNotFound() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(99L, 2L, 1000L);
        when(transferService.transfer(any(), any(CreateTransferRequest.class)))
                .thenThrow(new AccountNotFoundException(99L));

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Account not found with id: 99"));
    }

    @Test
    void createTransferReturns400WhenSameAccount() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(1L, 1L, 1000L);
        when(transferService.transfer(any(), any(CreateTransferRequest.class)))
                .thenThrow(new InvalidTransferException("Cannot transfer money to the same account"));

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "key-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Cannot transfer money to the same account"));
    }
}
