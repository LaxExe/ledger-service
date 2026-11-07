package com.example.ledger;

import com.example.ledger.domain.Account;
import com.example.ledger.domain.LedgerEntry;
import com.example.ledger.domain.Transfer;
import com.example.ledger.dto.CreateTransferRequest;
import com.example.ledger.dto.TransferResponse;
import com.example.ledger.repository.AccountRepository;
import com.example.ledger.repository.LedgerEntryRepository;
import com.example.ledger.repository.TransferRepository;
import com.example.ledger.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TransferIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @BeforeEach
    void cleanDatabase() {
        ledgerEntryRepository.deleteAll();
        transferRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    @DisplayName("Normal transfer moves money and creates double-entry ledger records summing to zero")
    void normalTransferMovesMoneyAndWritesBalancedLedgerEntries() {
        Account alice = accountRepository.save(new Account("Alice", 10000L));
        Account bob = accountRepository.save(new Account("Bob", 2000L));

        CreateTransferRequest request = new CreateTransferRequest(alice.getId(), bob.getId(), 3000L);
        TransferResponse response = transferService.transfer("norm-key-1", request);

        assertThat(response).isNotNull();
        assertThat(response.fromAccountId()).isEqualTo(alice.getId());
        assertThat(response.toAccountId()).isEqualTo(bob.getId());
        assertThat(response.amountCents()).isEqualTo(3000L);

        // Verify Account Balances
        Account updatedAlice = accountRepository.findById(alice.getId()).orElseThrow();
        Account updatedBob = accountRepository.findById(bob.getId()).orElseThrow();
        assertThat(updatedAlice.getBalanceCents()).isEqualTo(7000L);
        assertThat(updatedBob.getBalanceCents()).isEqualTo(5000L);

        // Verify Double-Entry Ledger Entries
        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        assertThat(entries).hasSize(2);

        long sum = entries.stream().mapToLong(LedgerEntry::getAmountCents).sum();
        assertThat(sum).as("Ledger entries must sum to zero").isEqualTo(0L);

        // Replay same idempotency key (idempotent replay)
        TransferResponse replayResponse = transferService.transfer("norm-key-1", request);
        assertThat(replayResponse.id()).isEqualTo(response.id());

        // Ensure balances did not change on replay
        Account recheckAlice = accountRepository.findById(alice.getId()).orElseThrow();
        Account recheckBob = accountRepository.findById(bob.getId()).orElseThrow();
        assertThat(recheckAlice.getBalanceCents()).isEqualTo(7000L);
        assertThat(recheckBob.getBalanceCents()).isEqualTo(5000L);

        // Ensure no new ledger entries were written
        assertThat(ledgerEntryRepository.findAll()).hasSize(2);
    }
}
