# Ads Integration — High-Level Design

> Status: **implemented (v1)** · Owner: platform · Default state: **ON for all users**
> (single backend switch; per-user `adsFree` exemption for a future Premium tier)
>
> Goal: monetise NeoChatHub with advertising that is **fully controllable from one
> backend flag**, **fully customisable without a redeploy**, and **never degrades the
> user experience**. Ads live only in content-discovery surfaces (feed, explore,
> reels, stories) — never inside conversations, settings, auth, or moderation flows.

---

## 1. Design principles

1. **One master switch.** A single backend flag — the `ads` FeatureKey, driven by
   `features.flags.ads` (env `FEATURE_ADS`) — turns *all* advertising on or off,
   platform-wide, with no deploy. When off, the client fetches nothing and renders
   nothing: zero network calls, zero DOM, zero cost.
2. **UX first, always native.** Every ad is an in-flow, clearly-labelled
   ("Sponsored") card that matches the surrounding design tokens. No pop-unders, no
   redirects, no interstitials-on-navigation, no autoplay sound. Frequency is capped
   and tunable per surface.
3. **Reuse, don't reinvent.** The on/off gate rides the existing enum-driven feature
   system (`FeatureKey` / `FeatureFlags` / `FeatureAccessService` / `/features`). The
   per-user opt-out reuses the existing feature self-toggle (`PUT /features/ads`). No
   new auth, caching, or entitlement machinery.
4. **Provider-agnostic (two networks).** A single `<AdSlot>` renders whichever network
   the backend config names — **`adsense`** or **`adsterra`** — globally or per surface.
   Switching or mixing them is a backend config change, not a code change.
5. **CSP stays hardened.** A network's script/frame hosts are added to the CSP only when
   its domains are configured (`ads.csp-domains`); otherwise the CSP is byte-identical to
   the pre-ads policy. No ads render until a network is configured.

---

## 2. Platform recommendation

The app has adult-adjacent surfaces (Night Owl, Flirt Lobby, stranger matching), so
mainstream AdSense may reject or limit the account, and it is slow to approve. Two
networks are supported and can run at the same time on different surfaces:

| Provider   | Use it for | Notes |
|------------|-----------|-------|
| `adsterra` | **Fastest cash / now** | Approves in 24–48h, accepts social/dating content, net-15 payout. Use **Native Banner only** (no popunder/social-bar). One zone = one container, so cap its surface at `MAX=1`. |
| `adsense`  | **Best RPM long-term** | Apply in parallel; point units only at the clean surfaces (feed/explore). Highest quality ads if approved. |

The recommended rollout: **Adsterra on the feed now → apply to AdSense in parallel → put
AdSense on feed/explore and keep Adsterra on reels once approved** — all by flipping
backend config, no redeploy.

---

## 3. Backend design

### 3.1 The single flag (on/off gate)

Add one enum key — the whole gate is one line:

```java
// FeatureKey.java
ADS (null, false, false, null, true),   // defaultEntitled=true → everyone when globally on
```

Master switch in `application.yml` (env-overridable, **no deploy** — matches the
existing FeatureFlags contract):

```yaml
features:
  flags:
    ads: ${FEATURE_ADS:false}   # ← THE single flag. false = dark. Flip to go live.
```

Effective access for `(user, ads)` resolves through the existing precedence:
`global kill-switch → admin DENY → entitlement → user self-toggle → ON`. That means:

- **On by default:** `FEATURE_ADS` defaults **true**, so every user currently sees ads
  (provider `house` → safe in-app promos until a real network is wired).
- **Platform kill:** set `FEATURE_ADS=false` → ads gone for everyone instantly.
- **Per-user exemption (`User.adsFree`, default false):** a hard, server-side gate — an
  ad-free user is withheld the `ads` entitlement in `FeatureAccessService.resolve()`
  (before any grant), so they see **no** ads even if ads are globally on and even if an
  admin ALLOW grant exists. This is the single seam a **future Premium tier flips**
  (`user.adsFree = true` on upgrade). Everyone else (the default) sees ads. No client
  change needed — `useFeature("ads")` already reflects the exemption via `/features`.
- **No UI control.** On/off and all tuning are governed **only** by the backend (config
  file for global state, `User.adsFree` for the per-user exemption). No user-facing
  toggle; the client only *reads* state (`useFeature("ads")` / `/ads/config`).

