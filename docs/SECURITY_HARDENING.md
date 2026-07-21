# NeoChatHub — Security Hardening Status & Runbook

Living checklist mapped to the 15-point production-security framework. Legend:
**✅ Done** (already in the codebase) · **🆕 Implemented this pass** · **📋 TODO** (steps below).

Last updated: 2026-07-20.

---

## Status by category

### 1. Authentication & Authorization
- ✅ Account lockout after repeated failed logins (`LoginAttemptService`)
- 🆕 **Password breach checking** (HIBP k-anonymity, `PwnedPasswordService`) at signup / change / reset — fail-open, toggle `app.auth.breach-check.enabled`
- 🆕 **Re-auth for sensitive actions**: password change already required current password; **account deletion now requires password** (accounts with a local password) — ⚠️ frontend must send it, see §O. Change-email has no user endpoint (n/a).
- ✅ Single active session (forced single-device: login revokes other sessions)
- ✅ Account recovery safeguards (single-use, TTL, hashed, anti-enumeration reset tokens)
- 📋 Impossible-travel / unusual-location detection — see §P
- 📋 Device fingerprinting (optional) — see §P
- ✅ JWT signature verification (HS256, `verifyWith`, no default secret)
- ✅ Refresh-token rotation + reuse detection (opaque UUIDs, single-device policy)
- ✅ Access-token expiry (15 min)
- 🆕 Password hashing upgraded to **Argon2id** (`DelegatingPasswordEncoder`; legacy BCrypt still verifies, BCrypt cost raised to 12 for any bcrypt path)
- ✅ Password-reset tokens (256-bit, single-use, 30-min TTL, anti-enumeration) — 🆕 now **hashed at rest** (SHA-256 key in Redis)
- ✅ RBAC (`@PreAuthorize`, `ROLE_SUPER_ADMIN` admin API)
- ✅ Session revocation on logout / reset / new login; device/session management (`/auth/sessions`)
- 📋 **Email-verification enforcement** — see §A
- 📋 **MFA / TOTP** — see §B

### 2. Request Security
- 🆕 SSRF guard (`util/SsrfGuard`) on soundtrack download + web-push endpoints
- ✅ CSRF (custom `CsrfTokenFilter` for the cookie flows)
- ✅ CORS whitelist (fails startup on wildcard+credentials)
- ✅ Request size limits (multipart 30/35 MB) — 🆕 per-field `@Size` caps on message/signup DTOs
- 🆕 File-upload magic-byte verification + MIME allow-list (`util/UploadValidator`)
- ✅ JSON schema / bean validation (`@Valid` + validators; 🆕 `@ValidUsername` wired)

### 3. Rate Limiting & Abuse
- ✅ Per-IP + per-user HTTP rate limiting (Redis, 100 auth / 60 anon per min)
- ✅ Login throttling (`LoginAttemptService`, 5/user + 20/IP per 15 min)
- 🆕 OTP/email resend cooldown (60 s/recipient)
- 🆕 WebSocket message-send flood limit (120/10 s/user) + 🆕 connect-rate limit (30/min/user)
- 🆕 Friend-request daily cap (50/day)
- ✅ Report/block abuse (one open report per pair)
- 📋 Global DDoS (Cloudflare) — see §J

### 4. Input Validation
- ✅ XSS: React auto-escaping on render; email HTML server-side-escaped
- ✅ SQL-injection: JPA + bound params throughout
- 🆕 SVG upload sanitization + serve-side sandbox
- 📋 HTML/Markdown sanitization for any rich-content render path — see §F
- 🆕 Username validation (`@ValidUsername`)
- ✅ File-name sanitization (`MediaKeys.isSafeKey`)

### 5. File Upload Security
- 🆕 MIME allow-list + magic-byte verification
- ✅ Per-type size limits; random UUID filenames; stored outside static dir
- 📋 Image re-encode + EXIF strip — see §C
- 📋 Virus scanning (ClamAV) — see §D

