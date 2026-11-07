# Ledger Service

[![CI](https://github.com/LaxExe/ledger-service/actions/workflows/ci.yml/badge.svg)](https://github.com/LaxExe/ledger-service/actions/workflows/ci.yml)

A lightweight, double-entry banking ledger REST API built with Java 21, Spring Boot, and PostgreSQL. It enforces strict financial invariants and remains correct under high-contention concurrent traffic and duplicate requests.

---

## The Problem It Solves

In financial and ledger systems, two critical edge cases cause silent data corruption and monetary loss:
1. **Lost Updates Under Concurrency**: When two requests attempt to debit or credit the same account at the same time, naive read-modify-write patterns read stale balances and overwrite each other's changes.
2. **Duplicate Transfers Under Network Retries**: When mobile apps or payment clients experience network drops or timeouts, they retry requests. Without idempotency, retried requests double-charge customers.

This service solves both problems using **pessimistic row locking with ordered lock acquisition** and **database-enforced idempotency deduplication**.

---

## Quickstart (Run in 3 Commands)

1. **Start PostgreSQL**:
   ```bash
   docker compose up -d
   ```

2. **Run the Application**:
   ```bash
   ./mvnw spring-boot:run
   ```

3. **Verify Health**:
   ```bash
   curl -i http://localhost:8080/health
   ```

---

## Core Invariants & Test Verification

The service satisfies four non-negotiable rules. Each rule is guaranteed by an automated test:

| Rule | Enforcement Mechanism | Verifying Test |
| :--- | :--- | :--- |
| **1. Money is stored as whole cents in `BIGINT`** | Never use `float` or `double`. Domain validation rejects negative balances and fractional values. | `AccountTest.cannotCreateAccountWithNegativeBalance`<br>`TransferControllerTest.createTransferRejectsNegativeOrZeroAmount` |
| **2. Every transfer writes two ledger entries that sum to zero** | Classical Venetian double-entry bookkeeping: a debit (`-amount`) and credit (`+amount`) committed atomically within `@Transactional`. | `TransferServiceTest.transferMovesMoneyAndCreatesTwoBalancedLedgerEntries`<br>`TransferIntegrationTest.normalTransferMovesMoneyAndWritesBalancedLedgerEntries` |
| **3. The same idempotency key sent twice produces one transfer** | PostgreSQL `UNIQUE` constraint on `idempotency_key` with transactional rollback and replay caching. | `TransferServiceTest.transferWithExistingIdempotencyKeyReturnsOriginalTransferWithoutMovingMoney`<br>`IdempotencyIntegrationTest.parallelDuplicateRequestsProduceExactlyOneTransfer` (10 threads racing) |
| **4. Total system balance never changes, and no balance goes negative** | `SELECT ... FOR UPDATE` pessimistic write locks acquired in ascending ID order (`min(id1, id2)` before `max(id1, id2)`). | `ConcurrentTransferIntegrationTest.parallelTransfersWithPessimisticLockingSucceed` (50 threads)<br>`ConcurrentTransferIntegrationTest.randomParallelTransfersConserveTotalBalanceAndNoNegativeBalances` (20 consecutive runs) |

---

## Architecture & Design Decisions

### 1. Whole Cents (`BIGINT`) vs Floating Point
IEEE 754 floating-point numbers (`float`, `double`) cannot accurately represent decimal fractions like `0.10` or `0.01` in binary. Over time, floating-point rounding errors accumulate into phantom money creation or loss. Storing currency as whole cents (`Long` / `BIGINT`) provides exact integer arithmetic without precision loss.

### 2. Pessimistic Locking (`SELECT ... FOR UPDATE`) vs Optimistic Locking
Optimistic locking (`@Version`) works well when collision rates are low. However, on high-contention bank accounts (e.g. high-volume merchant accounts or payroll accounts), optimistic locking causes excessive transaction rollbacks and retry storms. Pessimistic locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) serializes operations at the database row level, queuing incoming requests smoothly without wasting CPU on failed retries.

### 3. Ordered Lock Acquisition (Deadlock Prevention)
If Request 1 transfers from Account A to B, while Request 2 transfers from Account B to A, an un-ordered locking strategy can lock A and B in opposite order, creating a classic circular wait (Deadlock). By always acquiring locks in ascending ID order:
```java
Long firstId = Math.min(fromId, toId);
Long secondId = Math.max(fromId, toId);
```
both requests compete for the lowest ID first. One wins, the other waits, and deadlocks are mathematically eliminated.

### 4. Database-Enforced Idempotency
Application-level checks like `if (repository.existsByKey(key))` fail when two identical requests hit the server concurrently within milliseconds. A database `UNIQUE` constraint on `idempotency_key` guarantees atomicity at the index level: the second request triggers a constraint violation, rolls back cleanly, and retrieves the first request's committed receipt.

---

## API Reference

### Create Account
```bash
POST /accounts
Content-Type: application/json

{
  "owner": "Alice",
  "initialBalanceCents": 10000
}
```
**Response (201 Created)**:
```json
{
  "id": 1,
  "owner": "Alice",
  "balanceCents": 10000
}
```

### Read Account
```bash
GET /accounts/1
```
**Response (200 OK)**:
```json
{
  "id": 1,
  "owner": "Alice",
  "balanceCents": 10000
}
```

### Create Transfer
```bash
POST /transfers
Content-Type: application/json
Idempotency-Key: 7b31238f-a9cb-48d1-96f3-e51c89be6f38

{
  "fromAccountId": 1,
  "toAccountId": 2,
  "amountCents": 2500
}
```
**Response (201 Created)**:
```json
{
  "id": 1,
  "fromAccountId": 1,
  "toAccountId": 2,
  "amountCents": 2500,
  "idempotencyKey": "7b31238f-a9cb-48d1-96f3-e51c89be6f38",
  "createdAt": "2026-11-07T16:00:00Z"
}
```

---

## Automated Test Suite

Run all tests:
```bash
./mvnw verify
```

The test suite contains **23 test methods** (**44 test executions per build**), all executed against real PostgreSQL in CI via GitHub Actions.
