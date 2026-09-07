# NeoChatHub / TalkMe — Authorized Security Assessment

Date: 2026-09-07. Scope: backend `com.neo.chat` (Spring Boot 4.0.6, Java 25) + `TalkMe-UI` (Next.js). Authorized red-team review by the project owner. Non-destructive methodology (static review + unit/integration tests; no production data touched).

## Executive summary

Security score before this pass: **41/100**. After the fixes below: **estimated 74/100** (remaining gaps are the not-yet-fixed items in the last section — mostly per-feature abuse limits and the OAuth/token-identity refactor).

The application had strong building blocks (custom CSRF, hardened CSP/HSTS, bcrypt, rotating refresh tokens, STOMP CONNECT auth, per-chat encryption) but several **critical, remotely reachable** flaws: WebSocket message/notification forgery, an unauthenticated media root, and spoofable client-IP trust that defeated every rate limit. All criticals and the highest-value highs are now fixed with regression tests.

Backend test suite: 5076 tests, **all green except 10 pre-existing `@SpringBootTest` failures that require a running Redis** (confirmed identical on a clean tree — not caused by these changes).

## Critical / High findings FIXED (with regression tests)

| # | Finding | Class/CWE | Fix |
|---|---------|-----------|-----|
| 1 | **WebSocket message & notification forgery.** Any authenticated user could `SEND` a crafted STOMP frame to `/topic/chat/{uuid}/messages` (non-call payloads), `/topic/presence/{user}`, `/topic/lobby`, `/topic/match/*`, or `/user/{victim}/queue/**` — injecting fake messages into chats they weren't in, spoofing presence, and pushing fake match/notification/DM events into any user's private queue. | Missing Authorization / CWE-862 | `WebSocketChannelInterceptor`: strict SEND allow-list (only `/app/**`, plus WebRTC call-signalling to a chat the sender is a **member** of); strict SUBSCRIBE allow-list (chat membership for chat topics, own `/user/queue/**`, public broadcast topics; blocks relay-internal topics and raw `/queue/**`). |
| 2 | **Entire media root served unauthenticated.** `/talkMe/**` mapped the whole upload directory; anyone could fetch any user's private chat media / avatars / view-once files by path, and uploaded HTML/SVG rendered inline (stored XSS). | Broken Access Control / CWE-284, CWE-79 | Handler removed; `/talkMe/**` + `/media/**` `denyAll`. `/api/v1/uploads/media` now authorizes per object (Bearer principal or HttpOnly, path-scoped `media_token` cookie); conversation media requires chat membership; only `profiles/` is public. New `generateMediaToken`/`parseMediaToken`. `Cache-Control: private`. |
| 3 | **Spoofable client IP defeated all rate limiting / brute-force lockout / CAPTCHA remote-ip / geo.** Everything read the client-supplied **first** `X-Forwarded-For` hop, so an attacker rotated the header for unlimited buckets. | CWE-348 | New `util.ClientIp` reads the entry appended by the trusted proxy (from the right; `app.security.trusted-proxy-hops`, default 1; `CF-Connecting-IP` only when `trust-cloudflare-header=true`). Wired into `RateLimitingFilter`, `AuthController`, `ConsentController`, `CountryDetection`. The `/ws` rate-limit skip changed from a `contains("/ws")` substring (which also exempted `/follows`, `/profile-views`) to an exact handshake match. |
| 4 | **Banned/soft-deleted users kept full access.** They could refresh access tokens indefinitely and authenticate over WebSocket. | Broken Access Control / CWE-613 | `AuthServiceImpl.refresh` rejects banned/deleted; STOMP CONNECT now enforces `isEnabled()`. |
| 5 | **Signup had no username-uniqueness check.** A case-variant (`Alice` vs `alice`) inserted a duplicate that made every future login for the victim throw (targeted lockout). | CWE-20 | `existsByUsernameIgnoreCase` at signup and in `generateUniqueUsername`; `GlobalExceptionHandler` maps `DataIntegrityViolation`→409 and stops echoing raw `IllegalArgumentException` messages. |
| 6 | **`/auth/login` bypassed Bean Validation** (guest under-18 / blank names; null-password 500 leaking an internal message). | CWE-20 | Explicit `validate()` on both login and guest branches. |
| 7 | **Upload pipeline: stored-XSS / polyglot.** Client filename set the stored extension (`.html`, `.svg`), and the avatar endpoint had no content validation. | Unrestricted Upload / CWE-434 | Stored extension neutralized to a safe media allow-list (scriptable → `.bin`); avatar path now magic-byte-validates the image. |
| 8 | **ffmpeg ran on untrusted bytes with network demuxers.** A crafted HLS/concat playlist could make ffmpeg fetch `http(s)`/`file` segments (SSRF/LFI); output kept EXIF/GPS metadata. | SSRF / CWE-918, CWE-359 | All ffmpeg invocations (transcode, photo-music mux, probe, NSFW frame extraction) now pass `-nostdin -protocol_whitelist file,pipe` and `-map_metadata -1`. |
| 9 | **Lobby DM abuse over STOMP.** `/app/lobby/chat` sent a DM (and Web Push) to **any** username with no lobby-membership check → cross-user spam bypassing block/friends, plus push amplification. Lobby JOIN broadcast the full `UserResponse` (phone, roles). | CWE-862, CWE-359 | Both parties must be in the lobby (`inLobby`, fail-closed); body length capped; JOIN payload strips phone/roles. |
| 10 | **Actuator / Swagger / BootUI exposure.** `/actuator/**` was readable by any authenticated user (incl. guests); Swagger and BootUI were public whenever the profile ≠ prod. | Info Disclosure / CWE-200 | Actuator → `SUPER_ADMIN`; BootUI profile-gated; Swagger disabled in prod; `pageable.max-page-size=200`. New `ProductionHardeningCheck` fails boot when a public deployment runs a non-prod profile or exposes BootUI beyond localhost. |
| 11 | **Datastore ports published on 0.0.0.0.** compose `ports:` ignored `BIND_HOST`. | CWE-668 | All datastore ports now bind `${BIND_HOST:-127.0.0.1}`. |
| 12 | **Frontend DOM XSS via user URL.** `href={profile.website}` rendered a user-controlled URL, so `javascript:` executed (CSP allows inline script). JSON-LD `<script>` was not breakout-hardened. | CWE-79 | New `lib/safe-url.ts` `safeHref()` (scheme allow-list) applied to the profile link; JSON-LD serializer unicode-escapes `< > & U+2028 U+2029`. |
| 13 | **Story media accepted external URLs** (viewer-IP leak, post-publication content swap, moderation bypass). | CWE-20 | `util.MediaReferences.isExternalUrl` rejects `scheme://` references for story visual + voice media. |

