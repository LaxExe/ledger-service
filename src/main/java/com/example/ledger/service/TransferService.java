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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionTemplate transactionTemplate;

    public TransferService(
            AccountRepository accountRepository,
            TransferRepository transferRepository,
            LedgerEntryRepository ledgerEntryRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public TransferResponse transfer(String idempotencyKey, CreateTransferRequest request) {
        // Fast-path: return existing transfer if this key was already processed
        Optional<Transfer> existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return TransferResponse.from(existing.get());
        }

        try {
            return transactionTemplate.execute(status -> executeTransfer(idempotencyKey, request));
        } catch (DataIntegrityViolationException e) {
            // Concurrent race condition: another thread saved this idempotency key at the exact same instant.
            // Catch the database unique constraint violation and return the winner's transfer.
            return transferRepository.findByIdempotencyKey(idempotencyKey)
                    .map(TransferResponse::from)
                    .orElseThrow(() -> e);
        }
    }

    private TransferResponse executeTransfer(String idempotencyKey, CreateTransferRequest request) {
        // Double-check inside transaction boundary
        Optional<Transfer> existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return TransferResponse.from(existing.get());
        }

        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new InvalidTransferException("Cannot transfer money to the same account");
        }

        // Lock in fixed order (lowest id first) to prevent deadlocks under concurrent bidirectional transfers
        Long firstId = Math.min(request.fromAccountId(), request.toAccountId());
        Long secondId = Math.max(request.fromAccountId(), request.toAccountId());

        Account firstAccount = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new AccountNotFoundException(firstId));

        Account secondAccount = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new AccountNotFoundException(secondId));

        Account fromAccount = firstAccount.getId().equals(request.fromAccountId()) ? firstAccount : secondAccount;
        Account toAccount = firstAccount.getId().equals(request.toAccountId()) ? firstAccount : secondAccount;

        // Check funds
        if (fromAccount.getBalanceCents() < request.amountCents()) {
            throw new InsufficientFundsException(
                    fromAccount.getId(),
                    request.amountCents(),
                    fromAccount.getBalanceCents()
            );
        }

        // Move funds
        fromAccount.debit(request.amountCents());
        toAccount.credit(request.amountCents());

        // Save accounts
        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        // Record transfer
        Transfer transfer = new Transfer(
                fromAccount.getId(),
                toAccount.getId(),
                request.amountCents(),
                idempotencyKey
        );
        Transfer savedTransfer = transferRepository.saveAndFlush(transfer);

        // Double-entry bookkeeping: debit entry (-amount) and credit entry (+amount)
        LedgerEntry debitEntry = new LedgerEntry(savedTransfer, fromAccount, -request.amountCents());
        LedgerEntry creditEntry = new LedgerEntry(savedTransfer, toAccount, request.amountCents());

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        return TransferResponse.from(savedTransfer);
    }
}