### 6. WebSocket Security
- ✅ JWT auth on CONNECT (rejects anonymous); subscription authorization (chat membership)
- ✅ Origin validation (`setAllowedOriginPatterns`); heartbeat 25 s
- 🆕 Message size / send-time / send-buffer limits; connect-rate limit
- 📋 Max concurrent sessions per user — see §E

### 7. Database Security
- ✅ Parameterized queries; audit logs (`AdminAuditLog` + `AuditLog`)
- 📋 TLS connection — see §G1
- 📋 Least-privilege DB role — see §G2
- 📋 Migrations (Flyway) / drop `ddl-auto: update` — see §G3
- 📋 Backups / PITR / read replicas / slow-query log — see §H

### 8. HTTP Security Headers
- ✅ HSTS, CSP, X-Frame-Options, Referrer-Policy, Permissions-Policy
- 🆕 explicit `X-Content-Type-Options: nosniff`, COOP `same-origin`, CORP `same-site`
- ⚠️ COEP `require-corp` intentionally omitted (breaks Turnstile + https media); enable only after moving those to CORP-tagged responses.

### 9. Infrastructure Security — all 📋, see §I
Firewall · Fail2Ban · auto security updates · rootless/non-root/read-only containers · secret manager · SSH key-only · IDS.

### 10. API Protection
- ✅ API versioning (`/api/v1`)
- 📋 API keys / request signing / idempotency keys / replay protection — see §K
- ✅ Pagination limits (present on list endpoints) · 📋 usage logging → §12

### 11. Privacy & Data Protection
- ✅ Encrypt sensitive data at rest (per-chat AES-256-GCM); secure cookie flags (`HttpOnly`+`Secure`+`SameSite`) — 🆕 `Secure` now defaults true in deployed profiles
- 🆕 PII masking in logs (emails masked, token links → DEBUG)
- ✅ Account deletion (30-day soft-delete + purge)
- 📋 Encrypt backups — §H · **Data export** — see §L
- 📋 Formal data-retention policy — §H

### 12. Monitoring & Logging
- ✅ Actuator health/metrics/prometheus; audit logs
- 🆕 Structured (ECS-JSON) console logging in prod
- 📋 Error tracking (Sentry), uptime, resource alerts, DB health, WS metrics — see §M

### 13. Messaging-Specific
- ✅ Message ownership + conversation-membership verification (send/edit/delete/pin/star)
- 🆕 Read-receipt authorization (membership guard on markRead/markDelivered); 🆕 **typing/activity-indicator authorization** (membership-checked, Redis-cached 60 s)
- ✅ Duplicate prevention (clientId + unique constraint); blocked-user enforcement (both directions); report system; TLS in transit
- 🆕 Message flood limit (WS send limit); friend-request cap; anti-spam heuristics = rate limits + caps + moderation
- ✅ Profanity filtering (moderation word-lists + NSFW sidecar); prevent joining unauthorized rooms (guest gate + membership)
- 📋 Attachment ownership on the media endpoint — see §L(media authz); Link-safety scanning — see §F2; advanced automatic abuse detection — see §Q

### 14. Frontend Security
- ✅ CSP-compatible; React escaping; no secrets in bundle; clickjacking (frame-ancestors 'none'); trusted image domains (CSP img-src)
- 📋 Subresource Integrity; avoid long-lived tokens in localStorage (access token is in-memory ✅, refresh is HttpOnly cookie ✅) — see §N

### 15. Operational Security — all 📋, see §I/§H
Automated backups + restore testing · DR plan · zero-downtime deploys · health/readiness/liveness · secret rotation · 📋 dependency scanning (see §U) · container image scanning.

---

## Deferred CODE features — implementation steps

### §A. Email-verification enforcement
The pipeline exists (`User.isVerified`, token email, confirm endpoint) but gates nothing.
1. Add config `app.auth.require-email-verification: ${REQUIRE_EMAIL_VERIFICATION:false}`.
2. In `OAuth2LoginSuccessHandler`, set `user.setVerified(true)` on first OAuth login (Google already verified the address).
3. Add a `OncePerRequestFilter` (after `JwtAuthenticationFilter`) that, when the flag is on and the principal is a non-guest, non-verified user, returns 403 `TM_UNVERIFIED` for all `/api/**` **except** `/auth/logout`, `/auth/verify-email`, `/auth/resend-verification`, `/auth/me`.
4. Frontend: on `TM_UNVERIFIED`, show a "verify your email" gate with a resend button.
5. Roll out with the flag **off**, verify OAuth users are flagged verified, then enable.

