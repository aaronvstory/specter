# Tracked follow-ups (from reviews)

Coherence/robustness improvements surfaced by codex-2 that are real but lower-priority than the
ban-critical fixes already applied. Tracked here so they're not lost.

## Coherence (identity realism)
- [DONE] **IMEI TAC ↔ device model** — IMEIs currently use a random 14-digit body (Luhn-valid) unrelated to
  the selected device. Real IMEIs start with a model-specific TAC (Type Allocation Code), and a
  dual-SIM device's two IMEIs usually share the TAC. To fully match, the device DB (`data/devices.json`)
  would need per-model TAC(s), and `generators.imei` would build TAC + serial + Luhn. *Fraud SDKs that
  validate TAC-against-model could flag the current random TAC.* (Bigger lift — needs a TAC dataset.)
- **Serial OEM-shape** — `serial` is a fixed 16-char uppercase hex. Real serials vary by OEM
  (length, mixed alnum) and some are permission-gated to `UNKNOWN`. Consider per-model serial patterns.
- **Phone number E.164** — `mobile_number` is a random NANP number with no `+1`/E.164 form and no
  assignment check. Some apps expect `+1…` or a null Line1. Consider assigned test ranges or nullable.

## Hook coverage (defense in depth)
- **Cursor value accessors** — `GsfCursorWrapper` overrides `getString`/`getLong`. A caller using
  `copyStringToBuffer`, `getBlob`, or copying the cursor into a `MatrixCursor` before reading could
  still see the real value. Override the remaining accessors for the android_id value column.
- **`ContentProviderClient.query`** — the provider hook covers `ContentResolver.query`; a caller using
  `ContentProviderClient.query` for the gservices authority would bypass it. Hook that overload too.

## Done (for reference)
Already fixed: fail-closed ledger, Build.VERSION.*, Gservices.getLong, concurrent-duplicate race,
ICCID carrier coherence, GSF Long overflow, advertising-id v4, IMEI slot handling, GSF provider hook,
pkg injection validation.

## CI note
The GitHub Actions workflow (`.github/workflows/ci.yml`) is valid but currently returns
`startup_failure` with no logs on this private repo — the signature of exhausted Actions
minutes on the account's free private-repo tier (Actions is enabled; YAML validates). The
suite is the source of truth locally (`uv run … pytest`) and the PR review bots run on every
push. CI will execute once minutes reset or the repo is made public.
