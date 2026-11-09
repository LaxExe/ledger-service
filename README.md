# Ledger Service

Built using Java 21, Spring Boot 3.3, PostgreSQL 16.

A small banking API I built to learn how money moves safely in a database. You can create accounts and transfer money between them.

I wanted to get two things right: what happens when a lot of requests hit the same account at once, and what happens when a client retries a request that already went through.

Demo page: https://laxexe.github.io/ledger-service/

## Run it

You need Docker and Java 21.

```bash
docker compose up -d
./mvnw spring-boot:run
curl -i http://localhost:8080/health
```

Tests: `./mvnw clean test` (the database has to be running).

## Problems and how they were addressed

| Problem | What happens | How it was addressed |
|---|---|---|
| **Lost updates** | Two concurrent requests read the same initial balance, subtract funds, and overwrite each other. One withdrawal vanishes. | **Pessimistic row locking** via `SELECT ... FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`). Other transactions targeting that account queue until it commits. |
| **Deadlock cycles** | Alice sends to Bob while Bob sends to Alice. Each acquires one lock and blocks waiting on the other indefinitely. | **Ordered lock acquisition**. The lower account ID is always locked first regardless of transfer direction, breaking circular waits. |
| **Network retry double-charges** | A client times out and retries an already completed transfer, causing funds to move twice. | **Idempotency keys** (`Idempotency-Key` header) with a unique database index. The fast-path returns cached results; duplicate inserts are caught and return the winner. |
| **Floating-point rounding** | Using float or double causes imprecise fractional cent errors over time. | **Integer cent math** stored as 64-bit `Long` (`balanceCents`, `amountCents`). |
| **Silent balance drift** | Account balances change without an audit trail of which transactions caused it. | **Double-entry ledger records**. Every transfer saves immutable paired entries (`-amount` debit, `+amount` credit) that sum to zero. |

## How the transfer pipeline works

All of it happens in `TransferService.transfer`, inside one transaction:

1. If we've already seen this `Idempotency-Key`, return the old transfer and stop.
2. Lock both accounts with `SELECT ... FOR UPDATE`, lowest id first.
3. Check the sender has enough money.
4. Subtract from one account, add to the other.
5. Save the transfer and two ledger entries, one negative and one positive.

Some notes on why:

- **Two ledger entries.** Every transfer is saved as a debit and a credit, so all entries always add up to zero. Amounts are whole cents (a `Long`), not decimals, to avoid rounding.
- **Row locks.** `findByIdForUpdate` in `AccountRepository` locks the row. A second transfer on that account waits until the first one commits.
- **Lowest id first.** If Alice sends to Bob while Bob sends to Alice, each could grab one lock and wait on the other forever. Locking in the same order every time stops that.
- **Idempotency key.** The `transfers` table has a unique constraint on the key. The check in step 1 isn't enough on its own, since two identical requests can both pass it at the same moment. So the service also catches the constraint error from the database and returns the transfer that won.

## API

| Endpoint | What it does | Success |
|---|---|---|
| `POST /accounts` | Create an account | 201 |
| `GET /accounts/{id}` | Get an account | 200 |
| `POST /transfers` | Move money (needs `Idempotency-Key`) | 201 |
| `GET /health` | Is it up | 200 |

Errors come back as JSON. Unknown account is a 404. Not enough money, missing header, same account on both sides, or an amount of zero or less is a 400.

```bash
curl -X POST http://localhost:8080/accounts \
  -H "Content-Type: application/json" \
  -d '{"owner": "Alice", "initialBalanceCents": 10000}'

curl -X POST http://localhost:8080/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 7f3c1e9a-0001" \
  -d '{"fromAccountId": 1, "toAccountId": 2, "amountCents": 2500}'
```

Run that second command twice. The second time you get the same transfer back and no extra money moves.

## Tests

There are 24 tests. The important ones use a real PostgreSQL:

- `ConcurrentTransferIntegrationTest` fires 50 transfers from 16 threads and checks nothing is lost. A second test does 60 random transfers and checks the total stays the same and no balance goes negative.
- `IdempotencyIntegrationTest` sends 10 identical requests at once and checks only one transfer is created.
- The rest cover the service, the controllers and validation.

GitHub Actions runs `./mvnw clean verify` on every push, with a Postgres container.

## Project layout

```
src/main/java/com/example/ledger/
  controller/   endpoints
  service/      TransferService, AccountService
  repository/   database access, including the locking query
  domain/       Account, Transfer, LedgerEntry
  dto/          request and response types
  exception/    errors and the global handler
docs/index.html   the demo site
```

The `stage-1` to `stage-4` branches are the steps I built it in: accounts, transfers, locking, then idempotency.

## License

MIT. See [LICENSE](LICENSE).
