# Stage 4: Distributed Idempotency & Replay Protection

> **Milestone 4 of 6** in the [Ledger Service](https://github.com/LaxExe/ledger-service) reference architecture.  
> Live Documentation & Concurrency Simulator: [laxexe.github.io/ledger-service](https://laxexe.github.io/ledger-service/)

---

## Overview

In distributed systems, networks are inherently unreliable. When an API client calls `POST /api/v1/transfers` and experiences a network timeout or connection reset (TCP RST, 504 Gateway Timeout), the client has no way to know whether:
1. The request was dropped **before** reaching the server (transfer not executed).
2. The server executed the transfer, but the response was dropped **on the return journey**.

If the client blindly retries the payment, the user is charged twice.

Stage 4 introduces **guaranteed distributed idempotency**: using an `idempotency_key` supplied by the caller, the system guarantees that no matter how many times a request is retransmitted or raced, **funds are deducted exactly once and every retry receives the exact same receipt**.

```
                        CLIENT RETRY SCENARIO (NETWORK TIMEOUT)

       Client                                                  Ledger Service
         |                                                           |
         | --- POST /api/v1/transfers (key="req-abc-123") ---------> |
         |                                                           | [Deducts $50]
         |                                                           | [Creates Transfer #88]
         |                                                           | [Commits to DB]
         | <--- [NETWORK TIMEOUT / DROPPED PACKET] ----------------- X
         |
    (Client retries)
         |
         | --- POST /api/v1/transfers (key="req-abc-123") ---------> |
         |                                                           | [Checks idempotency_key]
         |                                                           | [Finds existing Transfer #88]
         |                                                           | [NO DEDUCTION]
         | <--- 200 OK (Transfer #88 receipt) ---------------------- |
```

---

## Technical Architecture

### 1. Dual-Tier Deduplication Strategy

| Mechanism | Component | Purpose |
| :--- | :--- | :--- |
| **Tier 1: Read Fast-Path** | `transferRepository.findByIdempotencyKey()` | Quickly returns cached transfer receipt if previous call already committed. |
| **Tier 2: Storage Invariant** | `UNIQUE (idempotency_key)` on `transfers` | Eliminates race conditions if two parallel requests hit within microseconds. |

### 2. Transaction Boundary Isolation (`TransactionTemplate`)
A common bug in Spring Boot applications is catching database constraint exceptions inside a `@Transactional` method. When PostgreSQL throws a unique constraint violation, the Hibernate session and JDBC transaction are marked as **rollback-only**. Catching the exception inside the same transaction will fail on commit with `UnexpectedRollbackException`.

To solve this, Stage 4 uses Spring's `TransactionTemplate`:
```java
// TransferService.java
try {
    return transactionTemplate.execute(status -> 
        executeTransferInTransaction(request)
    );
} catch (DataIntegrityViolationException ex) {
    // Inner transaction cleanly rolled back!
    // Outer method reads the winning transaction that acquired the unique key:
    return transferRepository.findByIdempotencyKey(request.idempotencyKey())
            .map(TransferResponse::fromEntity)
            .orElseThrow(() -> ex);
}
```

```
           SUB-MILLISECOND RACE ON SAME IDEMPOTENCY KEY

   Thread A (POST key="k-1")                      Thread B (POST key="k-1")
             |                                              |
             v                                              v
   [findByIdempotencyKey]                         [findByIdempotencyKey]
   Returns Optional.empty                         Returns Optional.empty
             |                                              |
             v                                              v
   [Inner Transaction: Begins]                    [Inner Transaction: Begins]
   Locks accounts (min, max)                      Waits on account lock...
   Deducts funds, writes entries                  ...
   INSERT INTO transfers (key="k-1")              ...
   INNER TRANSACTION COMMITS                      ...
   Returns Transfer receipt                       Acquires account lock
                                                  INSERT INTO transfers (key="k-1")
                                                  PostgreSQL: UNIQUE VIOLATION!
                                                  INNER TRANSACTION ROLLS BACK
                                                            |
                                                            v
                                                  Catches DataIntegrityViolation
                                                  Queries findByIdempotencyKey("k-1")
                                                  Returns Thread A's Transfer receipt!
```

---

## Schema Update

```sql
ALTER TABLE transfers ADD COLUMN idempotency_key VARCHAR(255);
CREATE UNIQUE INDEX uk_transfers_idempotency_key ON transfers(idempotency_key);
```

---

## Verification & Stress Testing

### Running Idempotency Tests
```bash
./mvnw test -Dtest=IdempotencyIntegrationTest
```

### Verified Scenarios
* **Sequential Replay**: Re-sending identical request returns existing transfer without modifying balances.
* **10-Thread Parallel Race**:
  * 10 concurrent threads submit the exact same transfer request with the exact same idempotency key simultaneously using `CountDownLatch`.
  * **Result**:
    * Exactly 1 database transfer record created.
    * Exactly 2 ledger entries created.
    * Source balance deducted exactly once.
    * All 10 threads receive the exact same transfer ID.

---

## Interview Playbook: Stage 4 Deep Dive

### Q1: Why rely on PostgreSQL `UNIQUE` constraints instead of Redis distributed locks (`Redlock`)?
> **Answer**: Redis distributed locks introduce extra network hops, cache invalidation complexities, and edge cases during Redis failover. Even with Redlock, if a JVM experiences a Stop-The-World GC pause or network partition, the lock lease can expire while the transfer is still executing, allowing a second thread to acquire the lock and double-spend. The database unique constraint is the ultimate single source of truth—it is transactional, ACID-compliant, and impossible to bypass.

### Q2: What happens if two identical requests arrive within 1 millisecond?
> **Answer**: Both threads pass the initial `findByIdempotencyKey()` read check because neither has committed yet. Both enter the transactional block. One thread commits the transfer with the unique key. The second thread fails at `INSERT INTO transfers` with a PostgreSQL unique constraint violation (`DataIntegrityViolationException`). Our service catches this, rolls back the losing transaction cleanly using `TransactionTemplate`, and queries the database to return the winning thread's committed transfer receipt.

### Q3: What should happen if a client reuses an idempotency key with different payload parameters (e.g., different amount)?
> **Answer**: Reusing an idempotency key with conflicting parameters indicates a client bug or replay tampering. The service validates whether the payload parameters (source, destination, amount) match the original transfer. If they differ, the service rejects the request with HTTP `409 Conflict` or `422 Unprocessable Entity` ("Idempotency key already used for a different transfer payload").

---

## Next Stage

Proceed to the [`main` branch](https://github.com/LaxExe/ledger-service) or [`feature/tests-and-ci`](https://github.com/LaxExe/ledger-service/tree/feature/tests-and-ci) to see the full GitHub Actions CI pipeline, integration test suite, and live documentation.
