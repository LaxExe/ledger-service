package com.example.ledger.controller;

import com.example.ledger.dto.AccountResponse;
import com.example.ledger.dto.CreateAccountRequest;
import com.example.ledger.exception.AccountNotFoundException;
import com.example.ledger.service.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AccountService accountService;

    @Test
    void createAccountReturnsCreatedWithPayload() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest("Alice", 10000L);
        AccountResponse response = new AccountResponse(1L, "Alice", 10000L);

        when(accountService.createAccount(any(CreateAccountRequest.class))).thenReturn(response);

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/accounts/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.owner").value("Alice"))
                .andExpect(jsonPath("$.balanceCents").value(10000));
    }

    @Test
    void createAccountRejectsBlankOwner() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest("", 1000L);

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.owner").exists());
    }

    @Test
    void createAccountRejectsNegativeBalance() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest("Alice", -50L);

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.initialBalanceCents").exists());
    }

    @Test
    void getAccountReturnsAccountWhenFound() throws Exception {
        AccountResponse response = new AccountResponse(1L, "Alice", 10000L);
        when(accountService.getAccount(1L)).thenReturn(response);

        mockMvc.perform(get("/accounts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.owner").value("Alice"))
                .andExpect(jsonPath("$.balanceCents").value(10000));
    }

    @Test
    void getAccountReturns404WhenNotFound() throws Exception {
        when(accountService.getAccount(999L)).thenThrow(new AccountNotFoundException(999L));

        mockMvc.perform(get("/accounts/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Account not found with id: 999"));
    }
}
