# ResalePilot — Project State

**Updated:** 2026-10-05

## Purpose and runtime

ResalePilot refreshes Yaga listings through the normal Spring Boot application:

```text
select eligible current listing → prepare draft and images → publish replacement
→ confirm remote publication → hide old listing → complete job/run
```

Manual `ON_DEMAND` is the preferred execution mode. The stack is Java 25, Spring Boot, Maven, PostgreSQL 17, Flyway, JUnit 5, Testcontainers, Playwright Java, and Google Drive API. Development is primarily on Windows.

| Account | Shop slug |
|---:|---|
| 1 | `nik-ar` |
| 2 | `w-a-k-a` |

Listings, jobs/runs, auth state, Drive folders, candidate selection, and orchestration are account-scoped. A non-default account must never fall back to account 1 auth.

## Refresh entry points and selection

```http
POST /api/yaga/accounts/{accountId}/refresh-on-demand?count=N
POST /api/yaga/accounts/{accountId}/refresh-on-demand/listings/{listingId}
```

The listing-specific route selects exactly one requested listing, checks account ownership and the same eligibility predicates as count-based selection, creates one AUTO/ON_DEMAND job, and uses the same publication, confirmation, hide, and completion pipeline. It rejects ineligible listings, active runs, and unsafe existing jobs. An optional `idempotencyKey` is supported; a mismatched reuse is rejected. It does not change count-based ordering.

Candidates must be current, published Yaga listings with valid account/identity data, an unarchived product, and complete archived image links. Like activity is eligible if the count is zero, the last observed increase is **older than** five days, or a positive count with no observed increase was first observed more than five days ago. Exactly five days is not enough. Selection order is `external_created_at ASC NULLS LAST, created_at ASC, id`.

Active jobs and `RESULT_UNKNOWN` block selection. A `FAILED` job with confirmed `publication_status=PUBLISHED` blocks **the same source listing** so it cannot create another replacement. Failures before confirmed publication remain retryable. The guard does not exclude all failed jobs.

## Import and listing data

```http
POST /api/yaga/accounts/{accountId}/reconcile-listings
POST /api/yaga/accounts/{accountId}/import-listings?onlyNew=true&limit=25
POST /api/yaga/accounts/{accountId}/import-listings?onlyNew=false&offset=0
PATCH /api/yaga/accounts/{accountId}/listings/by-slug/{productSlug}/status
```

`reconcile-listings` performs complete published-list discovery. Absence may change a current local listing to `UNAVAILABLE` and non-current only when `completenessConfirmed=true`. `onlyNew=true` skips existing listing details and scans for missing IDs rather than stopping at the first existing listing. Use `onlyNew=false` to update existing listings. The status PATCH changes local state only; it does not act on Yaga.

Yaga detail `props.pageProps.initialProduct` supplies clothing fields: `size` and `brand` are single exact labels; `colors[].name` and `materials[].name` are multi-value labels. Import and re-import store size/brand and comma-separated color/material on `Product`; missing optional values remain null/empty. Migration V21 adds material and expands color to text. Publication snapshots carry all four fields.

Delivery comes from `initialProduct.shipping` and is stored on `MarketplaceListing` (V22): Omniva, DPD, SmartPosti enabled flags and each enabled carrier's typed `selected_price` package-size code, plus `from_hand_to_hand`, `upon_agreement`, and `bundling`. Null delivery settings mean unknown/not imported; explicit `false` means disabled. Re-import updates known settings. The draft uses the **source listing's** settings. If unknown, publication leaves Yaga defaults untouched; if known, it sets and verifies all carrier/toggle states and enabled package sizes before publication.

Image import deduplicates repeated external image IDs within one detail payload. It keeps the first occurrence, assigns dense display order, reuses existing `MarketplaceListingImage` rows and `ProductImage` links, and removes listing-image rows absent from the latest payload. Conflicting URL or filename metadata for one ID fails and rolls back the import; `uq_listing_external_image` remains in place. Operator-verified account 2 re-import: discovered 311, processed 310, created 44, updated 266, failed 0; previously failing listing 31356894 imported successfully.

## Publication form and confirmation

The snapshot `YagaListingDraftData` contains Product clothing fields and source-listing delivery settings. Playwright fills only present clothing values when the selected category has the control, using exact Yaga labels: one size, one brand, all comma-separated colors and materials. The live Brand control is an `input[role=combobox][placeholder='Vali bränd']` beside `Bränd (valikuline)`. Color and Material multi-selects can remain open after selection; Playwright presses Escape, waits for the listbox to close, and rechecks selected values. Failed or unavailable selections stop preparation before publication. `SELECT_CLOTHING_FIELDS` and `FILL_PRICE` expose bounded safe diagnostics; price diagnostics identify the timed-out step, control state, requested/observed value, and overlay/listbox state without logging auth data or HTML.