### §B. MFA / TOTP (SUPER_ADMIN first, then opt-in for users)
1. Add `dev.samstevens.totp:totp:1.7.1`.
2. `User`: `totpSecret` (encrypted via existing `MasterKeyService`), `mfaEnabled`, `recoveryCodesHash` (list).
3. Endpoints: `POST /auth/mfa/setup` (returns provisioning URI + QR), `POST /auth/mfa/enable` (verify first code), `POST /auth/mfa/disable`, and a `mfaToken` step in `login` when `mfaEnabled`.
4. On login with MFA: return a short-lived `mfa_pending` token instead of full tokens; require `POST /auth/mfa/verify` before issuing access/refresh.
5. Enforce MFA for `ROLE_SUPER_ADMIN` unconditionally.
6. Frontend: enrollment QR + code entry screens; recovery-code download.

### §C. Image re-encode + EXIF strip  ⚠️ test carefully
Naive `ImageIO` re-encode **drops EXIF orientation → rotated photos**, and lacks HEIC/WebP write support. Do it right:
1. Use a library that honors orientation, e.g. Thumbnailator (`net.coobird:thumbnailator`) with `Orientation` handling, or libvips (`jvips`) for HEIC/WebP.
2. In `StorageServiceImpl` (image branch): decode → apply EXIF orientation → re-encode to canonical JPEG (q≈0.85) / PNG / WebP, then store the re-encoded bytes (this strips all metadata incl. GPS).
3. Keep a size guard (reject > N megapixels — decompression-bomb defense).
4. Fall back to storing the original **only** if the format is unsupported AND magic-byte-verified; log it.

### §D. Virus scanning (ClamAV)
1. Run `clamav/clamav` as a sidecar (compose service, port 3310).
2. Add a `ClamAvScanner` using clamd INSTREAM (or `xyz.capybara:clamav-client`).
3. In `UploadController.uploadFile`, after `UploadValidator.validate`, stream the temp file to clamd; reject on `FOUND`.
4. Fail policy: fail-**closed** for `profile|post|story`, fail-open (log) for private conversation media if the scanner is down.

### §E. Max concurrent WebSocket sessions per user
Not done because a missed DISCONNECT can leak set entries and lock a user out. Safe design:
1. On CONNECT: `SADD ws:sessions:{user} {sessionId}` with a per-member TTL (Redis 7 `EXPIRE`-per-member or a ZSET scored by timestamp).
2. Reject if the live-session count (after pruning entries older than the heartbeat window) exceeds e.g. 10.
3. On DISCONNECT (handle `StompCommand.DISCONNECT` in the interceptor) and via the existing idle reaper: `SREM`.
4. Prefer the ZSET-by-timestamp approach so stale entries self-expire.

### §F. HTML/Markdown sanitization & §F2. Link-safety
- **F1**: if any field is ever rendered as HTML (e.g. `PostRequest.richContent`), sanitize on write with OWASP Java HTML Sanitizer (`com.googlecode.owasp-java-html-sanitizer`) using an allow-list policy.
- **F2 link safety**: extract URLs from message/post text; check against Google Safe Browsing API (or a local blocklist) in `ContentModerationServiceImpl`; flag/strip on hit. Requires a Safe Browsing API key.

### §L. GDPR data export
1. `GET /api/v1/auth/export` (authenticated, rate-limited).
2. Aggregate: account/profile, settings, sessions, friends, posts, stories, and message history. **Message history is AES-GCM at rest** — decrypt per-chat via `ChatKeyService` before serializing, and stream to avoid OOM (can be large).
3. For large accounts, generate asynchronously and email a time-limited download link (reuse the mail + signed-URL infra).
4. Return `application/json` (or a zip with media manifest).

---

## Infrastructure / Ops runbook

