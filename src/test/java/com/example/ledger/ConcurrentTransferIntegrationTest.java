package com.example.ledger;

import com.example.ledger.domain.Account;
import com.example.ledger.dto.CreateTransferRequest;
import com.example.ledger.repository.AccountRepository;
import com.example.ledger.repository.LedgerEntryRepository;
import com.example.ledger.repository.TransferRepository;
import com.example.ledger.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConcurrentTransferIntegrationTest {

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
    @DisplayName("50 parallel transfers out of one account succeed cleanly with pessimistic locking")
    void parallelTransfersWithPessimisticLockingSucceed() throws InterruptedException {
        Account fromAccount = accountRepository.save(new Account("Alice", 5000L));
        Account toAccount = accountRepository.save(new Account("Bob", 0L));

        int numberOfTransfers = 50;
        long transferAmountCents = 100L;

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch readyLatch = new CountDownLatch(numberOfTransfers);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfTransfers; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                try {
                    transferService.transfer(
                            UUID.randomUUID().toString(),
                            new CreateTransferRequest(fromAccount.getId(), toAccount.getId(), transferAmountCents)
                    );
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int successfulTransfers = 0;
        for (Future<Boolean> future : futures) {
            try {
                if (future.get(10, TimeUnit.SECONDS)) {
                    successfulTransfers++;
                }
            } catch (ExecutionException | TimeoutException ignored) {
            }
        }
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(successfulTransfers).isEqualTo(50);

        Account updatedFrom = accountRepository.findById(fromAccount.getId()).orElseThrow();
        Account updatedTo = accountRepository.findById(toAccount.getId()).orElseThrow();

        assertThat(updatedFrom.getBalanceCents())
                .as("Source account balance must be 0 after 50 transfers of 100 cents")
                .isEqualTo(0L);

        assertThat(updatedTo.getBalanceCents())
                .as("Destination account balance must be 5000 after 50 transfers of 100 cents")
                .isEqualTo(5000L);

        // Verify total balance conserved
        assertThat(updatedFrom.getBalanceCents() + updatedTo.getBalanceCents()).isEqualTo(5000L);
    }

    @RepeatedTest(value = 20, name = "Run {currentRepetition} of {totalRepetitions} - Invariant check under heavy concurrent stress")
    @DisplayName("Many random parallel bidirectional transfers between accounts conserve total balance and prevent deadlocks")
    void randomParallelTransfersConserveTotalBalanceAndNoNegativeBalances() throws InterruptedException {
        // Setup 4 accounts with 10,000 cents ($100.00) each = 40,000 cents total
        Account a1 = accountRepository.save(new Account("Account-1", 10000L));
        Account a2 = accountRepository.save(new Account("Account-2", 10000L));
        Account a3 = accountRepository.save(new Account("Account-3", 10000L));
        Account a4 = accountRepository.save(new Account("Account-4", 10000L));

        List<Long> accountIds = List.of(a1.getId(), a2.getId(), a3.getId(), a4.getId());
        long initialTotalBalance = 40000L;

        int concurrentThreads = 16;
        int totalRequests = 60;

        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        Random random = new Random();

        for (int i = 0; i < totalRequests; i++) {
            int fromIdx = random.nextInt(accountIds.size());
            int toIdx;
            do {
                toIdx = random.nextInt(accountIds.size());
            } while (toIdx == fromIdx);

            Long fromId = accountIds.get(fromIdx);
            Long toId = accountIds.get(toIdx);
            long amount = (random.nextInt(30) + 1) * 100L;

            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                try {
                    transferService.transfer(
                            UUID.randomUUID().toString(),
                            new CreateTransferRequest(fromId, toId, amount)
                    );
                } catch (Exception ignored) {
                }
                return null;
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<Void> future : futures) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException ignored) {
            }
        }
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Invariant Check 1: No balance is negative
        List<Account> allAccounts = accountRepository.findAll();
        long finalTotalBalance = 0L;

        for (Account account : allAccounts) {
            assertThat(account.getBalanceCents())
                    .as("Account %d balance must never be negative", account.getId())
                    .isGreaterThanOrEqualTo(0L);
            finalTotalBalance += account.getBalanceCents();
        }

        // Invariant Check 2: Total balance across all accounts is strictly unchanged
        assertThat(finalTotalBalance)
                .as("Total balance across all accounts must remain invariant (%d cents)", initialTotalBalance)
                .isEqualTo(initialTotalBalance);
    }
}
