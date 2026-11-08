# Stage 2: Double-Entry Ledger & Transactional Transfers

> **Milestone 2 of 6** in the [Ledger Service](https://github.com/LaxExe/ledger-service) reference architecture.  
> Live Documentation & Concurrency Simulator: [laxexe.github.io/ledger-service](https://laxexe.github.io/ledger-service/)

---

## Overview

Stage 2 introduces the **double-entry bookkeeping engine**. In production banking systems, money is never simply overwritten in an account row; every single monetary movement must be permanently recorded as an auditable, immutable double-entry ledger record.

```
                         POST /api/v1/transfers
                                   |
                                   v
             +-------------------------------------------+
             |         TransferService.createTransfer    |
             |       (@Transactional Isolation: READ_COMMITTED)   |
             +-------------------------------------------+
                                   |
        +--------------------------+--------------------------+
        |                                                     |
        v                                                     v
+-------------------------------+             +-------------------------------+
| Account #1 (Debit)            |             | Account #2 (Credit)           |
| balance: $1,000 -> $750       |             | balance: $200 -> $450         |
+-------------------------------+             +-------------------------------+
        |                                                     |
        +--------------------------+--------------------------+
                                   |
                                   v
                    +-----------------------------+
                    |          Transfer           |
                    | id: 42, amount: 25000 cents |
                    +-----------------------------+
                                   |
             +---------------------+---------------------+
             |                                           |
             v                                           v
+---------------------------+               +---------------------------+
|    LedgerEntry (Source)   |               |  LedgerEntry (Destination)|
| account_id: 1             |               | account_id: 2             |
| amount_cents: -25000      |               | amount_cents: +25000      |
+---------------------------+               +---------------------------+
             \                                           /
              \_________________   _____________________/
                                \ /
                      SUM OF ENTRIES == 0
```

---

## The Core Principles

### 1. The Zero-Sum Invariant
Every financial transfer must generate exactly two offsetting `LedgerEntry` records:
$$\sum_{i=1}^{n} \text{entry}_i = 0$$
* The **source account** receives a negative entry (`-amount_cents`).
* The **destination account** receives a positive entry (`+amount_cents`).
* At no point in time can money be created from nothing or lost in transit.

### 2. Append-Only Immutability
Ledger entries are strictly append-only. **`UPDATE` and `DELETE` operations are never executed on the `ledger_entries` table.**
If a mistake is made or a transfer is refunded, a *new* compensating transfer is created with inverse ledger entries. This guarantees an untampered historical audit trail.

### 3. ACID Transaction Boundaries (`@Transactional`)
The entire balance deduction, balance addition, transfer receipt creation, and dual ledger entry generation run inside a single database transaction.
If any step fails (e.g., source has insufficient funds, disk failure, database constraint violation), the entire transaction rolls back cleanly, leaving zero partial state.

---

## Data Model & Schema

```sql
CREATE TABLE transfers (
    id             BIGSERIAL PRIMARY KEY,
    source_id      BIGINT NOT NULL REFERENCES accounts(id),
    destination_id BIGINT NOT NULL REFERENCES accounts(id),
    amount_cents   BIGINT NOT NULL CHECK (amount_cents > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE ledger_entries (
    id           BIGSERIAL PRIMARY KEY,
    transfer_id  BIGINT NOT NULL REFERENCES transfers(id),
    account_id   BIGINT NOT NULL REFERENCES accounts(id),
    amount_cents BIGINT NOT NULL, -- Signed: negative for debit, positive for credit
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transfers_source ON transfers(source_id);
CREATE INDEX idx_transfers_dest ON transfers(destination_id);
CREATE INDEX idx_ledger_account ON ledger_entries(account_id);
CREATE INDEX idx_ledger_transfer ON ledger_entries(transfer_id);
```

---

## API Specification

### 1. Execute Transfer
```http
POST /api/v1/transfers
Content-Type: application/json

{
  "sourceAccountId": 1,
  "destinationAccountId": 2,
  "amountCents": 25000
}
```

**Response (`201 Created`):**
```json
{
  "id": 42,
  "sourceAccountId": 1,
  "destinationAccountId": 2,
  "amountCents": 25000,
  "createdAt": "2026-11-08T01:15:00Z"
}
```

**Insufficient Funds Error (`422 Unprocessable Entity`):**
```json
{
  "status": 422,
  "message": "Account 1 has insufficient funds: available 10000 cents, required 25000 cents",
  "timestamp": "2026-11-08T01:15:02Z"
}
```

**Self-Transfer Error (`400 Bad Request`):**
```json
{
  "status": 400,
  "message": "Source and destination accounts must be different",
  "timestamp": "2026-11-08T01:15:04Z"
}
```

### 2. Get Transfer by ID
```http
GET /api/v1/transfers/42
```

### 3. Get Account Transfer History
```http
GET /api/v1/transfers/account/1
```

---

## Verification & Testing

### Running Tests
```bash
./mvnw test -Dtest=TransferServiceTest,TransferControllerTest
```

### Test Coverage
* `TransferServiceTest.java`:
  * Verifies successful transfer decrements source, increments destination, and writes 2 ledger entries.
  * Verifies rejecting transfers when `amountCents <= 0`.
  * Verifies rejecting transfers when source and destination account IDs are identical.
  * Verifies throwing `InsufficientFundsException` when balance is lower than amount.
  * Verifies zero-sum invariant across generated ledger entries.
* `TransferControllerTest.java`:
  * Validates HTTP layer request serialization, status codes, and error mapping.

---

## Interview Playbook: Stage 2 Deep Dive

### Q1: Why not just update the account balance directly with `UPDATE accounts SET balance = balance - 100`?
> **Answer**: Updating balances in-place without ledger entries destroys historical auditability. If an account has a balance of \$500, you have no way to reconstruct *how* it reached that number, whether funds were lost in transit, or if an internal actor modified the database. In double-entry bookkeeping, the account balance is technically a derived cache of the sum of all historical ledger entries.

### Q2: What happens if the service crashes halfway through writing the two ledger entries?
> **Answer**: Because all operations run within a single database transaction annotated with `@Transactional`, relational databases enforce **Atomicity** via write-ahead logging (WAL). If the JVM crashes, the connection drops, or a constraint is violated, the database engine rolls back all modifications. Neither account is charged, and no partial ledger entries are committed.

### Q3: How do you handle refunds or corrections in an immutable ledger?
> **Answer**: You never issue an `UPDATE` or `DELETE` on existing ledger entries. Instead, you create a new compensating transfer in the reverse direction. For example, if Transfer 42 sent \$50 from A to B, Refund 43 sends \$50 from B to A referencing Transfer 42. Both transactions remain in the immutable audit log forever.

---

## Next Stage

In Stage 2, transfers work sequentially. But what happens if two concurrent requests attempt to spend the same account balance simultaneously?

Proceed to [`stage-3-locking`](https://github.com/LaxExe/ledger-service/tree/stage-3-locking) to see how pessimistic row locking (`SELECT ... FOR UPDATE`) and ordered lock acquisition eliminate lost updates and deadlocks.
