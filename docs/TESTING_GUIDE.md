# TalkMe Backend Testing Guide

This guide defines **how we write tests so no scenario is missed**. Every public method
under test is run through the same fixed enumeration checklist below. Coverage is measured
with Jacoco (`./gradlew test jacocoTestReport`, report at
`build/reports/jacoco/test/html/index.html`); any targeted class under the per-class
threshold (line ≥ 85%, branch ≥ 80%) is a gap to close.

---

## 1. Conventions (match the existing suite — do not reinvent)

- **Frameworks:** JUnit 5 (`@Test`, `@Nested`, `@DisplayName`), Mockito
  (`@ExtendWith(MockitoExtension.class)`, `@Mock`, `ArgumentCaptor`, `lenient()`),
  AssertJ (`assertThat`, `assertThatThrownBy`).
- **No `@InjectMocks`.** Build the system-under-test manually in `@BeforeEach`, passing
  mocked collaborators to the constructor. See
  `service/impl/FlirtModeServiceImplTest.java`.
- **Structure:** one `@Nested` class per method or behavior; test methods named
  `shouldReturnX...` / `shouldThrowWhen...`.
- **Error paths assert the domain code**, not just the exception type:
  ```java
  assertThatThrownBy(() -> service.doThing(badInput))
      .isInstanceOfSatisfying(BadRequestException.class,
          ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_830"));
  ```
- **Side effects are asserted**, not assumed: `ArgumentCaptor` on the saved entity,
  `verify(...)` on messaging/broadcast/cache/outbox, and `verify(..., never())` on the
  paths that must NOT fire.
- **Controllers:** standalone MockMvc + the real `GlobalExceptionHandler` as advice +
  `LocalValidatorFactoryBean` + `AuthenticationPrincipalArgumentResolver`; assert via
  JSONPath (`$.success`, `$.messageCode`). See `controller/FlirtModeControllerUnitTest.java`.
  Populate `SecurityContextHolder` in an `authenticate()` helper; clear it in `@AfterEach`.
- **Integration tests** (only for wiring-critical flows): `@SpringBootTest` +
  `@ActiveProfiles("test")` on H2; replace external/network services with `@MockitoBean`.
  See `controller/AuthControllerTest.java`.
- **Determinism:** no real clock, Redis, DB, or network in unit tests. Inject time seams,
  use fixed UUIDs/ids.

Build & run: `cd TalkMe && ./gradlew test`. Batch run:
`./gradlew test --tests "com.neo.chat.service.impl.*"`.

---

## 2. The enumeration checklist (apply to EVERY public method)

### Positive cases (happy paths)

1. **Nominal success** — typical valid input → correct return value / DTO shape.
2. **Every valid branch** — each mode/flag/type combination (group vs 1:1, enabled vs
   disabled, cursor present vs absent, etc.).
3. **Valid boundaries** — min/max length, first/last page, empty-but-allowed collection,
   limit exactly at the cap.
4. **Idempotency / repeat** — where applicable (mark-read twice, re-accept an already-
   accepted request resolves cleanly).
5. **Side effects** — verify the save (captor), emissions, cache evictions, outbox writes.

### Negative cases (one test per distinct outcome)

6. **Null / missing** required argument.
7. **Not found** — entity/UUID absent → `NotFoundException` + exact `TM_###`.
8. **Authorization / ownership** — acting on another user's resource, non-member, blocked
   → `ForbiddenException` + code.
9. **State / precondition conflict** — already exists / already processed / wrong lifecycle
   state → `ConflictException` or `BadRequestException` + code.
10. **Validation failure** — bad length/format/range, invalid enum/media type →
    `BadRequestException` + code (or bean-validation at the controller layer).
11. **Rate / quota exceeded** → `TooManyRequestsException` + code.
12. **Self-action guards** — self-friend, self-block, self-view → correct code.
13. **Feature-locked / consent-not-granted / suspended / guest-restricted** →
    `FeatureLockedException` / `ForbiddenException` + code.
14. **Downstream failure** — repo throws, external client throws/times out, outbox persist
    fails → correct wrapping/propagation (e.g. `IllegalStateException` on outbox failure)
    and **no partial side effects**.
15. **Empty result** — empty list vs null; no NPE on empty.
16. **Concurrency / retry seams** — self-proxy tx methods, optimistic retry: exercise the
    retry branch.

Not every method hits all 16 — but each one must be *considered* and either covered or
consciously N/A. The Jacoco branch number is the backstop that catches a missed branch.

---

## 3. Layer-specific extensions

- **Mappers:** null input → null/empty; every field mapped; null nested fields; collection
  mapping; enum ↔ string; ignored/derived fields correct.
- **Validators (`ConstraintValidator`):** null handling; exact boundary matrix
  (min-1 / min / max / max+1); valid vs invalid format; the full `isValid` true/false
  matrix per rule.
- **Security / JWT:** valid round-trip; expired; tampered signature; wrong token type
  (e.g. delivery-token rejected where an access token is required); missing claims;
  malformed; filter allow vs 401; rate-limit filter over vs under threshold.
- **Schedulers / reapers:** nothing-to-do (no-op); exactly-at-deadline; past-deadline
  batch; **partial-failure isolation** (one bad row does not abort the batch); correct
  processed count returned.
- **Caches:** hit; miss → load → populate; eviction; **fail-open** on backing-store error
  (never throws to the caller).
- **Crypto:** encrypt → decrypt round-trip; wrong key; corrupted ciphertext;
  key-not-provisioned ("DARK") path.
- **GlobalExceptionHandler:** one case per handled exception type → correct HTTP status +
  `success:false` + `messageCode`; unhandled/framework exception → 500 mapping.

---

## 4. Workflow per class

1. **Read the impl first.** Extract the real `TM_###` codes, every branch, and every side
   effect. The enumeration is driven by the actual code, never guessed.
2. Write `<Name>ServiceImplTest` / `<Name>MapperTest` / `<Name>ValidatorTest` mirroring the
   production package under `src/test/java`.
3. Run the batch (`--tests`) to green.
4. Regenerate the Jacoco report; fill any class still under target, then move on.
