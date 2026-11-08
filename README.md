# Stage 3: Pessimistic Locking & Deadlock Prevention

> **Milestone 3 of 6** in the [Ledger Service](https://github.com/LaxExe/ledger-service) reference architecture.  
> Live Documentation & Concurrency Simulator: [laxexe.github.io/ledger-service](https://laxexe.github.io/ledger-service/)

---

## Overview

In multi-threaded banking systems, concurrent transfers against the same accounts cause two catastrophic failure modes:
1. **Lost Updates**: Two concurrent transactions read the same initial balance, perform calculations in memory, and overwrite each other's writes.
2. **Deadlocks**: Two concurrent bidirectional transfers lock accounts in opposing orders (Account A -> B vs Account B -> A), causing the database engine to abort transactions.

Stage 3 eliminates both vulnerabilities using **PostgreSQL pessimistic row locking** and **deterministic ordered lock acquisition**.

```
                CONCURRENT BIDIRECTIONAL TRANSFERS (DEADLOCK RISK)

       Thread 1: Transfer (Acc 1 -> Acc 2)      Thread 2: Transfer (Acc 2 -> Acc 1)
                      |                                        |
                      |                                        |
      +---------------+---------------+        +---------------+---------------+
      |  Order Locks: min(1, 2) = 1   |        |  Order Locks: min(1, 2) = 1   |
      |  Acquires lock on Account 1   |        |  Tries lock on Account 1      |
      |  Status: GRANTED              |        |  Status: WAITING (BLOCKED)    |
      +---------------+---------------+        +---------------+---------------+
                      |                                        |
                      v                                        |
      +---------------+---------------+                        |
      |  Order Locks: max(1, 2) = 2   |                        |
      |  Acquires lock on Account 2   |                        |
      |  Status: GRANTED              |                        |
      +---------------+---------------+                        |
                      |                                        |
                      v                                        |
      +---------------+---------------+                        |
      |  Execute transfer & commit    |                        |
      |  Locks RELEASED               |                        |
      +---------------+---------------+                        |
                                                               v
                                               +---------------+---------------+
                                               |  Acquires lock on Account 1   |
                                               |  Acquires lock on Account 2   |
                                               |  Executes transfer cleanly    |
                                               +-------------------------------+
```

---

## Concurrency Failure Modes

### 1. The Lost Update Anomaly (Reproduced in Integration Tests)
Without row locking, consider two threads running simultaneously against an account with balance **$100.00**:

```
Time   Thread 1 (Transfer $60)          Thread 2 (Transfer $50)          Database Balance
-----------------------------------------------------------------------------------------
T1     SELECT balance FROM accounts;                                     $100.00
       (reads $100.00)
T2                                      SELECT balance FROM accounts;    $100.00
                                        (reads $100.00)
T3     Check: $100 >= $60 (OK)          Check: $100 >= $50 (OK)
T4     new_bal = $100 - $60 = $40       new_bal = $100 - $50 = $50
T5     UPDATE balance = $40                                              $40.00
T6                                      UPDATE balance = $50             $50.00 (LOST UPDATE!)
```

* **Outcome**: Both transfers succeeded (\$110 withdrawn), but the final balance is \$50. **\$60 vanished, and the account allowed an unrecorded overdraft.**
* **Integration Test Evidence**: In `ConcurrentTransferIntegrationTest`, running 50 concurrent 100-cent transfers from a 5,000-cent account without locking left a final balance of **4,400 cents instead of 0 cents**—demonstrating a massive 4,400-cent lost update!

---

## The Solution

### 1. Pessimistic Row Locking (`SELECT ... FOR UPDATE`)
Spring Data JPA's `@Lock(LockModeType.PESSIMISTIC_WRITE)` instructs PostgreSQL to acquire an exclusive row-level lock:

```java
public interface AccountRepository extends JpaRepository<Account, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
```

Generated PostgreSQL query:
```sql
SELECT a.id, a.balance_cents, a.name 
FROM accounts a 
WHERE a.id = $1 
FOR NO KEY UPDATE;
```
Any competing transaction attempting to read the same account `FOR UPDATE` is suspended at the database engine until the holding transaction issues `COMMIT` or `ROLLBACK`.

### 2. Deadlock Elimination via Ordered Lock Acquisition
Pessimistic locking alone introduces deadlocks when transfers execute in opposite directions:
* **Thread 1**: Locks Account 1, requests lock on Account 2.
* **Thread 2**: Locks Account 2, requests lock on Account 1.
* **Result**: Circular dependency. PostgreSQL throws: `PSQLException: ERROR: deadlock detected`.

To mathematically prevent deadlocks, we eliminate Coffman's **Circular Wait** condition by enforcing a strict global ordering on lock acquisition:

```java
// TransferService.java
Long firstId = Math.min(request.sourceAccountId(), request.destinationAccountId());
Long secondId = Math.max(request.sourceAccountId(), request.destinationAccountId());

// Always lock lower ID before higher ID regardless of transfer direction:
Account first = accountRepository.findByIdForUpdate(firstId)
        .orElseThrow(() -> new AccountNotFoundException(firstId));
Account second = accountRepository.findByIdForUpdate(secondId)
        .orElseThrow(() -> new AccountNotFoundException(secondId));

Account source = request.sourceAccountId().equals(firstId) ? first : second;
Account destination = request.destinationAccountId().equals(firstId) ? first : second;
```

---

## Verification & Stress Testing

### Running Concurrency Tests
```bash
./mvnw test -Dtest=ConcurrentTransferIntegrationTest
```

### Verified Scenarios
1. **50 Concurrent Threads on Single Account**:
   * Starting balance: 5,000 cents.
   * 50 parallel threads concurrently transfer 100 cents each.
   * Verified: Source balance reaches exactly 0 cents; destination balance reaches exactly 5,000 cents; exactly 50 transfers and 100 ledger entries recorded.
2. **Stress Test (20 Consecutive Cycles)**:
   * Repeating high-concurrency transfers across 20 iterations yields 0 deadlocks and 0 lost updates.

---

## Interview Playbook: Stage 3 Deep Dive

### Q1: Why choose Pessimistic Locking over Optimistic Locking (`@Version`)?
> **Answer**: Optimistic locking checks version counters at commit time and throws `OptimisticLockException` if conflicts occur. In financial systems with hot accounts (e.g., payroll accounts, merchant wallets, or high-volume settlement pools), high concurrency produces massive abort and retry rates. If 100 threads hit the same account, optimistic locking causes 99 retries, exhausting thread pools and degrading latency. Pessimistic locking serializes execution efficiently with minimal overhead.

### Q2: How does ordering lock acquisition prevent deadlocks?
> **Answer**: According to Coffman's four conditions for deadlock (Mutual Exclusion, Hold and Wait, No Preemption, Circular Wait), a deadlock can only occur if there is a circular chain of processes waiting on resources held by the next. By imposing a total order on account IDs (`min(id1, id2)` then `max(id1, id2)`), threads acquire locks in the exact same sequence, making a cycle impossible ($T_1 \to T_2 \to \dots \to T_1$).

### Q3: What is the difference between `FOR UPDATE` and `FOR NO KEY UPDATE` in PostgreSQL?
> **Answer**: 
> * `FOR UPDATE` locks the entire tuple and blocks all competing locks, including `FOR SHARE` foreign key checks.
> * `FOR NO KEY UPDATE` (used by Hibernate for `PESSIMISTIC_WRITE`) locks the row against concurrent modifications but allows foreign key checks and `SELECT ... FOR SHARE` queries to proceed concurrently, offering higher throughput without sacrificing consistency.

---

## Next Stage

Now that concurrent transfers are thread-safe and deadlock-free, what happens when network timeouts cause the client to retry a transfer?

Proceed to [`stage-4-idempotency`](https://github.com/LaxExe/ledger-service/tree/stage-4-idempotency) to see how unique database constraints and replay caching ensure safe network retries.
