---
name: junit-testing
description: Use this skill whenever writing, extending, or fixing unit tests or integration tests in the Banking Transaction Processor — for example "add tests for X", "write JUnit tests", "the tests are failing", "increase coverage", or after implementing any new service/controller method via the code-generation skill. Also use when diagnosing local test failures (UnnecessaryStubbingException, MockitoException, ApplicationContext load failures) since this project has hit specific, documented causes for each of these before.
---

# JUnit Testing — Banking Transaction Processor

This skill describes how tests are structured in this project and documents real failure modes already encountered, so they aren't re-introduced.

## Test stack

- JUnit 5, Mockito (via `spring-boot-starter-test`), AssertJ for all assertions (`assertThat`, never raw JUnit `assertEquals`)
- `@ExtendWith(MockitoExtension.class)` for pure unit tests — no Spring context
- `@SpringBootTest @AutoConfigureMockMvc` for integration tests — full Spring context + H2
- Arrange-Act-Assert structure in every test, with comments marking each section

## Test file layout

```
src/test/java/com/banking/
├── service/
│   ├── AccountServiceTest.java   — one test per public service method's happy + sad paths
│   └── EdgeCaseTests.java        — cross-cutting edge cases, grouped with @Nested by operation
├── controller/
│   └── AccountControllerTest.java — HTTP-layer behavior, mocked service
└── AccountIntegrationTest.java    — full-stack flows through real MockMvc + H2, grouped with @Nested
```

Don't create a fifth test file for a new feature unless it's a genuinely new layer — extend the existing four.

## Mockito unit tests (`AccountServiceTest`, `EdgeCaseTests`)

### Required `@BeforeEach` pattern

```java
@Mock  private AccountRepository     accountRepository;
@Mock  private TransactionRepository transactionRepository;
@InjectMocks private AccountService  accountService;

private Account sourceAccount;
private String  sourceId;

@BeforeEach
void setUp() {
    sourceAccount = new Account(new BigDecimal("1000.00"));
    sourceId      = sourceAccount.getId().toString();

    // lenient() is REQUIRED here — see "Known failure mode" below.
    lenient().when(accountRepository.findById(sourceAccount.getId()))
            .thenReturn(Optional.of(sourceAccount));
    lenient().when(accountRepository.save(any(Account.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(accountRepository.saveAll(anyList()))
            .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(transactionRepository.save(any(Transaction.class)))
            .thenAnswer(inv -> inv.getArgument(0));
}
```

**KNOWN FAILURE MODE — always use `lenient()` on shared `@BeforeEach` stubs.**
Mockito's strict stubbing fails any test that doesn't use every stub registered before it runs. Since most edge-case tests intentionally short-circuit before reaching the repository (invalid amount, same-account, not-found), a non-lenient shared stub block will throw `UnnecessaryStubbingException` on those tests even though nothing is wrong. This already happened once in this project — every shared `@BeforeEach` stub must be `lenient()`. Test-specific stub overrides (inside an individual `@Test` method) do **not** need `lenient()` since they're consumed by that one test.

### Assertion patterns to follow

- Use `ArgumentCaptor` to verify *what* was passed to `save()`/`saveAll()`, not just that it was called:
  ```java
  ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
  verify(transactionRepository).save(txCaptor.capture());
  assertThat(txCaptor.getValue().getType()).isEqualTo(TransactionType.DEPOSIT);
  ```
