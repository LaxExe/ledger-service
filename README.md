# Stage 5: Integration Testing & Continuous Integration (CI)

> **Milestone 5 of 6** in the [Ledger Service](https://github.com/LaxExe/ledger-service) reference architecture.  
> Live Documentation & Concurrency Simulator: [laxexe.github.io/ledger-service](https://laxexe.github.io/ledger-service/)

---

## Overview

A financial ledger service cannot rely merely on unit mocks. Invariants such as row locking (`SELECT ... FOR NO KEY UPDATE`), database deadlocks, unique constraint violations, and transaction rollbacks require verification against real relational databases.

This branch introduces the **end-to-end integration test suite** and the **automated GitHub Actions CI workflow** that exercises every layer against a live PostgreSQL 16 instance.

```
       GIT PUSH / PULL REQUEST
                 |
                 v
+-------------------------------------------------+
|       GitHub Actions Runner (ubuntu-latest)     |
|                                                 |
|  +-------------------------------------------+  |
|  | PostgreSQL 16 Service Container           |  |
|  | Image: postgres:16-alpine                 |  |
|  | Healthcheck: pg_isready                   |  |
|  +-------------------------------------------+  |
|                        ^                        |
|                        | Port 5432              |
|  +-------------------------------------------+  |
|  | Java 21 OpenJDK (Eclipse Temurin)         |  |
|  | ./mvnw -B verify                          |  |
|  +-------------------------------------------+  |
+-------------------------------------------------+
                 |
        +--------+--------+
        |                 |
        v                 v
   Unit Tests      Integration Tests (Spring Boot + Real PG)
   (Fast mock)     - Account creation & checks
                   - Dual ledger entry zero-sum check
                   - 50-thread concurrent transfer test
                   - 10-thread idempotency race test
```

---

## Test Architecture

The test suite is structured into two complementary layers:

### 1. Mock Unit Tests (`src/test/java/.../unit`)
Fast feedback tests running purely in-memory:
* `HealthControllerTest`: WebMvc slice test verifying `GET /health` endpoint.
* `AccountTest`: Domain unit tests verifying `debit()`, `credit()`, and negative balance guards.
* `TransferServiceTest`: Mockito unit tests verifying business logic validation, entity mapping, and error throwing.

### 2. Live Database Integration Tests (`src/test/java/.../integration`)
Full Spring Boot test suite (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) connecting to live PostgreSQL:
* **`TransferIntegrationTest`**:
  * Creates real accounts via HTTP.
  * Executes transfers.
  * Verifies that account balances in PostgreSQL match transfer amounts.
  * Validates that exactly 2 immutable `LedgerEntry` records exist for each transfer, summing to 0.
* **`ConcurrentTransferIntegrationTest`**:
  * Fires 50 parallel threads against a single account using `ExecutorService` and `CountDownLatch`.
  * Verifies row locking prevents lost updates and deterministic ordering prevents deadlocks.
* **`IdempotencyIntegrationTest`**:
  * Fires 10 concurrent threads simultaneously with the same `idempotency_key`.
  * Verifies that PostgreSQL `UNIQUE` constraint and `TransactionTemplate` ensure exactly 1 transfer is written, with 0 double-charges.

---

## GitHub Actions CI Workflow

The workflow is defined in [`.github/workflows/ci.yml`](.github/workflows/ci.yml):

```yaml
name: CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build-and-test:
    runs-on: ubuntu-latest

    services:
      postgres:
        image: postgres:16-alpine
        env:
          POSTGRES_DB: ledger_db
          POSTGRES_USER: postgres
          POSTGRES_PASSWORD: password
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

    steps:
      - name: Check out repository
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'

      - name: Make Maven wrapper executable
        run: chmod +x ./mvnw

      - name: Run unit & integration tests
        env:
          SPRING_DATASOURCE_URL: jdbc:postgresql://localhost:5432/ledger_db
          SPRING_DATASOURCE_USERNAME: postgres
          SPRING_DATASOURCE_PASSWORD: password
        run: ./mvnw -B verify
```

---

## Verification & Execution

### Running the Full Test Suite Locally
Ensure PostgreSQL is running:
```bash
docker compose up -d
```

Run all tests:
```bash
./mvnw clean verify
```

**Expected Result:**
```
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## Next Stage

Proceed to the [`main` branch](https://github.com/LaxExe/ledger-service) to explore the full production documentation site, interactive browser simulator, and interview preparation guides.