Preparation observes Yaga `POST /api/product` for a successful draft ID/slug and successful image preparation (`POST /api/product/{draftId}/images`). Publication uses `PATCH /api/product/{draftId}`; image-order save is observed separately because the PATCH can succeed while that save fails. HTTP 200 or an unrecognized PATCH status alone never proves success. Captured draft identity can permit strict remote-detail verification, which checks identity, shop, published visibility, content, price, condition, category, and images. A newly published detail may briefly say `pending`; bounded polling (default 1 second, 10 seconds) waits for `published`. Unknown outcomes remain `RESULT_UNKNOWN`; the publish click is never retried automatically.

The old listing is hidden only after the replacement is confirmed. Hide preparation preserves target identity and avoids reloading an already correct page; an uncertain `Peida` click is never repeated. Operator-verified targeted ON_DEMAND refresh completed publication confirmation and old-listing hide. The same live smoke testing confirmed non-default delivery package sizes Omniva/DPD/SmartPosti = medium/medium/medium and clothing preservation.

## Recovery and reconciliation

```http
POST /api/yaga/refresh-runs/{runId}/jobs/{jobId}/reconcile-publication
POST /api/yaga/refresh-runs/{runId}/jobs/{jobId}/recover-hide-preparation
POST /api/yaga/refresh-runs/{runId}/jobs/{jobId}/reconcile-hide
POST /api/yaga/accounts/{accountId}/products/{productId}/reconcile-manual-listing
```

`reconcile-publication` takes `{"publicUrl":"https://www.yaga.ee/{shopSlug}/toode/{productSlug}"}` for an unknown publication outcome; it verifies an existing replacement, never republishes. If a job failed after confirmed publication while preparing hide, `recover-hide-preparation` can restore the guarded hide recovery state. `reconcile-hide` then verifies the replacement is still published and the old listing is remotely inactive/deleted before completing; it never clicks hide on an inactive listing. Do not weaken `RESULT_UNKNOWN` safeguards or bypass these state checks.

`reconcile-manual-listing` takes `{"sourceListingId":..., "manualExternalListingId":..., "publicUrl":"..."}`. It is for a manually published replacement after failed jobs. It re-fetches remote detail, verifies account/shop, published manual listing identity and product title/description/category, ordered image identity and links, and remote deletion of the source and recorded failed replacements. Price equality is not required because the manual listing may have been repriced. It atomically marks those local listings deleted/non-current and attaches the manual listing as the sole current published listing; failed jobs stay as history. Repeated calls are idempotent. It performs no Yaga mutation. Do not use it to attach a merely inactive but unverified-deleted listing.

## Auth, Drive, and safety

Yaga auth state is per account, with a sessionStorage sidecar (for example `playwright/.auth/yaga-account-2-state.json`). Normal ON_DEMAND uses persisted account auth. Interactive Chrome/CDP is only for setup/recovery: run existing `YagaAuthSetup`, then `YagaAuthCheck` if auth is invalid. Do not treat an old-listing deletion during hide preparation as an auth failure without checking remote state.

Publication images use archived `ProductImage.driveFileId` values and account-specific Drive folders; `YagaAccount.driveFolderId` routes them. Drive auth is OAuth. On `AUTO_GOOGLE_DRIVE_AUTH_INVALID_OR_EXPIRED`, stop the backend, refresh only the local token directory while retaining `client-secret.json`, restart with OAuth settings, and use `GET /api/drive/availability` to complete browser authorization. If Google still rejects the refresh token, revoke ResalePilot's third-party access and repeat. Keep client secrets and token directories outside commits.

Never log or commit cookies, tokens, auth-state, sessionStorage files, OAuth client secrets, credentials, or sensitive response bodies. Never retry an ambiguous publish/hide click, create another replacement for an unresolved published job, or hide before replacement confirmation. Keep account isolation, published-list discovery completeness, and oldest-first ordering.

## Migrations, validation, and handoff

Flyway migrations currently run through **V22**: V20 observes likes, V21 adds Product material/expands color, and V22 stores listing delivery settings with an all-unknown or complete-state constraint.

Important commits:

- `42451b46c1cb5d62419812046d73688b3ff2fad5` — clothing, delivery, targeted refresh, candidate/recovery, and form work.
- `ede8a9afc4e1588daf9ebef087e0c7ccb6a6f3a6` — duplicate-image import fix.

Latest full Maven suite after the image fix: **723 tests, 0 failures, 0 errors, 0 skipped**. For code changes, run targeted tests and then `mvn test` from `backend`. Start a live batch with a small ON_DEMAND count after checking import/reconciliation and auth. Do not run real Yaga mutations, change DB state, commit, or push in a Codex task unless the user authorizes it.
