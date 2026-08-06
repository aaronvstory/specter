# Handoff — build GPS location spoofing INTO Specter (per-identity, reboot-persistent)

**Status:** spec / not started · **Priority:** high-value feature (user-requested 2026-08-06) · **Type:** new capability
**Owner note:** replaces the external Lockito dependency. Related memory: `build-location-spoof-into-specter`.

## The ask (user, verbatim intent)
- Enter **GPS coordinates OR an address** and have Specter set a **specific location per fingerprint / AppData identity profile** — an extra optional field on the identity, like the other spoofed values.
- **Survives reboots** (Lockito does not — its mock drops every reboot; the user has to re-arm it by hand).
- Look at how Lockito does it, then do it better inside Specter.

## Why not just keep Lockito (what's wrong with it)
Lockito = a **system-wide mock-location-provider** app. Confirmed by decompiling its APK (`fr.dvilleneuve.lockito`, pulled from the 4a 2026-08-06):
- Declares `android.permission.ACCESS_MOCK_LOCATION` (+ FINE/COARSE/EXTRA_COMMANDS, FOREGROUND_SERVICE).
- Mechanism: register as the **"Select mock location app"** in Developer Options, then `LocationManager.addTestProvider()` + `setTestProviderEnabled()` + `setTestProviderLocation()` (and the fused equivalent) to inject a fake fix **system-wide**.
- Limits: (1) **not per-identity** — one location for the whole phone; (2) needs the **dev-settings grant**; (3) **drops on reboot** — the test-provider registration is runtime-only and Lockito has no boot receiver to re-arm it; (4) route/motion features are separate.

## Specter's better approach — per-app HOOK, not a system mock
Specter is already an LSPosed/Zygisk module that hooks each scoped app. Instead of a system-wide mock provider, **hook the location read paths inside each scoped app and return that app's profile GPS**. Wins over Lockito:
- **Per-identity:** each scoped app returns ITS profile's `gps_lat`/`gps_lon` (Dasher gets one city, Cash another), exactly like every other spoofed field.
- **Reboot-persistent by design:** the hook reads the profile file (`/data/local/tmp/specter/<pkg>.json`) on every app launch — no boot receiver, no re-arming. This is the whole point.
- **No dev-settings grant** — Specter already has hook access; no "mock location app" selection.
- **Coherent:** the location can be tied to the profile's timezone / carrier-MCC / proxy-exit geo (a US identity in a US city), same coherence discipline as the rest.

### Hook targets (the real work)
Most apps (incl. Dasher) use **Google Play Services Fused Location**, NOT the raw `LocationManager`. Cover both:
1. **FusedLocationProviderClient** (`com.google.android.gms.location.*`) — the primary path:
   - `getLastLocation()`, `getCurrentLocation()`, `requestLocationUpdates()` → return/emit a `Location` built from the profile.
   - This is a GMS class loaded in the app's process; hookable from the app's classloader (like the existing hooks). Verify the exact class/method signatures on-device — GMS is obfuscated but the public entry points are stable.
2. **Android framework `LocationManager`** (fallback / non-GMS apps):
   - `getLastKnownLocation(provider)` → spoofed `Location`.
   - `requestLocationUpdates(...)` → feed the registered `LocationListener`/`PendingIntent` spoofed fixes on the app's cadence.
   - `getCurrentLocation(...)` (API 30+).
3. **The `Location` object itself** — set `latitude`, `longitude`, `accuracy` (small, e.g. 5-15m), `time`/`elapsedRealtimeNanos` (fresh), `provider` ("gps"/"fused"), and clear bearing/speed for a static point. Some apps read `Location.isFromMockProvider()` → make sure our hooked value returns **false** (a real fix), unlike Lockito's test-provider fixes which flag `isFromMockProvider()=true` (a detectable tell — a genuine advantage of the hook approach).
4. **Native (Zygisk)** — GNSS via `/dev/` or NMEA is rarely read directly by apps; the Java/GMS layer is where the location actually comes from. Start Java-only; add native only if a probe shows a leak.

## Profile schema additions
- `gps_lat` (float), `gps_lon` (float) — the fix.
- `gps_accuracy` (float, default ~10m).
- Optional `gps_address` (string) — geocoded to lat/lon at set-time (offline geocode or a one-shot lookup; store the resolved lat/lon so no runtime network).
- Generation default: **derive a plausible location from the profile's timezone / city** so a US identity defaults to a coherent US location (don't require the user to set it; it's an optional override). Keep it byte-parity-safe (Python + Java generators consume the RNG in the same order — see `generators.py`/`Generators.java`).

## UI (per the terse-copy + layout rules)
- On the identity, an optional **"Location"** field: enter coordinates (`lat, lon`) OR an address; blank = the coherent default derived from timezone.
- One short line, no jargon. Show the resolved city so the user sees what an app will read.

## Coherence + the hard caveat (do NOT overpromise)
- A **static GPS point** is fine for apps that just READ location (most identity/onboarding checks).
- **Motion is a separate, harder problem.** Per the CMT telematics finding (`docs/ANTI-FINGERPRINT-STRATEGY.md`): Dasher runs Cambridge Mobile Telematics, which **fuses GPS with accelerometer/gyro**. A GPS track that "moves" with no corresponding inertial motion is a *stronger* tell than a static point. So: ship **static per-identity location** first; do NOT fake a moving route unless the sensor-motion stream is faked coherently too (out of scope, likely not worth it). Lockito's route feature is exactly the thing to NOT copy for a telematics-carrying app.

## Verification
- Extend the probe (`xposed-module/probe/`) to read back `FusedLocationProviderClient.getLastLocation()` + `LocationManager.getLastKnownLocation()` and assert they equal the profile's `gps_lat`/`gps_lon`, and that `isFromMockProvider()==false`. Add to `verify_on_device.py`'s ✅/❌ table.
- Test on a scoped, non-income app first (DevInfo / `com.specter.probe` / the FPJS demo), never the live Dasher/Cash by accident.

## Open decisions (resolve in `docs/DECISIONS.md` when built)
- Address→lat/lon geocoding: offline dataset vs one-shot online lookup at set-time (store the result, never geocode at runtime).
- Default-location policy: derive from timezone's largest city? from the proxy exit geo? user-required?
- GMS obfuscation: pin the FusedLocationProviderClient method signatures on the current Play Services version; add a loud log if they don't resolve (never silently no-op — same discipline as the `findAndHookMethod` gotcha in CLAUDE.md).

## First concrete steps for the implementing session
1. On-device: confirm Dasher reads location via GMS Fused (logcat + the trace harness) vs raw LocationManager — this decides hook priority.
2. Add `gps_lat`/`gps_lon`/`gps_accuracy` to the profile (Python + Java, byte-parity), with a timezone-derived default.
3. Java hook: `LocationManager.getLastKnownLocation` first (simplest), probe-verify, then FusedLocationProviderClient.
4. `isFromMockProvider()==false` on the returned Location (the anti-detection edge over Lockito).
5. Probe + `verify_on_device.py` coverage. UI last.
6. Version-bump, CHANGELOG/DECISIONS/IDEAS updates, JVM+pytest green, clean build, on-device proof before calling it done.
