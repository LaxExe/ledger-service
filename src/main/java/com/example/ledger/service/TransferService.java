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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public TransferService(
            AccountRepository accountRepository,
            TransferRepository transferRepository,
            LedgerEntryRepository ledgerEntryRepository
    ) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public TransferResponse transfer(String idempotencyKey, CreateTransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new InvalidTransferException("Cannot transfer money to the same account");
        }

        // Load source account
        Account fromAccount = accountRepository.findById(request.fromAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.fromAccountId()));

        // Load destination account
        Account toAccount = accountRepository.findById(request.toAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.toAccountId()));

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
        Transfer savedTransfer = transferRepository.save(transfer);

        // Double-entry bookkeeping: debit entry (-amount) and credit entry (+amount)
        LedgerEntry debitEntry = new LedgerEntry(savedTransfer, fromAccount, -request.amountCents());
        LedgerEntry creditEntry = new LedgerEntry(savedTransfer, toAccount, request.amountCents());

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        return TransferResponse.from(savedTransfer);
    }
}