The `ads` wire-name flows out of `GET /features` automatically — no controller change.

### 3.2 Customisation (config, no redeploy)

`AdsProperties` (`@ConfigurationProperties(prefix = "ads")`) holds everything tunable,
served read-only via a new endpoint:

```
GET /api/v1/ads/config   (authenticated; returns global, non-sensitive config)
```

Response (`AdsConfigResponse`):

```jsonc
{
  "enabled": true,                 // = features.flags.ads state (single source of truth)
  "provider": "adsterra",          // adsense | adsterra (global default)
  "label": "Sponsored",            // shown on every ad
  "adChoicesUrl": "https://...",   // optional "why this ad?" link
  "clientId": "",                  // AdSense ca-pub-xxxx (account-wide)
  "scriptUrl": "",                 // global Adsterra zone script
  "frequencyCapPerSession": 30,    // hard per-surface ceiling
  "placements": {
    // provider/scriptUrl are optional per-surface overrides (blank = inherit global),
    // so different surfaces can run different networks at once.
    "feed":    { "enabled": true, "everyN": 6,  "maxPerSession": 12, "unitId": "", "provider": "", "scriptUrl": "" },
    "explore": { "enabled": true, "everyN": 12, "maxPerSession": 12, "unitId": "", "provider": "", "scriptUrl": "" },
    "reels":   { "enabled": true, "everyN": 8,  "maxPerSession": 10, "unitId": "", "provider": "", "scriptUrl": "" },
    "stories": { "enabled": false,"everyN": 6,  "maxPerSession": 6,  "unitId": "", "provider": "", "scriptUrl": "" }
  }
}
```

Every knob — which surfaces show ads, how often, the per-session caps, the provider,
the label, the house promos — is set in `application.yml` under `ads:` and overridable
per-environment via env vars. Tuning frequency to protect UX is a config edit.

### 3.3 CSP (the one place a real network needs care)

The hardened static CSP in `SecurityConfig` blocks third-party scripts. The design
makes the ad-domain allowance **additive and configurable**:

```yaml
ads:
  csp-domains: []   # e.g. [https://pagead2.googlesyndication.com, https://*.adsterra.com]
```

`SecurityConfig` appends `ads.csp-domains` to `script-src`, `frame-src`, `img-src`,
and `connect-src` **only when the list is non-empty**. With the default (`house`,
empty list) the emitted CSP is **byte-identical to today's** — no regression. Enabling
a real network is: set `provider`, `scriptUrl`/`clientId`, add its domains to
`csp-domains`, restart.

---

## 4. Frontend design

### 4.1 Data flow

```
GET /features ──► useFeature("ads")  ─┐
                                       ├─► useAds() ─► { enabled, config }
GET /ads/config ─► useAdsConfig() ────┘     (config fetched only when feature is on)
```

- `useFeature("ads")` — per-user boolean (respects global flag + opt-out). Already exists.
- `useAdsConfig()` — React Query for `/ads/config`, **`enabled` gated on the feature**,
  so a user without ads never calls it.
- `useAds()` — convenience combiner returning `{ enabled, config, placement(name) }`.

### 4.2 `<AdSlot>` — the single rendering primitive

```tsx
<AdSlot placement="feed" index={i} />
```

Responsibilities:
- Renders the correct **variant** per surface (in-feed card, masonry tile, full-screen
  reel/story slide) using the app's design tokens, with a `Sponsored` label + optional
  AdChoices link.
- Renders the correct **provider**: `adsense` (`<ins class="adsbygoogle">` + one-time
  script load) or `adsterra` — as **Native Banner** (invoke.js + container div) or
  **Banner** (fixed-size `srcDoc` iframe). Adsterra **Popunder** and **Social Bar** are
  site-wide (loaded once by `<AdsterraGlobalScripts>`, not per-slot). Network scripts are
  injected **once**, lazily.

**Deterministic fill-gating (no empty slots).** `useAds().placement(name)` returns `null`
unless the surface is enabled **and** the provider can produce a creative (`adsense` has a
client + unit id; `adsterra` has a script url + unit id, plus width/height for banner).
Surfaces only interleave a slot when `placement()` is non-null, so an empty ad slot is
**never inserted** — no blank reels slide, no feed gap, no paint-then-collapse (CLS).