### §G. Database
**G1 — TLS.** Append `?sslmode=require` (or `verify-full` with a pinned CA) to `DATABASE_URL` in `.env.prod`; enable `ssl = on` on the Postgres server with a valid cert.
**G2 — Least privilege.** Stop running as the DB owner. Create a scoped role:
```sql
CREATE ROLE neochat_app LOGIN PASSWORD '<strong>';
GRANT CONNECT ON DATABASE talkMe_prod TO neochat_app;
GRANT USAGE ON SCHEMA public TO neochat_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO neochat_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO neochat_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO neochat_app;
-- NO CREATE/DROP/ALTER.
```
Point `DATABASE_USERNAME` at `neochat_app`.
**G3 — Migrations.** Adopt Flyway: add `implementation 'org.flywaydb:flyway-database-postgresql'`, move the current schema into `src/main/resources/db/migration/V1__baseline.sql` (use `flyway baseline`), set `spring.jpa.hibernate.ddl-auto: validate`. Removes the boot-time ad-hoc DDL risk and lets G2 work (app no longer needs DDL rights).

### §H. Backups & retention
- Nightly `pg_dump` + WAL archiving for PITR; **encrypt dumps** (`age`/`gpg`); off-site copy.
- **Test restores monthly** (restore to a scratch DB, run smoke checks).
- Optional read replica for reporting/discover queries.
- Enable `log_min_duration_statement = 500ms` (slow-query log).
- Document retention: soft-deleted accounts purge at 30 days (already coded); define log/backup retention (e.g. 90 days).

### §I. Host / container / network
- **Firewall**: allow only 80/443 (and 22 from admin IPs). Bind Postgres/Redis/RabbitMQ to `127.0.0.1` (per prior hardening) — verify.
- **Fail2Ban**: jails for sshd + an Nginx 4xx/429 filter.
- **Auto updates**: `unattended-upgrades` (security only).
- **SSH**: `PasswordAuthentication no`, `PermitRootLogin no`, key-only.
- **Containers**: run as non-root (`USER 1000`), `read_only: true` + `tmpfs` for scratch, `cap_drop: [ALL]`, `no-new-privileges`, resource limits; consider rootless Docker/Podman.
- **Secrets**: move from `.env.*` to a manager (Docker/K8s secrets, Vault, or SOPS-encrypted files). `.env.*` is now gitignored — rotate any secret ever committed.
- **IDS**: host — Wazuh or OSSEC; file-integrity monitoring on the media root + jar.
- **Image scanning**: Trivy in CI on the built image.

### §J. Edge / DDoS
Front with Cloudflare (or equivalent): WAF, L3/4 DDoS, bot mode for `/auth/*`, rate rules, and cache for `/_next/**` static assets. Keep the app's own limiters as defense-in-depth.

### §K. API protection (integrations)
If/when third-party integrations are added: issue hashed API keys (store SHA-256), HMAC request signing with a timestamp + nonce (reject skew > 5 min → replay protection), and `Idempotency-Key` support on mutating endpoints (store key→response in Redis for 24 h).

### §M. Monitoring
- **Errors**: Sentry (`io.sentry:sentry-spring-boot-starter`) with PII scrubbing.
- **Uptime**: external check on `/actuator/health` (UptimeRobot/BetterStack).
- **Metrics/alerts**: Prometheus is already exposed — add Grafana alerts for CPU/RAM/disk, DB connection pool saturation, and WS session count/heartbeat failures.
- **Liveness/readiness**: expose `health.probes.enabled: true` (K8s) if orchestrated.

### §N. Frontend
- Add Subresource Integrity to any third-party `<script>`/`<link>` (Turnstile is loaded from Cloudflare — add `integrity` if they publish hashes, else keep it in CSP allow-list only).
- Confirm tokens: access token in memory (✅), refresh token HttpOnly cookie (✅) — do not move either to `localStorage`.

---

