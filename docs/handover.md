# PenKhata — handover brief

Everything a fresh session needs to continue this work without the chat history.
Written 26 Nov 2026. Read this first, then `docs/system-design.md` and `docs/system-design.pdf`.

---

## 1. What exists today

**Repository**: `swamendu/penkhata` (public, default branch `main`).
**App**: single-module Android app, one Kotlin file `app/src/main/java/com/example/penkhata/MainActivity.kt`
(~3,500 lines), Compose, SQLite, fully offline.
**Current version**: 8.2 (versionCode 71). APKs are built by GitHub Actions
(`.github/workflows/android.yml`) and delivered as the `penkhata-apk` artifact.

### What the app already does
- GST invoice with seller/buyer details, IGST vs CGST/SGST, discounts (÷ by (1+disc) or % off — a setting),
  round-off, payment split, exchange, finance field
- Invoice numbering per outlet and series; firm prefix fallback when the outlet code is `MAIN`
- Files: bills and quotes, search, share, render-on-demand PDFs, cancel with stock return
- Customers: master keyed by phone, name locked to the registered owner, search-as-you-type
- Stock: master data (brands, categories, distributors with coverage), models, SKUs, GRN with
  pending serials, purchase entry, stock in hand with three valuations and ageing, stock audit
- Reports: month summary, rate-wise, B2B/B2C, brand-wise, ledger with ageing, Installation Register XLSX
- Backup: zip with JSON + XLSX, optional AES-256-GCM password, snapshot before restore, restore with repair
- Roles: OWNER / MANAGER / STOCK / REP with a PIN gate; FLAG_SECURE for non-owners
- Settings: catalogued into Business / Team / Security / Backup tabs

### Known open defects (fix these before building anything new)
1. **SKU does not save.** The Models tab shows `SKUs in the database: 0` after adding one. The toast
   now reports the error (7.8.1+); the next step is to read that message. Suspect the `modelId` column
   on a database created by an older build.
2. **Stock is not branch-scoped.** Every stock row belongs to the whole app, not to an outlet.
3. **Roles are labels, not identities.** One shared PIN; no per-person login.
4. **The console does not exist yet.** The app is a standalone till.

---

## 2. The agreed design (see `docs/system-design.md` for the full text)

**The rule**: a brand sales rep *captures* a sale on the Android app; the outlet manager *bills* it on
the console. Only the counter issues invoice numbers, so numbering can never collide.

- **Console** — responsive web app (PWA), used by owner, manager, outlet manager, duty manager and
  stock auditor. Bills, takes payment, closes the day, runs audits.
- **Android app** — brand sales reps only. Capture, IMEI reservation, delivery challan, cash custody,
  sync. No invoicing, no numbers.
- **Server** — identity, roles, outlets, events, numbering, sync, audit trail.

Key sub-designs already settled: three dates (challan / delivery / invoice), request lifecycle with no
expiry but ageing, cash custody chain, six stock states, scheme splits with a claim ledger, approval
hierarchy, duty manager expiry.

---

## 3. The build plan — brick by brick

| # | Brick | Gate |
|---|---|---|
| 0.1 | Entity schema (document) | User reads and corrects it |
| 0.2 | Event catalogue | — |
| 0.3 | Metric definitions | Numbers agreed before code |
| 0.4 | Permission matrix | — |
| 0.5 | Sync contract | — |
| 0.6 | Screen inventory | — |
| 1 | Server skeleton + seed data | Numbers verified |
| 2 | Console shell, login, role landing pages | Roles differ correctly |
| 3 | Owner dashboard (read-only) | Matches a spreadsheet to the rupee |
| 4 | **Queue + billing — the heart** | A captured sale billed in under a minute |
| 5 | Cash custody + day-close | Handover and deposit move the numbers |
| 6 | Stock workspace + audit | A shelf counted, variance approved |
| 7 | Schemes + claim ledger | ₹2,200 becomes a receivable |
| 8 | Android app rebuilt thin | Capture on phone, bill on console |
| 9 | Offline and sync hardening | Internet pulled mid-bill |
| 10 | Reports and exports | — |
| 11 | Rollout outlet by outlet | — |

**Next action on a fresh session**: write brick 0.1, the entity schema, as a document — not code.

---

## 4. Open questions blocking the spec

1. The discount limit value (only the owner sets it).
2. Defective stock: reduce value the day it fails, or when the brand credits?
3. May a Samsung rep bill a vivo phone at all?
4. Is the claim deadline separate from the scheme validity window?
5. May an outlet manager approve a scheme, or only a manager above / the owner?

---

## 5. How to work on this repo (learned the hard way)

- **One iteration at a time.** Branch, PR, CI, one APK, wait for the user to test on a real phone.
- **Merge before branching.** Several iterations were branched from `main` while a PR was still open,
  which silently shipped the wrong base. Always `gh pr list` first.
- **Verify a patch actually landed.** More than once a Python patch script exited mid-way and the
  change was never written, while the build still went green. Grep for the change before building.
- **Never ship a modal without an exit.** A SharedPreferences write does not recompose; the answer must
  live in state or the dialog never closes and the app is unusable.
- **`open` is a Kotlin keyword.** Using it as a variable name breaks the parser and the compiler.
- **A coroutine's body is not covered by the `try` around `launch`.** Wrap the body itself.
- **`db(ctx)` must be a singleton** — one helper per process, WAL on, `busy_timeout` set. Multiple
  connections caused `SQLITE_BUSY` during restore.
- **Schema changes must go into `onCreate` as well as `onUpgrade`**, or a fresh install lacks the columns
  and reads fail silently.
- **Crash log**: Settings → Security → Last error. It captures stack traces on the device, which is how
  the SQLite bug was found without a PC.
- **Emulator harness**: `.github/workflows/emulator.yml` + `emu*.sh` install the app in a GitHub-hosted
  Android emulator and upload screenshots. It can launch and screenshot reliably; scripted taps are
  unreliable, so treat it as a visual check, not a test suite.
- **Logic harness**: mirror rules in Python (see `grn_logic_test.py` pattern) before trusting a build.

## 6. Constraints

- The user has **one test phone**, no laptop, and no budget for paid services. Anything requiring
  hardware, a PC build, or a subscription must be avoided or produced free via GitHub Actions.
- Customer names are personal data: never put them in filenames or shared text.
- The user tests every build on a real device and reports with screenshots. Keep replies short and
  always say what was verified and what was not.

## 7. Documents in this folder

- `system-design.md` — the agreed design in text
- `system-design.pdf` — the same with three flowcharts, plus schemes
- `PenKhata-System-Design.html` — a 14-slide deck (delivered separately, not in the repo)
