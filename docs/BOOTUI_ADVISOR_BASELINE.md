# BootUI Advisor Baseline (2026-09-08)

BootUI (`/bootui`, dev-only) runs eleven advisor scans over the running application. The team goal is
**zero open findings** on every scan so that any new finding is a real regression. This document records
which rules were **fixed** in code and which are **dismissed** in BootUI (persisted in `.bootui/boot-ui.yml`,
committed so every developer sees the same baseline) together with the reason for each dismissal.

A dismissal is only acceptable when the finding is (a) a false positive for this codebase, (b) a documented
design decision, or (c) a change whose risk to running functionality outweighs the benefit right now.
Re-evaluate the "deferred" rows when the surrounding constraints change.

## Dismissed rules

| Rule | Scanner | Reason | Kind |
|---|---|---|---|
| ARCH-PKG-001 | Architecture | 79 package cycles (`cache ↔ dto ↔ config …`). Resolving them is a package restructuring across the whole module; tracked as a separate refactor. | deferred |
| ARCH-SPRING-004 | Architecture | 21 self-invocations, each reviewed individually: every target is `@Transactional` with default `REQUIRED` propagation called from an already-transactional method (so the callee joins the same transaction either way), and the three `REQUIRES_NEW` / optimistic-retry cases already route through an `ObjectProvider` self-proxy. The codebase has no `@Cacheable`/`@Retryable`, and no `@Async` is self-invoked. `NotificationServiceImpl#notifyFriends` deliberately calls in-process so a per-recipient failure cannot mark the outer transaction rollback-only. | false positive |
| ARCH-CODE-008 | Architecture | 10 `java.util.Date` uses, all at third-party API boundaries that accept nothing else: jjwt 0.12 (`setExpiration`/`setIssuedAt`), the LiveKit token builder, and the OCI Object Storage SDK. Each computes with `java.time` and converts only at the call. | false positive |
| HIB-MAP-019 | Hibernate | All 30 flagged foreign keys are already the LEADING column of a `@UniqueConstraint` or a `unique = true` join column (7 owning `@OneToOne` join columns, 23 composite unique constraints), and PostgreSQL backs every unique constraint with a btree index. The rule only inspects `@Index` declarations, so silencing it would mean declaring 30 indexes that duplicate existing ones — the opposite of the DB-SCHEMA-003 cleanup done earlier. The Database Advisor introspects the live schema and reports **0** violations of its own missing-FK-index rule (DB-SCHEMA-002), which is the authoritative check. | false positive |
| HIB-FETCH-004 | Hibernate | Informational reminder about `Post` (4 bags) and `Message` (3 bags). The rule's own guidance is that multiple bags are safe unless two are join-fetched in one query; HIB-QUERY-007 covers that case and reports nothing. | design |
| PT-A05-055, PT-A05-040, PT-A05-041 | Pentesting | `/actuator/metrics`, `/actuator/prometheus` and `/v3/api-docs` are reachable mappings, but the dedicated actuator filter chain now requires `SUPER_ADMIN` for everything except health, and Swagger/OpenAPI is disabled in the `prod` profile. The scanner inspects handler mappings, not per-endpoint authorization. | false positive |
| PT-A05-060 | Pentesting | The CSP keeps `'unsafe-inline'` because the Next.js static export inlines its bootstrap script (no nonce can be injected into a pre-rendered export), and `'unsafe-eval'` because the browser-side TensorFlow.js/nsfwjs moderation model compiles kernels with `eval`. Removing either breaks the app. | design |
| PT-A05-044 | Pentesting | `server.forward-headers-strategy=framework` is required behind Nginx Proxy Manager, which overwrites `X-Forwarded-*`; `util.ClientIp` additionally trusts only the last hop. | design |
| PT-A05-001, PT-A05-068 | Pentesting | The synthetic probe truncates at 8 KiB because the SPA's `index.html` is larger, making the check indeterminate rather than failing. Cross-Origin-Embedder-Policy is deliberately unset — `require-corp` would break the Turnstile widget and remote media. | false positive |
| HIB-ID-001, HIB-ID-006 | Hibernate | `BaseEntity#id` uses `GenerationType.IDENTITY` on 73 tables with live data. Moving to sequences requires per-table sequences seeded above the current max id and a coordinated migration; `ddl-auto=update` would create sequences starting at 1 and collide with existing rows. | deferred (data migration) |
| HIB-FETCH-001, HIB-MAP-018 | Hibernate | `Post#poll` and six other to-one associations are intentionally EAGER; the non-owning `@OneToOne` cannot be LAZY without bytecode enhancement, and open-in-view is now off, so flipping fetch modes risks `LazyInitializationException` in callers. | design |
| HIB-MAP-006 | Hibernate | Shared-primary-key `@OneToOne` mapping is a schema redesign of seven tables. | deferred |
| HIB-QUERY-006 | Hibernate | 21 paged/streamed reads return entities and are mapped to DTOs inside the transaction (`default_batch_fetch_size=50` bounds N+1). DTO projections are an optimisation, not a defect. | design |
| HIB-ENTITY-008 | Hibernate | Adding `@Version` to `ReputationEvent`/`OutboxEvent` would add a nullable column to existing rows; Hibernate treats a null version as transient and would try to INSERT on save. | deferred (data migration) |
| HIB-CONFIG-002 | Hibernate | `ddl-auto=update` is the project's chosen schema strategy for every environment (no Flyway/Liquibase yet). | design |
| RAPI-MAP-007 | REST API | `DELETE /users/me` carries a JSON body (password + optional feedback). The web client depends on this contract. | design |
| RAPI-MAP-008 | REST API | `PUT /…/mine` endpoints address the resource of the authenticated user; the identifier is the principal, not a path segment. | design |
| RAPI-VALID-005 | REST API | Idempotency-Key support on 19 creation endpoints is a feature, not a defect. | deferred |
| RAPI-ERR-003 | REST API | Error responses use the project's `ResponseDto` envelope (`code`, `message`); switching to RFC 9457 `ProblemDetail` breaks every client error handler. | design |
| RAPI-PAGE-003 | REST API | Feeds use offset/limit (infinite scroll) while admin lists use page/size; both are intentional. | design |
| RAPI-VER-001 | REST API | All controllers are served under `/api/v1` via `WebMvcConfig#configurePathMatch`; the scanner inspects the un-prefixed mappings and does not see it. | false positive |
| RAPI-DTO-004 | REST API | 111 response DTOs are Lombok `@Data`/`@Builder` classes with ~2,300 setter call sites across 175 main and test files. Immutability is a separate mechanical refactor. | deferred |
| SEC-CONFIG-007 | Security | The flagged "hardcoded secrets" are values of the git-ignored local `.env`, imported as a property source by `spring.config.import`. Nothing is literal in tracked configuration. | false positive |
| SEC-CSRF-001 | Security | The API is stateless (JWT); CSRF is enforced by the custom double-submit `CsrfTokenFilter`, which the scanner does not recognise as a `CsrfFilter`. | false positive |
| SEC-METHOD-001 | Security | `@EnableMethodSecurity` is declared on `SecurityConfig`; 165 `@PreAuthorize` annotations are enforced (the scanner misses the annotation on a `proxyBeanMethods=false` class). | false positive |
| SEC-HEAD-003, SEC-HEAD-005, SEC-HEAD-006, SEC-HEAD-010 | Security | These flag chain #0, BootUI's own dev-only security chain, not the application chain (which sets CSP, Referrer-Policy, Permissions-Policy, COOP/CORP). | false positive |
| SEC-ACT-006 | Security | A separate management port would change the Prometheus scrape target in production monitoring. Actuator is already restricted to `SUPER_ADMIN` behind its own chain. | design |
| SPRING-WIRING-009 | Spring | The only remaining public mutable field is `$$beanFactory` on Spring Security's own `AuthorizationProxyWebConfiguration` CGLIB proxy — framework code. | false positive |
| SPRING-CONFIG-001 | Spring | Lazy initialisation trades startup time for wiring errors surfacing at first request; not wanted for a long-running server. | design |
| SPRING-PERF-001 | Spring | Virtual threads change the runtime model of the WebSocket/RabbitMQ/scheduler stack; to be evaluated on its own with load tests. | deferred |
| SPRING-WEB-003 | Spring | Nginx Proxy Manager terminates TLS and HTTP/2 in front of the application. | design |
| MEM-FOOTPRINT-004 | Memory | Host swap pressure on the developer machine; the JVM footprint is already bounded (`-Xmx768m`). | environment |
| HIB-QUERY-001 | Hibernate | Six `@Modifying` statements (`ChatRepository.touchUpdatedAt`, `ReputationEventRepository.markSnapshotApplied`, `RefreshTokenRepository.revokeAllUserTokens`, `SessionRepository.deleteByUser`, `PushSubscriptionRepository.deleteByUserId`, `UserRepository.incrementTotalUnreadCount`) are called inside transactions that keep mutating and saving managed entities afterwards; `clearAutomatically` would detach them and lose those writes. The other twelve sites now clear+flush automatically. | design |
| DB-HIB-006 | Database | The seven "missing columns" are `@ElementCollection` columns that live in their collection tables; the advisor resolves them against the owner table. | false positive |
| DB-SCHEMA-009 | Database | `messages(chat_id, sender_id, client_id)` and one other unique index intentionally include a nullable column (client-supplied id is optional). | design |

