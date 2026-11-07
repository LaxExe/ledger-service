package com.example.ledger.service;

import com.example.ledger.domain.Account;
import com.example.ledger.domain.LedgerEntry;
import com.example.ledger.domain.Transfer;
import com.example.ledger.dto.CreateTransferRequest;
import com.example.ledger.dto.TransferResponse;
import com.example.ledger.exception.AccountNotFoundException;
import com.example.ledger.exception.InsufficientFundsException;
import com.example.ledger.exception.InvalidTransferException;
import com.example.ledger.repository.AccountRepository;
import com.example.ledger.repository.LedgerEntryRepository;
import com.example.ledger.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(accountRepository, transferRepository, ledgerEntryRepository);
    }

    @Test
    void transferMovesMoneyAndCreatesTwoBalancedLedgerEntries() {
        Account fromAccount = new Account("Alice", 10000L);
        ReflectionTestUtils.setField(fromAccount, "id", 1L);

        Account toAccount = new Account("Bob", 2000L);
        ReflectionTestUtils.setField(toAccount, "id", 2L);

        when(accountRepository.findById(1L)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(toAccount));

        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer t = invocation.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 100L);
            return t;
        });

        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 3000L);
        TransferResponse response = transferService.transfer("idem-1", request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.amountCents()).isEqualTo(3000L);
        assertThat(fromAccount.getBalanceCents()).isEqualTo(7000L);
        assertThat(toAccount.getBalanceCents()).isEqualTo(5000L);

        // Verify two ledger entries were saved: one debit (-3000), one credit (+3000)
        ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(entryCaptor.capture());

        List<LedgerEntry> entries = entryCaptor.getAllValues();
        assertThat(entries).hasSize(2);

        LedgerEntry debitEntry = entries.get(0);
        LedgerEntry creditEntry = entries.get(1);

        assertThat(debitEntry.getAmountCents()).isEqualTo(-3000L);
        assertThat(creditEntry.getAmountCents()).isEqualTo(3000L);
        assertThat(debitEntry.getAmountCents() + creditEntry.getAmountCents()).isEqualTo(0L);
    }

    @Test
    void transferFailsWhenInsufficientFunds() {
        Account fromAccount = new Account("Alice", 1000L);
        ReflectionTestUtils.setField(fromAccount, "id", 1L);

        Account toAccount = new Account("Bob", 2000L);
        ReflectionTestUtils.setField(toAccount, "id", 2L);

        when(accountRepository.findById(1L)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(toAccount));

        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 5000L);

        assertThatThrownBy(() -> transferService.transfer("idem-2", request))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("insufficient funds");

        assertThat(fromAccount.getBalanceCents()).isEqualTo(1000L);
        assertThat(toAccount.getBalanceCents()).isEqualTo(2000L);
        verifyNoInteractions(transferRepository, ledgerEntryRepository);
    }

    @Test
    void transferFailsWhenSameAccount() {
        CreateTransferRequest request = new CreateTransferRequest(1L, 1L, 500L);

        assertThatThrownBy(() -> transferService.transfer("idem-3", request))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessageContaining("same account");

        verifyNoInteractions(accountRepository, transferRepository, ledgerEntryRepository);
    }

    @Test
    void transferFailsWhenAccountNotFound() {
        when(accountRepository.findById(1L)).thenReturn(Optional.empty());

        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, 500L);

        assertThatThrownBy(() -> transferService.transfer("idem-4", request))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(transferRepository, ledgerEntryRepository);
    }
}
