# PenKhata — handover brief

PenKhata is the **single-shop Android till** in this repository. It keeps shipping.

A separate product, **cc_cred** (multi-outlet console + capture app + server), is designed but lives
outside this repo — its handover and design documents travel separately. Do not build the console here.

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

## 5. Constraints

- The user has **one test phone**, no laptop, and no budget for paid services. Anything requiring
  hardware, a PC build, or a subscription must be avoided or produced free via GitHub Actions.
- Customer names are personal data: never put them in filenames or shared text.
- The user tests every build on a real device and reports with screenshots. Keep replies short and
  always say what was verified and what was not.

## 6. Documents in this folder