**Caps are deterministic, not stateful.** Each surface shows at most
`min(placement.maxPerSession, frequencyCapPerSession)` ads, enforced in the pure
`interleaveAds` step. No render-time mutable counter, so there is no ordering flash.

> **Real-network unfilled inventory.** A network may return no ad for a slot it accepted
> (fill rate < 100%), so a full-screen slot could momentarily show reserved space. When
> ads aren't configured, nothing renders at all (no fallback). For Adsterra Native Banner
> use `MAX=1` per surface; the Banner (iframe) format is fixed-size and self-contained.

### 4.3 Interleaving helper

`lib/ads/insert-ads.ts` exposes `interleaveAds(items, everyN, cap)` → a typed list of
`{ kind: "item", data } | { kind: "ad", key }`. Surfaces map over the interleaved list;
post keys are unchanged (`post.id`), ad keys are `ad-<n>`, so existing keying, dedup,
optimistic like/save, and infinite-scroll math are untouched. Ad nodes carry no
`post.id`, so per-post state maps simply skip them.

### 4.4 Placement map

| Surface | File | Injection | Default cadence |
|---------|------|-----------|-----------------|
| **News feed** | `main-feed.tsx` | in-feed card between posts | every 6 posts |
| **Explore** | `explore-discover.tsx` | sponsored masonry tile | every 12 tiles |
| **Reels** | `reels-viewer.tsx` | full-screen sponsored reel slide | every 8 reels |
| **Stories** | `story-viewer.tsx` | sponsored story between users | **config-ready, injection deferred** |

> **Stories note.** The `stories` placement exists end-to-end in config (default
> **off**) and `<AdSlot>` supports its full-screen variant, but the render-side
> injection is intentionally **deferred**: the story viewer is a timer-driven nested
> state machine (groups → stories, progress bars, hold-to-pause, auto-advance, viewed
> tracking) where interleaving ad slides risks destabilising playback. Wiring it is a
> follow-up to be done carefully behind the (already-present) flag — not a blocker for
> v1, since the surface ships disabled.

**Never**: 1:1 chat threads, chat list, settings, auth/onboarding, moderation/consent,
payment. These are communication/utility surfaces where an ad would break trust.

### 4.5 Control surface — backend config only

Ads are controlled **exclusively from the backend** — there is deliberately **no UI
toggle**. `features.flags.ads` (env `FEATURE_ADS`) is the single global on/off; all
tuning lives under `ads.*`; and the per-user exemption is `User.adsFree` (default false).
The client only ever *reads* this state (`useFeature("ads")` + `/ads/config`); it can
never change it. A **future Premium tier** simply sets `user.adsFree = true` on upgrade
(server-side) and those users stop seeing ads — no UI toggle, no client change.

---

## 5. Why this doesn't break anything

- **One-switch kill.** `FEATURE_ADS=false` reverts to pre-ads behaviour instantly,
  platform-wide, no deploy.
- **Additive only.** New files + one enum line + one nullable-safe `User.adsFree` column
  (DB-defaulted, so ddl-auto adds it to the populated table cleanly) + surgical
  interleaves guarded by `useAds().enabled`. No existing data model, endpoint, or render
  path changes shape.
- **CSP unchanged** unless a real network's domains are configured.
- **Fails open.** If `/ads/config` errors or the network script fails, `<AdSlot>`
  renders nothing (or a house card) — the surface keeps working.
- **Verified.** Backend compiles; frontend `tsc` clean; multi-dimension adversarial
  review (regression / gating / security-CSP / UX-theming / contract-consistency).

---

## 6. Rollout checklist

1. Ships with `FEATURE_ADS=true`, `ads.provider=house` — all users see safe in-app
   house promos now. (Set `FEATURE_ADS=false` to go dark.)
2. QA: verify slots, cadence, labels, dark/light theming, reduced-motion, and the
   per-user exemption (set a test user's `adsFree=true` → that user sees no ads).
3. Sign up Adsterra → set `provider=adsterra`, `scriptUrl`, `placements.*.unitId`, add
   `csp-domains` → restart → live revenue.
4. Apply to AdSense/Ezoic in parallel; when approved, switch `provider` + domains.
5. Tune `everyN` / `maxPerSession` from analytics to hold the UX/RPM balance.
