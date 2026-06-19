---
name: code-generation
description: Use this skill whenever adding a new feature, endpoint, domain entity, or service method to the Banking Transaction Processor — for example "add a new endpoint", "implement X feature", "add support for Y operation", or any request to extend the codebase with new functionality. Also use when modifying existing service/controller/domain logic to match a new requirement. This skill encodes the project's layered architecture, naming conventions, and patterns established across Account/Transaction features so new code is consistent with existing code, not just functionally correct.
---

# Code Generation — Banking Transaction Processor

This skill describes how this codebase is built so new features fit in seamlessly. Read this before writing any new endpoint, service method, entity, or DTO.

## Stack & versions (do not downgrade)

- Java 25, Spring Boot **3.5.5** (3.x line — do not jump to Spring Boot 4.0 without an explicit migration request; it's a bigger framework change)
- Spring Data JPA + H2 (in-memory, `create-drop` DDL)
- Jakarta EE packages (`jakarta.persistence.*`, `jakarta.validation.*`) — never `javax.*`
- JaCoCo `0.8.14` minimum for Java 25 class file support (anything older throws `Unsupported class file major version 69`)

## Layered architecture — strict separation

```
controller/   → HTTP only. @Valid on @RequestBody, delegates to service, maps to ResponseEntity. ZERO business logic.
service/      → All business rules, validation guards, transaction boundaries (@Transactional).
repository/   → JpaRepository interfaces only. Derived query methods, no @Query unless necessary.
domain/       → JPA entities. Private/protected constructors + static factories where there's more than one creation path.
dto/          → Request/response shapes. NEVER expose JPA entities directly from controllers.
exception/    → Custom RuntimeExceptions + one central GlobalExceptionHandler (@RestControllerAdvice).
```

Controllers must never contain `if`/validation logic beyond what `@Valid` handles declaratively. If you're about to write an `if` in a controller, it belongs in the service.

## Entity conventions (see `Account.java`, `Transaction.java`)

- IDs are `UUID`, generated in the constructor (`UUID.randomUUID()`), not via `@GeneratedValue`. Keeps ID generation in the domain layer.
- `protected NoArgsConstructor()` to satisfy JPA, never public.
- Money fields are `BigDecimal` with `precision = 19, scale = 2`.
- When an entity has multiple creation contexts (e.g. `Transaction` created for deposit vs. withdrawal vs. transfer), use **named static factories** instead of a generic constructor with a type flag:
  ```java
  Transaction.deposit(accountId, amount)
  Transaction.withdrawal(accountId, amount)
  Transaction.transferOut(accountId, amount, reference)
  Transaction.transferIn(accountId, amount, reference)
  ```
  This makes the service layer read like a sentence and makes wrong-type bugs impossible at the call site.
- **Money sign convention**: amounts are always stored positive. Sign is implied by the type/enum, never by a negative `BigDecimal`. If a type needs a debit/credit notion, add `isDebit()`/`isCredit()` helpers on the enum (see `TransactionType`) rather than encoding sign into the amount.

## Service layer conventions (see `AccountService.java`)

- Constructor injection only, no `@Autowired` field injection.
- Every public method that mutates state is `@Transactional`. Every read-only query method is `@Transactional(readOnly = true)`.
- **Validate amounts in the service even though `@Valid` already validates at the controller boundary.** The service must be self-defending for direct callers (tests, future internal use):
  ```java
  if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("X amount must be greater than zero, got: " + amount);
  }
  ```
- Account lookup is centralized in one package-private helper, reused by every operation:
  ```java
  Account findAccountById(String accountId) {
      UUID uuid;
      try { uuid = UUID.fromString(accountId); }
      catch (IllegalArgumentException e) { throw new AccountNotFoundException(accountId); }
      return accountRepository.findById(uuid).orElseThrow(() -> new AccountNotFoundException(accountId));
  }
  ```
  A malformed (non-UUID) ID and an unknown UUID both resolve to the same `AccountNotFoundException` → 404. Don't special-case malformed IDs differently.
- Guard ordering matters and should fail fast before any DB call where possible. For transfer-style operations: amount validity → cross-entity rule (e.g. same-account) → load entities → balance/business rule. Same-account-style guards using only request data (no DB lookup needed) go **before** any `findById` call.
- Error messages should be descriptive and include the actual values involved (e.g. `"Insufficient funds: balance is 500.00 but withdrawal requested 500.01"`), since these messages are surfaced directly in the 400 response body.

## DTO conventions

- Immutable where reasonable: `private final` fields, private constructor, `static from(Entity)` factory method (see `AccountResponse.from()`, `BalanceResponse.from()`, `TransactionHistoryResponse.from()` / `.fromList()`).
- Request DTOs use Bean Validation annotations with explicit messages: `@NotNull`, `@Positive`, `@NotBlank` — never bare annotations without a `message`.
- Response DTOs never carry JPA annotations (`@Entity`, `@Column`, `@Id`) — if you find yourself adding one, you're exposing the entity, not building a DTO.

## Controller conventions (see `AccountController.java`)

- `@RestController @RequestMapping("/accounts")` at the class level; HTTP-verb annotations + sub-paths per method.
- Literal-segment routes (e.g. `/accounts/transfer`) must be declared **before** any `/{id}/...` route in the same controller, or Spring MVC will try to bind the literal segment as a path variable.
- Return `ResponseEntity<T>` explicitly with the correct status: `201 Created` for resource creation, `200 OK` for everything else, never rely on default status inference.
- No try/catch in controllers — exceptions propagate to `GlobalExceptionHandler`.

## Exception handling (see `GlobalExceptionHandler.java`)

- One central `@RestControllerAdvice`. New exception types get a new `@ExceptionHandler` method here, not ad-hoc try/catch elsewhere.
- Standard mapping: `AccountNotFoundException` → 404, all business-rule violations (`InsufficientFundsException`, `SameAccountTransferException`, `IllegalArgumentException`) → 400, `MethodArgumentNotValidException`/`ConstraintViolationException` (Bean Validation failures) → 400.
- Response body shape is always `{"error": "<message>"}` — keep this consistent for any new exception type.

## Ledger / transaction-record rules

Any operation that moves money must persist a `Transaction` ledger entry (or two, for transfers):

- DEPOSIT → 1 record, `accountId` = the account credited.
- WITHDRAWAL → 1 record, `accountId` = the account debited.
- TRANSFER → 2 records: `TRANSFER_OUT` on source, `TRANSFER_IN` on destination, **sharing one `UUID.randomUUID().toString()` reference** so both legs can be correlated later.
- `timestamp` is always set inside the entity's constructor (`LocalDateTime.now()`), never passed in from the caller.

## When implementing a new requirement

1. Identify which layer(s) it touches — most features touch all five (domain → repository → service → controller → DTO).
2. Check `GlobalExceptionHandler` — does the new business rule need a new exception type and handler?
3. Does it move money? If so, it needs a `Transaction` ledger entry using the existing static-factory pattern — add a new factory method on `Transaction` rather than a generic constructor.
4. Match the existing validation-in-service-even-though-controller-validates pattern.
5. After implementation, hand off to the `junit-testing` skill to write/extend tests, then `code-review` to self-check before considering the change done.
