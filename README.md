# Banking Transaction Processor

A RESTful service for managing bank accounts and money movement — deposits, withdrawals, and transfers — with a full transaction ledger, overdraft protection, and balance/history query APIs.

Built with **Java 25** and **Spring Boot 3.5.5**, following a clean layered architecture (controller → service → repository → domain) with TDD coverage across unit and integration tests.

## Features

- Create accounts with an optional initial balance
- Deposit and withdraw funds with overdraft protection (no negative balances)
- Transfer funds between accounts atomically (`@Transactional`), with rollback on failure
- Automatic, immutable transaction ledger — every money movement is recorded with a timestamp
- Linked transfer records: the debit and credit legs of a transfer share a common reference ID
- Query current balance and full transaction history per account
- Centralized validation and error handling with descriptive 400/404 responses

## Tech Stack

| Component         | Choice                          |
|--------------------|----------------------------------|
| Language            | Java 25                          |
| Framework           | Spring Boot 3.5.5                |
| Persistence         | Spring Data JPA + H2 (in-memory) |
| Build tool          | Maven                            |
| Testing             | JUnit 5, Mockito, AssertJ, MockMvc |
| Coverage            | JaCoCo 0.8.14                    |

## Architecture

```
controller/   → REST endpoints. HTTP concerns only — no business logic.
service/      → Business rules, validation, transaction boundaries.
repository/   → Spring Data JPA interfaces (Account, Transaction).
domain/       → JPA entities (Account, Transaction, TransactionType).
dto/          → Request/response shapes — entities never leave the service layer.
exception/    → Custom exceptions + a single centralized @RestControllerAdvice.
```

## Getting Started

### Prerequisites

- JDK 25
- Maven 3.8+

### Run the application

```bash
mvn spring-boot:run
```

The service starts on `http://localhost:8080`. The H2 console is available at `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:bankingdb`, user: `sa`, no password).

### Run the tests

```bash
mvn clean test
```

This runs the full suite: service-layer unit tests, controller tests, edge-case tests, and full-stack integration tests against H2.

> **Note:** If you've previously built this project with an older JDK, delete `target/` before testing on Java 25 (`rm -rf target/ && mvn clean test`) to avoid stale-classfile issues.

### Generate a coverage report

```bash
mvn clean test
```

JaCoCo report is generated at `target/site/jacoco/index.html`.

## API Reference

### Create an account

```http
POST /accounts
Content-Type: application/json

{ "initialBalance": 1000.00 }
```

**201 Created**
```json
{ "accountId": "a1b2c3d4-...", "balance": 1000.00 }
```

### Deposit

```http
POST /accounts/{id}/deposit
Content-Type: application/json

{ "amount": 500.00 }
```

**200 OK** — returns updated `AccountResponse`. **400** if `amount <= 0`. **404** if the account doesn't exist.

### Withdraw

```http
POST /accounts/{id}/withdraw
Content-Type: application/json

{ "amount": 200.00 }
```

**200 OK** — returns updated `AccountResponse`. **400** if `amount <= 0` or insufficient funds. **404** if the account doesn't exist.

### Transfer

```http
POST /accounts/transfer
Content-Type: application/json

{
  "fromAccountId": "a1b2c3d4-...",
  "toAccountId": "e5f6g7h8-...",
  "amount": 100.00
}
```

**200 OK**. **400** if `amount <= 0`, source and destination are the same account, or source has insufficient funds. **404** if either account doesn't exist.

### Get balance

```http
GET /accounts/{id}/balance
```

**200 OK**
```json
{ "accountId": "a1b2c3d4-...", "balance": 800.00 }
```

**404** if the account doesn't exist.

### Get transaction history

```http
GET /accounts/{id}/transactions
```

**200 OK** — array ordered newest first. Returns `[]` (not 404) if the account exists but has no transactions.

```json
[
  { "type": "WITHDRAWAL", "amount": 200.00, "timestamp": "2026-06-19T10:15:30", "reference": null },
  { "type": "DEPOSIT", "amount": 500.00, "timestamp": "2026-06-19T10:10:00", "reference": null }
]
```

**404** if the account doesn't exist.

## Error Response Shape

All 4xx errors return a consistent shape:

```json
{ "error": "Insufficient funds: balance is 500.00 but withdrawal requested 500.01" }
```

## Design Decisions

- **Amounts are always stored as positive `BigDecimal`s.** Sign is implied by `TransactionType` (`isDebit()` / `isCredit()`), not encoded into the stored value — avoids silent `SUM(amount)` errors.
- **Account IDs are application-generated UUIDs**, not database-generated, keeping ID creation in the domain layer rather than delegated to the persistence layer.
- **Transfers persist two linked ledger entries** (`TRANSFER_OUT` + `TRANSFER_IN`) sharing one reference ID, so either leg can be used to reconstruct the full transfer.
- **Validation is enforced at both the controller (`@Valid`) and service layer.** The service never trusts the controller boundary alone, since service methods can be called directly (e.g. in tests or future internal use).
- **H2 in-memory** was chosen over PostgreSQL for zero-setup local development and fast test runs. Swapping to a persistent database only requires changing `application.properties` and the `h2` dependency.

## Trade-offs / Out of Scope

- No authentication or authorization — all endpoints are open.
- Single-node consistency only — no distributed transaction coordination.
- No pagination on the transaction history endpoint.

## Future Enhancements

- Event sourcing for the transaction ledger
- Kafka integration for downstream consumers
- Pessimistic/optimistic account locking for high-concurrency transfers
- Pagination on `GET /accounts/{id}/transactions`
- Dedicated audit/reporting service

## Project Conventions

This repo includes Claude Code skills under `.claude/skills/` (`code-generation`, `junit-testing`, `code-review`) that encode the architecture, testing, and review conventions used throughout this project. If you're extending this codebase with Claude Code, these load automatically and keep new changes consistent with existing patterns.

## License

Internal / take-home assignment — no license specified.
