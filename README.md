# Stage 1: Account Management & Monetary Representation

> **Milestone 1 of 6** in the [Ledger Service](https://github.com/LaxExe/ledger-service) reference architecture.  
> Live Documentation & Concurrency Simulator: [laxexe.github.io/ledger-service](https://laxexe.github.io/ledger-service/)

---

## Overview

Stage 1 establishes the bedrock of the ledger engine: **account provisioning, strict monetary data modeling, and REST boundaries**. 

Every subsequent stage (double-entry transfers, pessimistic row locks, distributed idempotency) builds directly on top of the guarantees introduced here.

```
       +-----------------------------------------------------------+
       |                  HTTP Client / API Gateway                |
       +-----------------------------------------------------------+
                                     |
                                     v
                       +---------------------------+
                       |     AccountController     |
                       |  (Input Validation, DTOs) |
                       +---------------------------+
                                     |
                                     v
                       +---------------------------+
                       |      AccountService       |
                       | (Business Invariants)     |
                       +---------------------------+
                                     |
                                     v
                       +---------------------------+
                       |     AccountRepository     |
                       |     (Spring Data JPA)     |
                       +---------------------------+
                                     |
                                     v
               +-------------------------------------------+
               |             PostgreSQL 16                 |
               |  Table: accounts (balance_cents: BIGINT)  |
               +-------------------------------------------+
```

---

## Architectural Decisions

### 1. Integer Cents (`BIGINT`) vs Floating Point (`FLOAT` / `DOUBLE`)
In financial systems, floating-point types (`float`, `double`) are strictly prohibited due to IEEE 754 binary representation errors:

```java
// IEEE 754 floating-point danger:
double a = 0.10;
double b = 0.20;
System.out.println(a + b); // Prints: 0.30000000000000004
```

In a high-throughput financial system, accumulated fractional inaccuracies corrupt ledgers and trigger audit failures. 

* **Our Standard**: All monetary values are represented as **whole cents using Java `Long` and PostgreSQL `BIGINT`**.
  * `$10.50` is stored as `1050`.
  * Maximum supported balance: `9,223,372,036,854,775,807` cents (\$92 quadrillion).
  * Division is deferred until presentation/reporting layers where rounding rules (e.g., Half-Up, Banker's Rounding) can be explicitly parameterized.

### 2. DTO Isolation from Database Entities
Database entities (`Account.java`) are strictly separated from client-facing DTOs (`CreateAccountRequest`, `AccountResponse`):
* **Security**: Prevents mass-assignment vulnerabilities (e.g., client attempting to supply `id` or arbitrary internal fields).
* **API Versioning**: Internal database schema refactors do not break public REST API contracts.
* **Validation**: Input sanitization via `@NotNull`, `@NotBlank`, `@PositiveOrZero` is handled before entity instantiation.

### 3. Non-Negative Balance Invariant
An account balance may never drop below zero. This invariant is enforced in defense-in-depth:
1. **Application Layer**: `Account.debit(long amount)` verifies `balanceCents - amount >= 0` before mutating state.
2. **Database Layer**: Column is defined with `NOT NULL` and schema checks.

---

## Data Model & Schema

```sql
CREATE TABLE accounts (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    balance_cents BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_balance_non_negative CHECK (balance_cents >= 0)
);

CREATE INDEX idx_accounts_name ON accounts(name);
```

---

## API Specification

### 1. Create Account
```http
POST /api/v1/accounts
Content-Type: application/json

{
  "name": "Alice Treasury",
  "initialBalanceCents": 100000
}
```

**Response (`201 Created`):**
```json
{
  "id": 1,
  "name": "Alice Treasury",
  "balanceCents": 100000,
  "createdAt": "2026-11-08T01:10:00Z"
}
```

### 2. Get Account by ID
```http
GET /api/v1/accounts/1
```

**Response (`200 OK`):**
```json
{
  "id": 1,
  "name": "Alice Treasury",
  "balanceCents": 100000,
  "createdAt": "2026-11-08T01:10:00Z"
}
```

**Error Response (`404 Not Found`):**
```json
{
  "status": 404,
  "message": "Account not found with id: 999",
  "timestamp": "2026-11-08T01:10:05Z"
}
```

---

## Verification & Testing

### Running Tests
```bash
./mvnw test -Dtest=AccountTest,AccountControllerTest
```

### Test Coverage
* `AccountTest.java`: Unit tests verifying that:
  * Creating accounts with negative initial balances throws an exception.
  * Crediting increases balance correctly.
  * Debiting decrements balance correctly.
  * Debiting more than the balance throws `InsufficientFundsException`.
* `AccountControllerTest.java`: MockMvc web layer tests verifying:
  * `POST /api/v1/accounts` returns `201 Created` and serialized JSON.
  * Validation rejections (`400 Bad Request`) for blank names or negative initial balances.
  * `GET /api/v1/accounts/{id}` returns `200 OK` or `404 Not Found`.

---

## Interview Playbook: Stage 1 Deep Dive

### Q1: Why not use `BigDecimal` everywhere instead of integer cents?
> **Answer**: `BigDecimal` is accurate, but has trade-offs in storage, serialization, and index density:
> 1. `BIGINT` is 8 bytes in PostgreSQL and maps directly to CPU registers for integer arithmetic, making queries and sorting significantly faster.
> 2. `NUMERIC` / `BigDecimal` has variable-length binary storage, higher CPU overhead, and potential impedance mismatches across microservice boundaries (JSON float coercion hazards).
> 3. Sub-cent currencies (e.g., crypto satoshis or micro-dollar billing) are also whole integers in their smallest unit (e.g., micro-cents or basis points).

### Q2: Why separate DTOs from JPA Entities?
> **Answer**: Coupling HTTP contracts to database schemas causes three major risks:
> 1. Over-posting security vulnerabilities where malicious clients submit internal fields.
> 2. Hibernate lazy-loading exceptions (`LazyInitializationException`) when jackson tries to serialize uninitialized proxies.
> 3. Inability to evolve database schemas without breaking external API clients.

### Q3: How do you prevent an account from being created with negative balances?
> **Answer**: By applying defense-in-depth:
> 1. Controller validation: `@PositiveOrZero` on `CreateAccountRequest.initialBalanceCents`.
> 2. Domain model guard: `Account` constructor rejects `balanceCents < 0` with `IllegalArgumentException`.
> 3. Database constraint: A table-level `CHECK (balance_cents >= 0)` constraint prevents invalid state even if bypassing the application layer.

---

## Next Stage

Proceed to [`stage-2-transfers`](https://github.com/LaxExe/ledger-service/tree/stage-2-transfers) to see how double-entry ledger mechanics, signed journal entries, and transactional rollback boundaries are implemented.
