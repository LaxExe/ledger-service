# Ledger Service

[![CI](https://github.com/LaxExe/ledger-service/actions/workflows/ci.yml/badge.svg)](https://github.com/LaxExe/ledger-service/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](LICENSE)

A high-performance, double-entry banking ledger REST API built with **Java 21**, **Spring Boot 3**, and **PostgreSQL 16**. The service enforces mathematical balance conservation and guarantees correctness under high-contention concurrent traffic and duplicate network retries.

> [!IMPORTANT]
> **Live Interactive Site & Concurrency Simulator:** Visit the official documentation site to run interactive simulations of lost updates, deadlock prevention, and 10x parallel idempotency races directly in your browser:  
> [**https://laxexe.github.io/ledger-service/**](https://laxexe.github.io/ledger-service/)

---

## Table of Contents

- [Website & Live Simulator](#website--live-simulator)
- [Quickstart (Run in 3 Commands)](#quickstart-run-in-3-commands)
- [Core Business Problems It Solves](#core-business-problems-it-solves)
  - [Problem 1: Lost Updates Under Concurrent Traffic](#problem-1-lost-updates-under-concurrent-traffic)
  - [Problem 2: Double Charging from Network Retries](#problem-2-double-charging-from-network-retries)
- [System Architecture & Deep Dive](#system-architecture--deep-dive)
  - [1. High-Level Architectural Flow](#1-high-level-architectural-flow)
  - [2. Double-Entry Accounting Pipeline (Venetian Method)](#2-double-entry-accounting-pipeline-venetian-method)
  - [3. Pessimistic Row Locking (`SELECT ... FOR UPDATE`)](#3-pessimistic-row-locking-select--for-update)
  - [4. Deadlock Prevention via Global Lock Ordering](#4-deadlock-prevention-via-global-lock-ordering)
  - [5. Database-Enforced Idempotency & Collision Recovery](#5-database-enforced-idempotency--collision-recovery)
- [The Four Financial Invariants & Verification Matrix](#the-four-financial-invariants--verification-matrix)
- [REST API Reference](#rest-api-reference)
  - [1. Create Account (`POST /accounts`)](#1-create-account-post-accounts)
  - [2. Read Account (`GET /accounts/{id}`)](#2-read-account-get-accountsid)
  - [3. Execute Transfer (`POST /transfers`)](#3-execute-transfer-post-transfers)
  - [4. Service Health (`GET /health`)](#4-service-health-get-health)
- [Branches & Staged Learning Roadmap](#branches--staged-learning-roadmap)
- [Automated Test Suite & GitHub Actions CI](#automated-test-suite--github-actions-ci)
- [Interview Playbook & Resume Bullets](#interview-playbook--resume-bullets)
- [License](#license)

---

## Website & Live Simulator

Explore interactive diagrams, test account balances, and observe race condition simulations on the documentation website:
- **[https://laxexe.github.io/ledger-service/](https://laxexe.github.io/ledger-service/)**

---

## Quickstart (Run in 3 Commands)

### Prerequisites
- Docker & Docker Compose
- Java 21 SDK (or use included `./mvnw`)

```bash
# 1. Start isolated PostgreSQL 16 container
docker compose up -d

# 2. Boot the Spring Boot service
./mvnw spring-boot:run

# 3. Verify health in another terminal
curl -i http://localhost:8080/health
```

Expected response: `HTTP/1.1 200 OK` with body `ok`.

---

## Core Business Problems It Solves

### Problem 1: Lost Updates Under Concurrent Traffic
When multiple threads withdraw money from the same account simultaneously, naive `read-modify-write` sequences cause lost updates:
```
Thread 1: Reads balance $50  -----> Computes $49 -----> Writes $49
Thread 2: Reads balance $50  -----> Computes $49 -----> Writes $49 (OVERWRITES Thread 1!)
```
Two $1 withdrawals took place, but Alice's balance only dropped by $1! In our automated stress test firing 50 concurrent transfers without locking, **$44 of withdrawals vanished into thin air**.

### Problem 2: Double Charging from Network Retries
When mobile apps or payment gateways experience packet loss or timeouts, they retry requests:
```
Client: Sends Transfer A ($25) -----> Server executes transfer -----> Response packet dropped by network!
Client: Timed out! Retries Transfer A ($25) -----> Server executes transfer AGAIN! (Alice charged $50!)
```
Without strict idempotency backed by database constraints, users get double-charged.

---

## System Architecture & Deep Dive

### 1. High-Level Architectural Flow

```
                          ┌───────────────────────────┐
                          │   HTTP REST API Client    │
                          └─────────────┬─────────────┘
                                        │
           POST /transfers (Header: Idempotency-Key: uuid-123)
                                        │
                                        ▼
                          ┌───────────────────────────┐
                          │    TransferController     │
                          └─────────────┬─────────────┘
                                        │
                         Validates DTO & Header presence
                                        │
                                        ▼
                          ┌───────────────────────────┐
                          │      TransferService      │
                          └──────┬─────────────┬──────┘
                                 │             │
        Fast-Path Check:         │             │ Atomic Execution:
   Does Key Exist in DB?         │             │ TransactionTemplate
        ├── YES: Return Cached   │             │
        └── NO:  Continue ───────┘             ▼
                                   ┌───────────────────────────┐
                                   │  Pessimistic Lock Phase   │
                                   │  min(id1, id2) -> FOR UPDATE
                                   │  max(id1, id2) -> FOR UPDATE
                                   └─────────────┬─────────────┘
                                                 │
                                                 ▼
                                   ┌───────────────────────────┐
                                   │   Balance Invariant Check │
                                   │ fromBalance >= amount?    │
                                   └─────────────┬─────────────┘
                                                 │
                                                 ▼
                                   ┌───────────────────────────┐
                                   │    PostgreSQL Database    │
                                   │  1. Debit Sender          │
                                   │  2. Credit Receiver       │
                                   │  3. Insert Transfer       │
                                   │  4. Insert LedgerEntry (-)│
                                   │  5. Insert LedgerEntry (+)│
                                   └───────────────────────────┘
```

---

### 2. Double-Entry Accounting Pipeline (Venetian Method)

Every transfer is immutable and writes **two balanced entries**:

```
[Transfer #101] : Moving $30.00 from Alice (Account 1) to Bob (Account 2)
  ├── LedgerEntry 1: Account 1 | Amount: -$30.00 (Debit)
  └── LedgerEntry 2: Account 2 | Amount: +$30.00 (Credit)
  ────────────────────────────────────────────────────────
  Net Sum Across Ledger Entries: -$30.00 + +$30.00 = $0.00 (EXACT ZERO)
```

The sum of all ledger entries across the history of the company must always equal **0**.

---

### 3. Pessimistic Row Locking (`SELECT ... FOR UPDATE`)

To eliminate lost updates, `AccountRepository` issues row-level exclusive locks:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select a from Account a where a.id = :id")
Optional<Account> findByIdForUpdate(@Param("id") Long id);
```
Generated PostgreSQL query:
```sql
SELECT id, balance_cents, owner FROM accounts WHERE id = ? FOR NO KEY UPDATE;
```
Any competing transaction touching the same account is queued by the database engine until the active transaction commits.

---

### 4. Deadlock Prevention via Global Lock Ordering

Consider cross-transfers arriving simultaneously:
- **Transfer 1**: Moves money from **Account 1 &rarr; Account 2**
- **Transfer 2**: Moves money from **Account 2 &rarr; Account 1**

If Transfer 1 locks Account 1 first, and Transfer 2 locks Account 2 first, a **Deadlock** occurs.

**The Universal Fix**: Always sort account IDs before locking:
```java
Long firstId = Math.min(request.fromAccountId(), request.toAccountId());
Long secondId = Math.max(request.fromAccountId(), request.toAccountId());

Account first = accountRepository.findByIdForUpdate(firstId).orElseThrow(...);
Account second = accountRepository.findByIdForUpdate(secondId).orElseThrow(...);
```
Both threads compete for `Account 1` first. One wins, the other waits. Circular wait is eliminated.

---

### 5. Database-Enforced Idempotency & Collision Recovery

The `idempotency_key` column carries a PostgreSQL `UNIQUE` constraint:
```sql
ALTER TABLE transfers ADD CONSTRAINT uk_transfers_idempotency_key UNIQUE (idempotency_key);
```
If two identical requests arrive within microseconds:
1. Both pass the initial `existsByIdempotencyKey()` check.
2. Request 1 inserts the row and commits.
3. Request 2 attempts to insert and triggers error `23505 (unique_violation)`.
4. Spring's `TransactionTemplate` rolls back Request 2 cleanly.
5. Our catch block captures `DataIntegrityViolationException`, queries the database for Request 1's committed receipt, and returns it to Request 2.

Both callers receive `201 Created` with the **same transfer ID**, but Alice is charged **only once**.

---

## The Four Financial Invariants & Verification Matrix

| # | Invariant Rule | Enforcement Mechanism | Verifying Test Method |
| :-: | :--- | :--- | :--- |
| **1** | **Whole Cents Only (`BIGINT`)** | No floats/doubles; integer cents prevent IEEE 754 rounding drift. | `AccountTest.cannotCreateAccountWithNegativeBalance`<br>`TransferControllerTest.createTransferRejectsNegativeOrZeroAmount` |
| **2** | **Zero-Sum Ledger Entries** | Every transfer records 2 entries (`-amount` and `+amount`) summing to 0. | `TransferServiceTest.transferMovesMoneyAndCreatesTwoBalancedLedgerEntries`<br>`TransferIntegrationTest.normalTransferMovesMoneyAndWritesBalancedLedgerEntries` |
| **3** | **Idempotent Deduplication** | PostgreSQL `UNIQUE` constraint on `idempotency_key` with rollback recovery. | `TransferServiceTest.transferWithExistingIdempotencyKeyReturnsOriginalTransferWithoutMovingMoney`<br>`IdempotencyIntegrationTest.parallelDuplicateRequestsProduceExactlyOneTransfer` (10 threads) |
| **4** | **Balance Conservation & Non-Negative** | Pessimistic write locks with ordered acquisition prevent lost updates and deadlocks. | `ConcurrentTransferIntegrationTest.parallelTransfersWithPessimisticLockingSucceed` (50 threads)<br>`ConcurrentTransferIntegrationTest.randomParallelTransfersConserveTotalBalanceAndNoNegativeBalances` (20 consecutive runs) |

---

## REST API Reference

### 1. Create Account (`POST /accounts`)

```bash
curl -i -X POST http://localhost:8080/accounts \
  -H "Content-Type: application/json" \
  -d '{"owner": "Alice", "initialBalanceCents": 10000}'
```

**Response (201 Created)**:
```json
{
  "id": 1,
  "owner": "Alice",
  "balanceCents": 10000
}
```

---

### 2. Read Account (`GET /accounts/{id}`)

```bash
curl -i http://localhost:8080/accounts/1
```

**Response (200 OK)**:
```json
{
  "id": 1,
  "owner": "Alice",
  "balanceCents": 10000
}
```

---

### 3. Execute Transfer (`POST /transfers`)

```bash
curl -i -X POST http://localhost:8080/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 96e84f1b-4967-47ce-afad-3ca091d71abe" \
  -d '{
    "fromAccountId": 1,
    "toAccountId": 2,
    "amountCents": 2500
  }'
```

**Response (201 Created)**:
```json
{
  "id": 101,
  "fromAccountId": 1,
  "toAccountId": 2,
  "amountCents": 2500,
  "idempotencyKey": "96e84f1b-4967-47ce-afad-3ca091d71abe",
  "createdAt": "2026-11-07T16:00:00Z"
}
```

---

### 4. Service Health (`GET /health`)

```bash
curl -i http://localhost:8080/health
```

**Response (200 OK)**:
```text
ok
```

---

## Branches & Staged Learning Roadmap

Each developmental stage is preserved as a dedicated branch with its own targeted `README.md` and interview questions:

- [**`main`**](https://github.com/LaxExe/ledger-service/tree/main): The complete, production-ready system with all invariants, stress tests, documentation, and CI.
- [**`stage-1-accounts`**](https://github.com/LaxExe/ledger-service/tree/stage-1-accounts): Account entity, repository, DTO separation, and REST endpoints.
- [**`stage-2-transfers`**](https://github.com/LaxExe/ledger-service/tree/stage-2-transfers): Double-entry ledger entries, `@Transactional` boundaries, zero-sum invariant without locking.
- [**`stage-3-locking`**](https://github.com/LaxExe/ledger-service/tree/stage-3-locking): Demonstrating lost updates under 50 threads, fixing with `SELECT ... FOR UPDATE` and deadlock prevention.
- [**`stage-4-idempotency`**](https://github.com/LaxExe/ledger-service/tree/stage-4-idempotency): Idempotency-Key header, PostgreSQL unique constraint, 10-thread parallel race deduplication.
- [**`gh-pages`**](https://github.com/LaxExe/ledger-service/tree/gh-pages): Static documentation site hosting the interactive simulator.

---

## Automated Test Suite & GitHub Actions CI

Run all 44 test executions locally:
```bash
./mvnw clean test
```

### GitHub Actions Pipeline
Every push and pull request runs on Ubuntu with a live PostgreSQL 16 container:
- Checks out code with Java 21
- Starts PostgreSQL service container
- Executes `./mvnw --batch-mode clean verify`
- Status: **All 44 test executions pass cleanly with 0 failures and 0 errors**.

---

## Interview Playbook & Resume Bullets

### Resume Entry
```markdown
**Ledger Service** | *Java 21, Spring Boot, PostgreSQL, JUnit 5, Testcontainers, GitHub Actions, Docker*
• Built an idempotent double-entry ledger REST API in Java 21, Spring Boot, and PostgreSQL, where network retries with identical idempotency keys safely return cached transfers without duplicate charges.
• Eliminated lost updates and deadlock cycles using pessimistic row locking (SELECT FOR UPDATE) and ordered lock acquisition; verified with a 16-thread stress test across 1,250 concurrent bidirectional transfers with zero lost funds and 100% balance conservation.
• Engineered 23 automated unit and integration tests (44 test executions per build) and automated testing across pull requests using a GitHub Actions CI pipeline with live PostgreSQL containers.
```

### Elevator Pitch (One-Liner)
> *"It's a Spring Boot REST API on PostgreSQL that records money movements as double-entry ledger entries, and I focused on keeping it mathematically correct under concurrent race conditions and repeated network requests, which I proved with a multi-threaded parallel stress test."*

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