## Fixed in code (2026-09-07 → 2026-09-08)

See the git history for details. Highlights: open-in-view off with fetch-joins where needed; bounded
`applicationTaskExecutor` (`spring.task.execution.mode=force`) and a named `taskScheduler`;
`proxyBeanMethods=false` on all configuration classes with sibling `@Bean` calls converted to parameters;
`@Valid` on all request bodies; `optional=false` on 57 non-nullable `@ManyToOne`s; 70 foreign-key indexes;
derived `deleteBy` methods converted to bulk deletes; eager to-one fetch-joins; 22 vulnerable transitive
dependencies patched via Spring Boot BOM property overrides; `@Tag`/`@Operation` documentation and JSON
`consumes` on mutating endpoints; controllers decoupled from repositories via `service.lookup`; constructor
injection; immutable `@ConfigurationProperties`; dedicated actuator security chain.

## Deployment-target readiness reports (all dismissed)

`graalvm_scan` and `crac_scan` do not report defects — every item has status `REVIEW` and describes work
that would be required **if** the application were built as a GraalVM native image or checkpointed with
CRaC. NeoChatHub is deployed as a JVM fat jar (`deploy/start_script.sh`), so both are out of scope and
their rules are dismissed wholesale:

| Rules | Why dismissed |
|---|---|
| GRAAL-SCAN-001, GRAAL-REFLECT-001, GRAAL-RES-001, GRAAL-SER-001, GRAAL-SER-002, GRAAL-SEC-001, SPRING-AOT-003 | Native-image readiness only. The flagged code (`DtoWarmup` classpath scanning, OAuth cookie JDK serialization, the web-push security provider) is correct on the JVM. Revisit if a native build is ever adopted; BootUI can generate `reachability-metadata.json` at that point. |
| CRAC-SECRET-001, CRAC-POOL-001, CRAC-POOL-002, CRAC-POOL-004, CRAC-FILE-001, CRAC-LIFECYCLE-002, CRAC-RANDOM-002 | Checkpoint/restore readiness only; `org.crac` is deliberately not on the classpath. CRAC-SECRET-001 in particular flags ordinary DTO fields named `token`/`password`, which only matter inside a distributable checkpoint image. |

