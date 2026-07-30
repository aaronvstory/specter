package com.specter.module.gen;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The one-tap "Set up everything" orchestrator for a virgin-phone install. Runs each install step in order and
 * reports a per-step result, so the UI shows a live checklist instead of one opaque spinner. Every step is
 * best-effort and independent — a failure in one (e.g. no oemcrypto to force L3 on this device) doesn't abort
 * the rest; the step just reports NOT-done with its reason. After this returns, the caller prompts a reboot
 * (LSPosed reloads scope, Zygisk loads the native layer, the Magisk overlays mount — all on boot).
 *
 * <p>What it installs (all of it — these are fleet devices):
 * <ul>
 *   <li>{@link ZygiskInstaller} native layer</li>
 *   <li>{@link LspScope} scope rows for the target apps (+ the probe/DevInfo defaults it needs)</li>
 *   <li>{@link OtaBlock} — keep the device on its current OS version</li>
 *   <li>{@link WidevineL3} — software DRM, device-wide</li>
 * </ul>
 * All of it is pure orchestration over already-tested installers; the only new logic here is the ordering +
 * result aggregation, which {@link #run} exposes on an injectable shell for tests.
 */
public final class SetupFlow {
    private SetupFlow() {}

    /** Process-wide in-flight guard. The UI's instance-local setupBusy flag can be reset by Activity recreation
     *  (rotation, process rebuild) while an old worker thread is still running — a second run would then race on
     *  the shared lspd_rw.db / Magisk staging dirs. This static latch makes two concurrent runs impossible
     *  regardless of Activity lifecycle. */
    private static final java.util.concurrent.atomic.AtomicBoolean IN_FLIGHT =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static final class BusyException extends RuntimeException {
        public BusyException(String m) { super(m); }
    }

    /** One step's outcome. done=true means it succeeded (or was already in place); detail is user-facing. */
    public static final class StepResult {
        public final String label;
        public final boolean done;
        public final String detail;
        public StepResult(String label, boolean done, String detail) {
            this.label = label; this.done = done; this.detail = detail;
        }
    }

    /** Whether the whole run needs a reboot to take effect (true if any step that requires one succeeded). */
    public static final class Outcome {
        public final List<StepResult> steps;
        public final boolean anySucceeded;
        /** The REQUIRED steps (native layer + LSPosed scope incl. framework) all succeeded — the gate for
         *  marking setup complete. OTA/Widevine are optional and don't count. */
        public boolean requiredOk;
        Outcome(List<StepResult> steps, boolean anySucceeded) {
            this.steps = steps; this.anySucceeded = anySucceeded;
        }
    }

    /** Run every setup step. Blocking (multiple su round-trips) — call OFF the UI thread. Never throws; each
     *  step's exception is caught and turned into a failed StepResult so the checklist always renders. */
    public static Outcome run(Context ctx, Collection<String> targets, boolean enableWidevine) {
        return run(ctx, targets, enableWidevine, new RootWriter.SuShell());
    }

    public static Outcome run(Context ctx, Collection<String> targets, boolean enableWidevine, RootWriter.Shell shell) {
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            throw new BusyException("A setup run is already in progress.");
        }
        try {
            return runLocked(ctx, targets, enableWidevine, shell);
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    private static Outcome runLocked(Context ctx, Collection<String> targets, boolean enableWidevine, RootWriter.Shell shell) {
        List<StepResult> steps = new ArrayList<>();
        boolean any = false;
        boolean nativeOk = false, scopeOk = false;   // the two REQUIRED steps

        // 1. Native layer — the deepest coverage; install (or refresh) the bundled .so.
        try {
            ZygiskInstaller.install(ctx, shell);
            steps.add(new StepResult("Native layer", true, "Installed — activates on reboot."));
            any = true; nativeOk = true;
        } catch (Throwable t) {
            steps.add(new StepResult("Native layer", false, msg(t, "Install failed — is root granted?")));
        }

        // 2. LSPosed scope: the picked targets PLUS the System Framework gate (android/system), so the
        //    raw-binder app-hiding gate actually installs — a one-click setup that scoped only the user apps
        //    left the framework gate off (codex-flagged). A bad/empty set still runs (no-op).
        try {
            java.util.LinkedHashSet<String> scope = new java.util.LinkedHashSet<>(targets);
            scope.add("android");
            scope.add("system");
            LspScope.Result r = LspScope.addTargets(ctx, scope, shell);
            String d = r.added > 0
                    ? "Added " + r.added + " entr" + (r.added == 1 ? "y" : "ies") + " to scope (incl. System "
                      + "Framework) — reboot to apply."
                    : "Already scoped (incl. System Framework).";
            steps.add(new StepResult("App scope", true, d));
            if (r.added > 0) any = true;
            scopeOk = true;   // scope write succeeded (module enabled + rows present/added)
        } catch (Throwable t) {
            steps.add(new StepResult("App scope", false, msg(t, "Couldn't write scope — enable Specter in LSPosed first.")));
        }

        // 3. OTA block — keep the device on its current OS version.
        try {
            OtaBlock.install(shell);
            steps.add(new StepResult("OTA block", true, "Updates blocked — reboot to finish."));
            any = true;
        } catch (Throwable t) {
            steps.add(new StepResult("OTA block", false, msg(t, "Couldn't install the OTA block.")));
        }

        // 4. Widevine L3 — software DRM device-wide. A device with no oemcrypto to shadow reports not-done
        //    (WidevineL3.install exits 3 there); that's fine, it just means nothing to force. Skipped
        //    entirely when the user's Widevine setting is off (default ON — see MainActivity onCreate).
        if (enableWidevine) {
            try {
                WidevineL3.install(shell);
                steps.add(new StepResult("Widevine L3", true, "Set to software DRM — reboot to finish."));
                any = true;
            } catch (Throwable t) {
                steps.add(new StepResult("Widevine L3", false, msg(t, "Couldn't set Widevine L3 on this device.")));
            }
        } else {
            steps.add(new StepResult("Widevine L3", true, "Skipped by user setting"));
        }

        Outcome o = new Outcome(steps, any);
        o.requiredOk = nativeOk && scopeOk;   // only the required steps gate "setup complete"
        return o;
    }

    private static String msg(Throwable t, String fallback) {
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? fallback : m;
    }
}
