---
name: code-review
description: Use this skill before considering any code change in the Banking Transaction Processor complete — after implementing a feature (code-generation skill) and writing its tests (junit-testing skill), and whenever the user asks to "review this", "check this PR", "is this ready", or similar. Also use proactively after any multi-file change to self-check for the architectural and safety issues this project has specifically run into before (entity exposure, sign-convention bugs, missing ledger entries, stale dependency versions).
---

# Code Review — Banking Transaction Processor

Run this checklist before declaring a change complete. It's organized by what has actually gone wrong in this project before, not generic advice.

## 1. Architecture boundaries

- [ ] No business logic (`if` statements beyond null-checks, validation, calculations) inside any `@RestController` class. If found, move to the service.
- [ ] No JPA entity (`Account`, `Transaction`) returned directly from a controller method or exposed in a DTO. Every response DTO uses a `static from(Entity)` factory, not field copying inline in the controller.
- [ ] Repository interfaces contain only method signatures — no logic, no `default` methods with business rules.
- [ ] Every new public service method that mutates state has `@Transactional`; every read-only one has `@Transactional(readOnly = true)`.

## 2. Money & ledger correctness

- [ ] Every operation that changes a balance persists a matching `Transaction` record via the existing static-factory methods (`Transaction.deposit/withdrawal/transferOut/transferIn`) — never a bare `new Transaction(...)`.
- [ ] Transfer-style operations persist **two** linked records sharing one `reference` UUID. Verify both legs reference the same string, not two independently generated UUIDs.
- [ ] All stored amounts are positive `BigDecimal`; sign is never encoded into the stored value. If a new operation type needs sign semantics, extend `TransactionType.isDebit()/isCredit()` rather than storing a negative amount.
- [ ] Money comparisons everywhere use `BigDecimal.compareTo()`, never `equals()` or `==`. (Scale mismatches like `100.0` vs `100.00` silently break `equals`-based logic.)
- [ ] Overdraft / balance-sufficiency checks use the correct boundary: `balance.compareTo(amount) < 0` should reject only when balance is strictly less than the requested amount — confirm `balance == amount` is allowed where the requirement calls for it (e.g. full withdrawal, full transfer).

## 3. Validation & error handling

- [ ] Every new request DTO field that has a business constraint (amount > 0, ID not blank, etc.) carries the matching Bean Validation annotation with an explicit `message`.
- [ ] Every service method re-validates what `@Valid` already checks (e.g. `amount <= 0` guard) — the service must not trust the controller layer blindly, since the service can be called directly (tests, future internal callers).
- [ ] New exception types are registered in `GlobalExceptionHandler` with the correct HTTP status (404 for not-found, 400 for business-rule violations and validation failures) and return the standard `{"error": "<message>"}` shape.
- [ ] Error messages include the actual values involved (e.g. both the balance and the requested amount), since these are surfaced verbatim in the API response.
- [ ] Guard ordering is fail-fast: checks that need no DB access (amount validity, same-account-by-ID) run before any `findById` call.

## 4. Test coverage (cross-check against junit-testing skill)

- [ ] Every new/changed service method has both a happy-path and a failure-path test.
- [ ] Failure-path tests assert: correct exception type + message, no repository write occurred, and (where relevant) in-memory state is unchanged.
- [ ] New shared stubs added to an existing `@BeforeEach` are wrapped in `lenient()` if not every test in the class will use them — otherwise expect `UnnecessaryStubbingException` across the whole file.
- [ ] If a new controller endpoint was added, confirm `AccountControllerTest` covers it via the standalone MockMvc setup (not `@WebMvcTest` without `@MockBean`).
- [ ] If the endpoint represents a new full user-facing flow, add a corresponding `@Nested` block + test in `AccountIntegrationTest`.

## 5. Dependency & environment sanity

- [ ] If touching `pom.xml`: confirm the Spring Boot parent version, JaCoCo version, and `java.version` property are mutually compatible with the JDK actually in use. (This project specifically needed Spring Boot ≥3.5.5 and JaCoCo ≥0.8.14 for Java 25 — verify against whatever JDK the environment reports before assuming an older pin still works.)
- [ ] No stray scaffold artifacts left behind (malformed directories, leftover `// TODO` comments in files that are otherwise fully implemented, unused imports).

## 6. Final pass

- [ ] Re-read the diff as if reviewing a stranger's PR: does every new file follow the conventions in `code-generation`'s SKILL.md (entity factory patterns, DTO immutability, layered separation)?
- [ ] Run (or ask the user to run) `mvn clean test` from a clean `target/` directory before declaring the change done — stale compiled classes have caused confusing false failures in this project before.
- [ ] Summarize what changed and why in plain terms, calling out any deliberate deviation from an existing pattern and the reason for it.
