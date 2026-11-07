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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IdempotencyIntegrationTest {

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
    @DisplayName("Sending the same idempotency key 10 times in parallel creates exactly 1 transfer and deducts funds only once")
    void parallelDuplicateRequestsProduceExactlyOneTransfer() throws InterruptedException, ExecutionException, TimeoutException {
        Account alice = accountRepository.save(new Account("Alice", 10000L));
        Account bob = accountRepository.save(new Account("Bob", 1000L));

        String sharedIdempotencyKey = "idemp-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(alice.getId(), bob.getId(), 2500L);

        int numberOfParallelRequests = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfParallelRequests);
        CountDownLatch readyLatch = new CountDownLatch(numberOfParallelRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<TransferResponse>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfParallelRequests; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                // Release all 10 threads simultaneously to race for the same key
                startLatch.await();
                return transferService.transfer(sharedIdempotencyKey, request);
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        List<TransferResponse> responses = new ArrayList<>();
        for (Future<TransferResponse> future : futures) {
            responses.add(future.get(10, TimeUnit.SECONDS));
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // All 10 callers received a valid response
        assertThat(responses).hasSize(10);

        // All 10 responses return the exact same transfer ID
        Set<Long> transferIds = responses.stream().map(TransferResponse::id).collect(Collectors.toSet());
        assertThat(transferIds).hasSize(1);
        Long uniqueTransferId = transferIds.iterator().next();

        // All 10 responses have the exact same idempotency key and amount
        for (TransferResponse resp : responses) {
            assertThat(resp.idempotencyKey()).isEqualTo(sharedIdempotencyKey);
            assertThat(resp.amountCents()).isEqualTo(2500L);
        }

        // Database Verification 1: Exactly 1 transfer record exists in the table
        List<Transfer> transfers = transferRepository.findAll();
        assertThat(transfers).hasSize(1);
        assertThat(transfers.get(0).getId()).isEqualTo(uniqueTransferId);
        assertThat(transfers.get(0).getIdempotencyKey()).isEqualTo(sharedIdempotencyKey);

        // Database Verification 2: Exactly 2 ledger entries exist (-2500 and +2500, sum = 0)
        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        assertThat(entries).hasSize(2);
        long entrySum = entries.stream().mapToLong(LedgerEntry::getAmountCents).sum();
        assertThat(entrySum).isEqualTo(0L);

        // Database Verification 3: Funds were deducted and credited exactly once
        Account updatedAlice = accountRepository.findById(alice.getId()).orElseThrow();
        Account updatedBob = accountRepository.findById(bob.getId()).orElseThrow();

        assertThat(updatedAlice.getBalanceCents())
                .as("Alice should only be debited 2500 once (10000 - 2500 = 7500)")
                .isEqualTo(7500L);

        assertThat(updatedBob.getBalanceCents())
                .as("Bob should only be credited 2500 once (1000 + 2500 = 3500)")
                .isEqualTo(3500L);

        // Total balance across both accounts is conserved
        assertThat(updatedAlice.getBalanceCents() + updatedBob.getBalanceCents()).isEqualTo(11000L);
    }
}