- For money comparisons, always use `isEqualByComparingTo(...)`, never `isEqualTo(...)` — `BigDecimal.equals()` is scale-sensitive (`isEqualTo` fails on `100.0` vs `100.00` even though they're mathematically equal).
- For failure paths, always assert: (1) the right exception type and message, (2) that no repository write happened (`verify(accountRepository, never()).save(any())`), (3) when relevant, that the in-memory object's state is unchanged (e.g. balance untouched after a failed withdrawal).
- For guard-ordering tests (e.g. same-account transfer should fail before any DB call), assert `verify(accountRepository, never()).findById(any(UUID.class))` — this is stricter than checking `never().save()` and actually proves the guard fires first.

### Bean Validation tests (null/blank field checks)

Don't fake these with a service-level `IllegalArgumentException` check — actually exercise the Jakarta `Validator`:

```java
private Validator validator;

@BeforeEach
void setUp() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
        validator = factory.getValidator();
    }
}

@Test
void null_amount_fails_bean_validation() {
    DepositRequest req = new DepositRequest(null);
    Set<ConstraintViolation<DepositRequest>> violations = validator.validate(req);
    assertThat(violations).isNotEmpty();
    assertThat(violations).extracting(cv -> cv.getPropertyPath().toString()).contains("amount");
}
```
(The `ValidatorFactory` can be safely closed inside the try-with-resources before `validator` is used later — Hibernate Validator's `Validator` instances remain usable after factory close.)

## Controller tests (`AccountControllerTest`)

**Do NOT use `@WebMvcTest(AccountController.class)`** unless you also add `@MockBean` for every collaborator the controller needs. This project's controller takes a real `AccountService` — without `@MockBean AccountService`, `@WebMvcTest` fails `ApplicationContext` load with `UnsatisfiedDependencyException`, and **every test in the class fails**, even empty ones, because the context never starts.

Use **standalone MockMvc setup** instead — faster, no Spring context, and immune to this failure mode entirely:

```java
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {
    @Mock private AccountService accountService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AccountController controller = new AccountController(accountService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(new LocalValidatorFactoryBean())  // standalone setup does NOT auto-wire @Valid — must add explicitly
                .build();
    }
}
```

If a new controller test needs `@Valid` to actually fire (e.g. testing a 400 on invalid input), confirm `LocalValidatorFactoryBean` is wired — its absence causes `@Valid` to silently no-op.

## Integration tests (`AccountIntegrationTest`)

- `@SpringBootTest @AutoConfigureMockMvc @Transactional` at the class level — `@Transactional` rolls back DB state after every test method automatically, so no manual cleanup is needed.
- Drive everything through MockMvc + real HTTP-shaped JSON bodies, never call the service directly — these tests exist to prove the full stack (controller → service → repository → H2) works together.
- Use small private helper methods (`createAccount(balance)`, `deposit(id, amount)`, `getBalance(id)`) to keep test bodies readable — see existing helpers before writing new ones.
- When asserting transaction history order, compare ISO-8601 timestamp strings lexicographically (`ts1.compareTo(ts2) >= 0`) rather than parsing — same-second timestamps from rapid sequential calls are still validly ordered this way.
- Always include both a "happy path through the full chain" test AND a "404/400 returned correctly via the real `GlobalExceptionHandler`" test for any new endpoint.

## Build/environment failure modes already diagnosed in this project

If `mvn test` fails locally, check these **before** assuming a code bug:

1. **`MockitoException: Could not modify all classes`** → JDK version newer than what the pinned Spring Boot version's Mockito/Byte Buddy supports. Check `pom.xml`'s `spring-boot-starter-parent` version against the local `java -version`. (This project moved from Spring Boot 3.2.5 → 3.5.5 specifically to support Java 25.)
2. **`Unsupported class file major version NN` from JaCoCo** → JaCoCo version too old for the JDK. Needs at least 0.8.14 for Java 25 (class file version 69).
3. **`ApplicationContext` load failure naming `WebMvcTestContextBootstrapper`** → a `@WebMvcTest`-annotated controller test exists without `@MockBean` for its dependencies. Convert to standalone MockMvc setup (see above) or add the missing `@MockBean`.
4. **Stale failures that don't match current file content/line numbers** → almost always a stale `target/` directory or an old extracted copy sitting alongside a freshly unzipped one. Run `rm -rf target/ && mvn clean test` from a freshly extracted project directory before assuming a real regression.

## Coverage targets

Service layer: 90%+. Domain logic: 95%+. New service methods should always ship with both a happy-path test and at least one failure-path test in the same PR/change — don't add a method to `AccountService` without a matching test in `AccountServiceTest` or `EdgeCaseTests`.