### §O. Frontend change — account-deletion re-auth  ✅ DONE
The backend requires the current password to delete an account **that has a local password** (OAuth-only exempt). Implemented in `TalkMe-UI`:
- `UserService.deleteAccount(password?)` sends `DELETE /users/me` with body `{ password }` when provided.
- `account-page.tsx` uses a **retry-on-TM_030** flow: first attempt sends no password (OAuth accounts delete cleanly); on `401 TM_030` it opens a password `Dialog` and retries, showing "Incorrect password" on a second TM_030. No need to detect OAuth on the client.
- Also fixed a latent break: the signup form derives the username from the email prefix, which the new `@ValidUsername` regex would reject (dots, etc.) — the derived value is now sanitized to `^[a-zA-Z0-9_]{3,30}$`.

### §P. Login anomaly / impossible-travel / device fingerprinting
Foundation exists: `CountryDetectionService` resolves the login IP → country, and login-alert emails are wired.
1. On successful login, persist `{ip, country, city, coarse lat/long, userAgent, timestamp}` to a `login_history` table.
2. **Impossible travel**: compare to the previous login — if `distance / elapsed_hours` exceeds a threshold (e.g. 900 km/h), flag and send a "new/unusual login" email (reuse `sendLoginAlert`), optionally force re-verification.
3. **New device/location**: hash `userAgent + coarse-IP` → if unseen, email the user.
4. **Device fingerprinting (optional)**: have the client compute a fingerprint (FingerprintJS) and send it as a header; store per session; alert on new fingerprint. Keep it advisory, not an auth factor.

### §Q. Automatic abuse detection (beyond the existing caps)
Current: rate limits, friend-request/report caps, blocked-user enforcement, moderation word-lists + NSFW. To add heuristic detection:
1. Track per-user rolling counters in Redis: messages/min, distinct recipients/hour, duplicate-content ratio, report-received count.
2. On threshold breach, auto-apply a graduated response (soft rate-cap → temporary send mute → admin review queue) and emit an `AuditLog` event.
3. Feed repeated `MatchReport`s against one user into the same queue.

### §R. API protection — idempotency, replay, deprecation
- **Idempotency keys** (§K): accept `Idempotency-Key` on POST/PATCH; store `key → (status, body)` in Redis 24 h; replay the stored response on repeat. Messages already have per-message idempotency via `clientId`.
- **Replay/timestamp/signing**: only needed for machine-to-machine integrations — implement HMAC request signing with a timestamp + nonce (reject skew > 5 min) when those are added.
- **Consistent error responses**: verify the global `@RestControllerAdvice` returns only `{code, message}` (no stack traces/SQL) in prod — it does today; keep `server.error.include-message`/`include-stacktrace=never` in prod config.
- **API deprecation policy**: document a versioning/sunset policy (e.g. `Deprecation` + `Sunset` headers, 6-month overlap) before introducing `/api/v2`.

### §S. Infrastructure (round 2) — mostly ops
- **Network segmentation / private VPC**: DB/Redis/RabbitMQ on a private subnet, reachable only from the app tier; verify they're bound to `127.0.0.1`/private IPs (prior hardening did this — re-verify with `ss -tlnp`). This directly closes the "public-IP Postgres" risk in §G.
- **Separate staging/prod**: distinct DBs, secrets, and OAuth clients; never point staging at prod data.
- **Immutable deploys**: build one versioned image, promote the same artifact staging→prod; no in-place mutation.
- **DNSSEC**: enable at the registrar if supported; **NTP**: `timedatectl set-ntp true` (TOTP + token TTLs depend on correct clock).

### §T. Database (round 2)
- **Failover**: managed Postgres with a standby (or Patroni); document promotion steps.
- **Backup integrity**: after each backup, restore to a scratch instance and run a checksum/row-count smoke test (automate; alert on failure — §M).
- **Migration rollback**: with Flyway (§G3), pair each `V__` with a tested down-path or a forward-fix policy; never edit an applied migration.
- **Activity monitoring**: enable `pgaudit` (or log DDL + failed logins) and ship to the log platform.