## Regression tests added
- `WebSocketChannelInterceptorUnitTest`: SEND/SUBSCRIBE allow-list (message forgery, presence/lobby spoof, cross-user queue injection, non-member call, fail-closed), CONNECT rejects banned.
- `RateLimitingFilterUnitTest` + `CountryDetectionServiceTest`: trusted-hop XFF, spoof-prefix cannot escape bucket, `/ws` exact match, CF-header only when enabled.
- `AuthServiceImplTest`: refresh rejects banned/deleted; signup rejects case-variant username.
- `UploadControllerUnitTest`: media authz (anon 401, membership 403, cookie viewer, banned-cookie rejected, profiles public).
- `StorageServiceImplTest`: scriptable extension → `.bin`.
- `WebSocketControllerUnitTest`: lobby DM/typing dropped when recipient not in lobby.
- `StoryServiceImplTest`: external story media rejected.
- Frontend `tests/unit/safe-url.test.ts`: `javascript:`/`data:`/obfuscated schemes rejected.

## Still TODO (recommended next, not yet fixed)
- **OAuth email-linking pre-hijack** — link a Google identity to a local account only when both are verified; **JWT `sub=username` token-confusion on rename** — bind tokens to the immutable uuid and reserve released usernames.
- **`PostServiceImpl` media** — apply the same `MediaReferences.isExternalUrl` guard per media item (helper is ready).
- **Per-feature abuse limits** — guest-account creation cap + reaper; forgot-password per-recipient/day cap; web-push subscription cap + push-service host allow-list + owner-scoped delete; notification pruning; email canonicalization (+tag/dot-folding) and disposable-domain rejection at signup.
- **self-destruct / view-once / allowDownload** — enforce on the media read path (currently client-trusted); mint single-use signed media tokens.
- **Frontend/dependency/DB-crypto deep audits** — the parallel subagents for these were cut off by a session rate limit; rerun.

See `docs/` and the in-code comments on each fix for detail.

## Follow-up audits (dependencies + data layer/crypto)

