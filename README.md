# Ledger Service

A double-entry ledger REST API built with Java 21, Spring Boot 3 and PostgreSQL 16. It moves money between accounts without losing updates under parallel requests and without double-charging on retries.

Site with interactive demos: **https://laxexe.github.io/ledger-service/**

## Run it

You need Docker and Java 21.

```bash
docker compose up -d          # PostgreSQL 16 on :5432
./mvnw spring-boot:run        # API on :8080
curl -i http://localhost:8080/health
```

Run the tests with `./mvnw clean test`. The integration tests need the database running.

## The two problems

**Lost updates.** Two threads read a balance of $50, both subtract $1 and both write $49. One withdrawal disappears. This happens whenever read, check and write are separate steps with no lock.

**Duplicate charges.** A client sends a transfer, the response is lost, and the client retries. Without a guard the server runs it twice.

## How it works

One service method, `TransferService.transfer`, runs the whole transfer in a single transaction:

1. If the `Idempotency-Key` already exists, return the stored transfer.
2. Lock both accounts with `SELECT ... FOR UPDATE`, lower id first.
3. Reject the transfer if the sender's balance is too low.
4. Debit the sender, credit the receiver.
5. Insert the transfer row, then two ledger entries (`-amount` and `+amount`).

Details of each piece:

- **Double entry.** Every transfer has one `transfers` row and two `ledger_entries` rows. Amounts are integer cents, so there is no rounding error.
- **Row locking.** `AccountRepository.findByIdForUpdate` uses `@Lock(PESSIMISTIC_WRITE)`. A second transaction on the same account waits for the first to commit.
- **Lock ordering.** Transfers A→B and B→A running together could each hold one lock and wait forever. Always locking the lower id first removes the cycle.
- **Idempotency.** `transfers.idempotency_key` has a unique constraint. The lookup in step 1 is only a fast path, because two identical requests can both miss it. When the second insert hits the constraint, the service catches `DataIntegrityViolationException` and returns the first request's transfer.

## API

| Endpoint | Description | Success |
|---|---|---|
| `POST /accounts` | Create an account | 201 with `Location` |
| `GET /accounts/{id}` | Read an account | 200 |
| `POST /transfers` | Move money, requires `Idempotency-Key` | 201 |
| `GET /health` | Liveness check | 200 `ok` |

Errors are JSON with `timestamp`, `status`, `error` and `message`. Unknown accounts return 404. Insufficient funds, a missing header, a same-account transfer or a non-positive amount return 400.

```bash
curl -X POST http://localhost:8080/accounts \
  -H "Content-Type: application/json" \
  -d '{"owner": "Alice", "initialBalanceCents": 10000}'

curl -X POST http://localhost:8080/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 7f3c1e9a-0001" \
  -d '{"fromAccountId": 1, "toAccountId": 2, "amountCents": 2500}'
```

Sending the second request again with the same key returns the same transfer and moves no money.

## Tests

24 `@Test` methods. The ones that matter most run against a real PostgreSQL:

| Test | What it checks |
|---|---|
| `ConcurrentTransferIntegrationTest` | 50 transfers on 16 threads lose no updates. 60 random transfers keep the total constant and no balance negative. |
| `IdempotencyIntegrationTest` | 10 parallel requests with one key create exactly one transfer. |
| `TransferIntegrationTest`, `TransferServiceTest` | Balances, transfer rows and ledger entries. |
| Controller tests | Validation and error responses. |

CI runs `./mvnw clean verify` on every push and pull request, with a PostgreSQL 16 service container. The site in `docs/` deploys to GitHub Pages from `main`.

## Layout

```
src/main/java/com/example/ledger/
  controller/   HTTP endpoints
  service/      TransferService, AccountService
  repository/   Spring Data JPA, including the locking query
  domain/       Account, Transfer, LedgerEntry
  dto/          request and response records
  exception/    error types and GlobalExceptionHandler
docs/index.html   the GitHub Pages site
```

The `stage-1-accounts` to `stage-4-idempotency` branches show the project as it was built: accounts, transfers, locking, then idempotency.

## License

MIT, see [LICENSE](LICENSE).