### §U. Container & CI/CD security
- **Containers**: minimal base (`eclipse-temurin:25-jre` or distroless), `USER 1000`, `read_only: true` + `tmpfs:/tmp`, `cap_drop:[ALL]`, `security_opt:[no-new-privileges, seccomp=default]`, AppArmor profile; sign images (cosign) and verify on deploy; rebuild weekly for base-image CVEs.
- **CI/CD**: secret scanning (gitleaks) + SCA (add the OWASP dependency-check gradle plugin — `id 'org.owasp.dependencycheck'` — and run `dependencyCheckAnalyze` in CI with an `NVD_API_KEY`; keep it OUT of the local build lifecycle) + license check + SAST (CodeQL/SpotBugs+FindSecBugs) + DAST (OWASP ZAP against staging); protected `main` with required reviews; optional commit signing.

### §V. Monitoring & DR (round 2)
- **Alerts**: login-anomaly (§P), traffic-spike (rate-limiter 429 surge), backup-failure, **SSL-cert expiry** (Certbot renewal + expiry probe), DB replication lag, RabbitMQ queue backlog, disk/CPU/RAM. Wire via Prometheus/Grafana (already exposed) + Alertmanager.
- **Log retention**: define (e.g. app 90 d, audit 1 y, access 30 d) and enforce at the log platform.
- **DR**: multi-region **encrypted** backup copies; written recovery runbook; set RTO/RPO targets (e.g. RTO 4 h / RPO 15 min via WAL archiving); run a restore drill quarterly.

### §W. Mobile / PWA
- **Push validation**: web-push endpoint is 🆕 SSRF-guarded + https-only; VAPID-signed. Keep it.
- **Service Worker integrity**: served same-origin with `Content-Type: application/javascript` + `Cache-Control: no-cache` on `sw.js` so updates aren't stale; rely on the CSP.
- **Offline data**: IndexedDB is wiped on logout/account-switch and namespaced per-user (see the local-DB isolation work). If ever storing secrets offline, encrypt with a key derived from a session secret — today no long-lived secret is persisted client-side.
- **Secure cache invalidation**: version the SW cache name per release so old assets are evicted on activate.

### §X. Known transitive dependency findings (SCA / Mend)
Fixes applied in `build.gradle`:
- **async-http-client** (CVE-2024-53990 **8.1**, CVE-2026-40490 6.8) → **removed** via `exclude group: 'org.asynchttpclient'` on the `web-push` dependency. Verified safe: web-push 5.1.1's compiled code contains **no** `org.asynchttpclient` references (it uses Apache HttpClient; we call the sync `send()`), and `dependencyInsight` confirms AHC — and its old `netty-reactive-streams` — are off the runtime classpath entirely. No code change, no runtime risk.
- **jose4j** `0.7.0 → 0.9.6` (force) — clears CVE-2023-31582, CVE-2024-29371, CVE-2023-51775, WS-2023-0116 (via `web-push`; jose4j IS used for VAPID signing so it's kept, just patched).
- **Bouncy Castle** all artifacts `1.78.1 → 1.81` (force on group `org.bouncycastle`). The `*-jdk15to18` variants come transitively via `oci-java-sdk-common`; `bcprov-jdk18on` is our explicit dep.

Accepted / monitored (no fix exists):
- **Bouncy Castle CVE-2026-0636 / CVE-2026-5588 (5.3, "Insufficient Information")** — filed against 1.81, the current latest release; **no fixed version is published** and the advisories are incomplete. Low severity. Action: watch for a BC 1.82+ that lists these as fixed, then bump the `org.bouncycastle` force. Reverting to 1.78.1 is not an option (higher-rated CVEs). These arrive via the OCI SDK + our web-push VAPID dependency.

## Verify after applying
- Backend compiles: `./gradlew compileJava` (all changes above compile clean as of this doc).
- Dependency scan: not wired into the build (reverted) — add the plugin per §U to run in CI.
- Smoke: login (Argon2 rehash), upload (magic-byte reject a renamed file), WS connect/subscribe to a foreign chat (should reject), forgot-password twice in 60 s (2nd suppressed).
- Round 2: signup with `Password123!`-class breached password → rejected (`TM_048`); `DELETE /users/me` without password on a password account → `401 TM_030`, with correct password → scheduled; send typing to a chat you're not in → dropped (no broadcast).