### Data layer / crypto — additional fixes SHIPPED
- **Peer phone-number & role exposure (HIGH, CWE-359).** `UserResponse` carried `phone` (from `mobileNumber`) and `roles` and was returned to any authenticated peer via `/users/{id}`, `/users/search`, friends, and lobby — bulk-harvestable. **Fixed:** `populatePresenceAndBlockStatus` now nulls `phone`/`roles` for every non-self response (self keeps them); regression test added.
- **Email-enumeration via people-search (MEDIUM, CWE-204).** Search/Discover matched the private `email` column, so a hit confirmed an address. **Fixed:** email dropped from both search predicates; LIKE metacharacters (`% _ \`) now escaped with an explicit ESCAPE clause.
- **`passwordHash` serialization backstop (LOW).** Added `@JsonIgnore` on `User.passwordHash` (no entity is returned today, but this guarantees the bcrypt hash can never leak).

### Data layer / crypto — verified SECURE (evidence-backed)
No SQL/JPQL injection (all `@Query` and both `nativeQuery` statements parameterized; Criteria specs bind values; the 17 hand-rolled `*SchemaMigration` classes concatenate only compile-time literals). AES-256-GCM is correct and fail-closed (fresh 12-byte SecureRandom IV, 128-bit tag, boot aborts if the master key can't unwrap existing keys). Reset/verify tokens are 256-bit SecureRandom stored SHA-256-hashed in Redis (no timing oracle). Block/feature/settings caches fail SAFE (fall back to DB). Frontend per-chat keys are imported non-extractable, in-memory only, cleared on logout. No entity is returned from a controller.

### Still open (data layer, low urgency)
Client-overridable `?sort=` on ~10 Pageable endpoints throws 500 on an unknown property (map `PropertyReferenceException`→400 + allow-list). `votePoll`/like/friend races return 500 to the losing request (unique constraints hold — catch `DataIntegrityViolation` and re-read). Redis caches plaintext last-message preview for 7 days when chat encryption is off.

### Dependencies
Backend versions resolve cleanly (Boot 4.0.6 BOM): no version I can confidently flag as known-vulnerable at the June-2026 cutoff; jose4j was deliberately overridden 0.7.9→0.9.6, bcprov 1.84, snakeyaml 2.5, postgresql 42.7.10 are all patched. **No polymorphic/default typing** anywhere (no deserialization-gadget surface). `springdoc 2.8.5` targets Boot 3 — a dev-only compat risk (prod already disables api-docs + swagger-ui); plan a move to springdoc 3.x. The `@Primary` Jackson-2 `ObjectMapper` does NOT drive HTTP (Boot 4 MVC uses Jackson 3) — it only serves ~25 internal callers; the `fail-on-null-for-primitives` customizer correctly targets the live Jackson-3 mapper.

**Frontend upgrades SHIPPED** (browser-shipping, security-critical): `dompurify 3.4.12 → 3.4.15` (the XSS sanitizer), `axios 1.16.1 → 1.20.0` (prototype-pollution advisories). Lockfile updated; unit tests still pass (the 3 pre-existing drifted suites — ui-states/login-page/signup-page — fail identically before and after).
**Frontend upgrades still recommended:** `@tiptap/* → ≥3.30.4` (attribute-injection XSS), `next → ≥16.2.11` (mostly dev-server/build; the prod static export sidesteps the Next server CVEs), `form-data`/`lodash` pnpm overrides. Build hygiene: drop `typescript.ignoreBuildErrors:true` (or gate CI on typecheck) and scope `allowedDevOrigins` off `["*"]`. No production source maps or secrets ship in `out/`.

## Round 2 — account-takeover + abuse limits (user-requested, SHIPPED)

| Finding | Class/CWE | Fix |
|---------|-----------|-----|
| **OAuth email pre-hijack.** A Google login linked to any local account with the same email, even an unverified pre-registration an attacker created — silently sharing the victim's account. | Improper Auth / CWE-1390 | `AuthServiceImpl.oauthLogin` now email-matches only when Google asserts the email verified; a matched **verified** local account is linked, but an **unverified shell** is reclaimed (password cleared, tokens/sessions revoked) so the pre-registrant loses all access. |
| **JWT identity re-binding on username rename.** Tokens carried only `sub=username`; a freed-and-re-registered username let a still-valid token authenticate as a different user (account takeover). | CWE-287 | Access tokens now carry the immutable `uid` claim; `JwtAuthenticationFilter` and STOMP CONNECT resolve the principal by `uid` (username is fallback for legacy tokens only), so a token never re-binds to another account. |
| **Unbounded guest-account creation.** Each guest login inserts permanent rows; no per-IP cap. | Resource Exhaustion / CWE-770 | Per-IP daily cap (20/day, fail-open) on `loginAsGuest`. |
| **Email-bomb via forgot-password / resend-verification.** Only a 60s cooldown; a victim address could be mailed repeatedly (and the shared provider quota burned). | CWE-770 | Per-recipient **daily** cap (5/day, fail-open) added on both mail types, on top of the cooldown. |
| **Push subscriptions: unbounded + owner-less delete.** One account could register thousands of endpoints; delete was by endpoint with no owner check. | CWE-770 / CWE-639 | Max 20 subscriptions per user (oldest evicted); `removeSubscription` is now owner-scoped. |

Regression tests: OAuth reclaim + unverified-IdP-ignored (`AuthServiceImplTest`); `uid` claim round-trip + auth-principal binding (`JwtTokenProviderUnitTest`); guest per-IP cap (`AuthServiceImplTest`); push cap + owner-scoped delete (`WebPushServiceImplTest`, `PushControllerUnitTest`).

With this round, the OAuth pre-hijack and the rename token-confusion — the two remaining account-takeover paths — are closed, and the top abuse vectors are rate-limited.