## Pentesting: the one scanner that cannot reach zero

`pentest_scan` does not consult the dismissal store, so its findings stay visible by design. Three of the
original seven were **fixed** by shrinking the default attack surface; the remaining four were each
verified against the code and cannot be resolved without breaking working functionality.

### Fixed

| Finding | Change |
|---|---|
| PT-A05-055, PT-A05-040 — actuator metrics/Prometheus reachable | `management.endpoints.web.exposure.include` is now `${MANAGEMENT_ENDPOINTS:health,info}`. A dev box publishes only the probes; production opts back in by setting that variable for its scrape target. |
| PT-A05-041 — `/v3/api-docs` exposed | springdoc is now `${SPRINGDOC_ENABLED:false}` in every profile (it was only disabled under `prod`). The generated schema lists every route and request shape, and it is tooling rather than application behaviour. A developer opts in on their own machine with `SPRINGDOC_ENABLED=true` in the git-ignored `.env`. |
| PT-A05-060 (partial) — `'unsafe-eval'` removed | `script-src` no longer grants `'unsafe-eval'`; it grants the strictly narrower `'wasm-unsafe-eval'` instead. The grant had been justified by the in-browser TensorFlow.js/nsfwjs moderation model, and that justification turned out to be **wrong**: neither `@tensorflow/tfjs` nor `nsfwjs` contains an `eval()`/`new Function()` call in any of its 17 shipped bundles (minified included), because `tf.ready()` selects the WebGL backend, which compiles GLSL shaders rather than JavaScript. Verified empirically as well — with the tightened header the 5.3 MB TensorFlow chunk executes with **zero CSP violations and zero page errors**, while `new Function()` is correctly blocked and `WebAssembly.compile()` still succeeds. This closes the eval-based XSS escalation path. |

### Remaining, with the evidence for each

| Finding | Why it stays |
|---|---|
| PT-A05-060 — CSP weakened (MEDIUM) | **Half of this was fixed** — see below. What remains is `'unsafe-inline'` in `script-src`, which the Next.js static export requires: each pre-rendered page carries ~47 inline `<script>` blocks (the `self.__next_f.push` hydration payload plus the accent/night-mode pre-paint snippets that exist precisely to run before first paint). A static export cannot carry a per-request nonce, and the hydration payload differs per page and per build, so hashing is not maintainable by hand. Closing it properly means a build step that extracts every inline-script hash into the policy — a real project, tracked separately. |
| PT-A05-001 — probe evidence truncated | The probe path hits the SPA deep-link fallback, which returns the 170 KB app shell — well past the scanner's 8 KiB budget. Returning a short 404 instead would break client-side routing for every dynamic route. The check is *indeterminate*, not failing. |
| PT-A05-044 — trusts `X-Forwarded-*` | Required behind Nginx Proxy Manager, which overwrites these headers; `util.ClientIp` additionally trusts only the last hop. The suggested alternative (Tomcat `internal-proxies` allow-list) would re-plumb the client-IP resolution that rate limiting, geo lookup and audit logging depend on — real risk for an INFO heuristic whose own text says the setting "is often legitimately required behind a trusted reverse proxy". |
| PT-A05-068 — no Cross-Origin-Embedder-Policy | Both `require-corp` and `credentialless` require every cross-origin iframe to send its own COEP header. Cloudflare Turnstile does not, so enabling either blocks the captcha and therefore signup and login. |

## Verifying the baseline

With the backend running (`./gradlew bootRun`, dev profile), every advisor should report
`violationsFound: 0` once dismissals are applied. Dismissals live in `.bootui/boot-ui.yml`, which is
committed so the whole team shares this baseline; delete an entry there to re-open a rule for review.
