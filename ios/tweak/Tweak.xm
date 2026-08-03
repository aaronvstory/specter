// SpecterTweak — coherent device-signal spoofer for iOS (ElleKit/Substrate, rootless + RootHide).
//
// The tweak is DUMB on purpose: it reads one already-coherent profile plist (produced by
// ios/core/profile.py) and returns those values on every device-identity read path. All coherence
// lives in the generator; the tweak just enforces "one device, consistently."
//
// Scope: injected only into the bundles listed in SpecterTweak.plist (Choicy/Substrate Filter). A
// %ctor bundle-guard + a per-bundle profile file is the second gate — no profile => the tweak is inert.
//
// Coverage (v0.1): the reliably-portable read paths, each implemented fully —
//   - ObjC:  -[UIDevice identifierForVendor] / name / systemVersion
//   - C:     sysctlbyname, sysctl(MIB), uname
//   - MG:    MGCopyAnswer_internal (the SIGILL-safe internal-worker hook)
// TODO (v0.2, marked, not stubbed-silently): IORegistry (IOPlatformSerialNumber/UUID/MAC),
//   GSSystemGetSerialNo, statfs storage tiers, boot-time cache, IDFA. See docs/ios/DEEP-DIVE-FINDINGS.md.
//
// Design discipline (from the Android build): NEVER silently no-op. If a hook can't install
// (e.g. the MGCopyAnswer prologue doesn't match on this iOS build), log loudly so a leak self-reports
// instead of shipping green.

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <sys/sysctl.h>
#import <sys/syscall.h>
#import <sys/utsname.h>
#import <sys/time.h>
#import <dlfcn.h>
#import <ptrauth.h>
#import <substrate.h>

#ifndef SYS___sysctl
#define SYS___sysctl 202   // Darwin __sysctl syscall — read the real host build past our own sysctl hook
#endif

// ---- profile ----------------------------------------------------------------------------------
// Read from real rootfs (NOT under the randomized jbroot) so RootHide path randomization is a non-issue.
static NSDictionary *gProfile = nil;

static NSString *P(NSString *key) {           // profile string accessor
    id v = gProfile[key];
    return [v isKindOfClass:[NSString class]] ? v : nil;
}

static void SpecterLog(NSString *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    NSString *m = [[NSString alloc] initWithFormat:fmt arguments:ap];
    va_end(ap);
    NSLog(@"[SpecterTweak] %@", m);
}

// ---- ObjC identity ----------------------------------------------------------------------------
%hook UIDevice
- (NSUUID *)identifierForVendor {
    NSString *idfv = P(@"IDFV");
    if (idfv) { NSUUID *u = [[NSUUID alloc] initWithUUIDString:idfv]; if (u) return u; }
    return %orig;
}
- (NSString *)name {
    NSString *v = P(@"DeviceName");
    return v ? v : %orig;
}
- (NSString *)systemVersion {
    NSString *v = P(@"OSVersion");
    return v ? v : %orig;
}
%end

// systemUptime shifted by the same offset as kern.boottime above, so the two stay consistent.
%hook NSProcessInfo
- (NSTimeInterval)systemUptime {
    NSTimeInterval up = %orig;
    NSNumber *off = gProfile ? gProfile[@"BootOffsetSec"] : nil;
    if (off) up += off.doubleValue;
    return up;
}
%end

// ---- sysctlbyname (hw.machine, hw.model, hw.memsize, hw.ncpu, kern.osversion, kern.boottime) ---
// Bound every write by the CALLER's buffer capacity, captured BEFORE %orig (which overwrites *oldlenp
// with the real value's length — losing the caller's true capacity). For OWNED keys we synthesize the
// value and DON'T call %orig; calling %orig then overwriting made a spoofed value LONGER than the real
// one fail the standard two-call size idiom (the original CRITICAL bug).
static int writeBytes(void *oldp, size_t *oldlenp, size_t cap, const void *v, size_t n) {
    if (!oldp) { if (oldlenp) *oldlenp = n; return 0; }   // size-probe call
    if (cap < n) { errno = ENOMEM; return -1; }
    memcpy(oldp, v, n); if (oldlenp) *oldlenp = n; return 0;
}
static int writeStrCap(void *oldp, size_t *oldlenp, size_t cap, const char *s) {
    return writeBytes(oldp, oldlenp, cap, s, strlen(s) + 1);
}

%hookf(int, sysctlbyname, const char *name, void *oldp, size_t *oldlenp, void *newp, size_t newlen) {
    if (name && gProfile && !newp) {                       // reads only; never intercept a set
        size_t cap = oldlenp ? *oldlenp : 0;               // capacity BEFORE %orig can mutate *oldlenp
        if (!strcmp(name, "hw.machine")) { NSString *m = P(@"HWMachine"); if (m) return writeStrCap(oldp, oldlenp, cap, m.UTF8String); }
        if (!strcmp(name, "hw.model"))   { NSString *m = P(@"HWModel");   if (m) return writeStrCap(oldp, oldlenp, cap, m.UTF8String); }
        if (!strcmp(name, "kern.osversion")) { NSString *m = P(@"OSBuild"); if (m) return writeStrCap(oldp, oldlenp, cap, m.UTF8String); }
        if (!strcmp(name, "hw.memsize")) { NSNumber *n = gProfile[@"MemSize"]; if (n) { uint64_t v = n.unsignedLongLongValue; return writeBytes(oldp, oldlenp, cap, &v, sizeof(v)); } }
        if (!strcmp(name, "hw.ncpu") || !strcmp(name, "hw.activecpu") ||
            !strcmp(name, "hw.physicalcpu") || !strcmp(name, "hw.logicalcpu")) {
            NSNumber *n = gProfile[@"NCPU"]; if (n) { int32_t v = (int32_t)n.intValue; return writeBytes(oldp, oldlenp, cap, &v, sizeof(v)); }
        }
    }
    return %orig;
}

// ---- sysctl(MIB) — the same fields via the numeric path (a fingerprinter reads BOTH) --------
%hookf(int, sysctl, int *name, u_int namelen, void *oldp, size_t *oldlenp, void *newp, size_t newlen) {
    if (name && namelen >= 2 && gProfile && !newp) {
        size_t cap = oldlenp ? *oldlenp : 0;
        if (name[0] == CTL_HW) {
            if (name[1] == HW_MACHINE) { NSString *m = P(@"HWMachine"); if (m) return writeStrCap(oldp, oldlenp, cap, m.UTF8String); }
            if (name[1] == HW_MODEL)   { NSString *m = P(@"HWModel");   if (m) return writeStrCap(oldp, oldlenp, cap, m.UTF8String); }
            if (name[1] == HW_MEMSIZE) { NSNumber *n = gProfile[@"MemSize"]; if (n) { uint64_t v = n.unsignedLongLongValue; return writeBytes(oldp, oldlenp, cap, &v, sizeof(v)); } }
            if (name[1] == HW_NCPU)    { NSNumber *n = gProfile[@"NCPU"]; if (n) { int32_t v = (int32_t)n.intValue; return writeBytes(oldp, oldlenp, cap, &v, sizeof(v)); } }
        } else if (name[0] == CTL_KERN) {
            if (name[1] == KERN_OSVERSION) { NSString *m = P(@"OSBuild"); if (m) return writeStrCap(oldp, oldlenp, cap, m.UTF8String); }
            if (name[1] == KERN_BOOTTIME) {
                // Needs the REAL boot instant first, then shift earlier by the per-profile offset;
                // systemUptime adds the same offset so (now - boottime) stays consistent. Boot time is
                // otherwise identical across containers on one device — a strong cross-account linker.
                int r = %orig;
                NSNumber *off = gProfile[@"BootOffsetSec"];
                if (r == 0 && off && oldp && oldlenp && *oldlenp >= sizeof(struct timeval))
                    ((struct timeval *)oldp)->tv_sec -= (long)off.longLongValue;
                return r;
            }
        }
    }
    return %orig;
}

// ---- uname ------------------------------------------------------------------------------------
%hookf(int, uname, struct utsname *buf) {
    int r = %orig;
    if (r == 0 && buf && gProfile) { NSString *m = P(@"HWMachine"); if (m) strlcpy(buf->machine, m.UTF8String, sizeof(buf->machine)); }
    return r;
}

// ---- MobileGestalt: hook the SIGILL-safe INTERNAL worker ---------------------------------------
// The exported MGCopyAnswer is an 8-byte tail-call thunk (MOV X1,#0 ; B MGCopyAnswer_internal).
// Hooking the export SIGILLs (2-instr fn ending in a PC-relative B). Resolve the branch target and
// hook the internal worker instead. Prologue is version-fragile -> loud fallback, never silent no-op.
static CFTypeRef (*orig_MGCopyAnswer_internal)(CFStringRef key, uint32_t *outType);

static CFTypeRef my_MGCopyAnswer_internal(CFStringRef key, uint32_t *outType) {
    if (key && gProfile) {
        // MGKeys is keyed by BOTH plaintext and obfuscated-hash forms (built in profile.py), so this
        // matches however the app queries — plaintext ("ProductType") or hash ("h9jDsbgj7xIVeIQ8S3/X3Q").
        NSDictionary *mg = gProfile[@"MGKeys"];
        if ([mg isKindOfClass:[NSDictionary class]]) {
            NSString *v = mg[(__bridge NSString *)key];
            if (v) { if (outType) *outType = 2 /* CFString */; return CFBridgingRetain(v); }
        }
    }
    return orig_MGCopyAnswer_internal(key, outType);
}

// The exported MGCopyAnswer is a thunk (e.g. iOS 16.3.1/20D67: `mov x1,#0 ; b MGCopyAnswer_internal`).
// Resolve the internal worker by finding the first unconditional B and following it, then hook THAT.
// PROVEN on 20D67: entry+4 is `b` to the worker. Read instructions at the REAL entry (never re-align —
// masking the low bits shifts the decode and resolves garbage → the original crash).
// The MGCopyAnswer_internal prologue-resolve is validated per iOS BUILD. Gate the hook on the PHYSICAL
// host's build (read via the raw syscall so our own sysctl hook can't spoof it) — the per-profile
// EnableMGHook flag reflects the spoofed identity, not the device the dylib actually runs on. On an
// un-validated build we SKIP (loudly) rather than risk MSHookFunction patching a wrong in-range target.
static BOOL mgHostBuildValidated(void) {
    char b[64] = {0};
    int mib[2] = {CTL_KERN, KERN_OSVERSION};
    size_t sz = sizeof(b);
    int rc;
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    rc = syscall(SYS___sysctl, mib, 2, b, &sz, NULL, 0);   // raw syscall bypasses our own sysctl hook
#pragma clang diagnostic pop
    if (rc != 0) { SpecterLog(@"MG: could not read host build — SKIP"); return NO; }
    static const char *ok[] = { "20D67" };   // iOS 16.3.1 (SE2/iPhone8), on-device verified. Add as validated.
    for (size_t i = 0; i < sizeof(ok) / sizeof(ok[0]); i++) if (!strcmp(b, ok[i])) return YES;
    SpecterLog(@"MG: host build '%s' not in validated allowlist — MG hook SKIPPED (verify the prologue first)", b);
    return NO;
}

static void installMGHook(void) {
    void *sym = dlsym(RTLD_DEFAULT, "MGCopyAnswer");
    if (!sym) { SpecterLog(@"MG: MGCopyAnswer not found — MobileGestalt path NOT spoofed"); return; }
#if __arm64e__
    sym = ptrauth_strip(sym, ptrauth_key_function_pointer);   // PAC lives in the HIGH bits
#endif
    uint32_t *code = (uint32_t *)sym;
    for (int i = 0; i < 8; i++) {                             // skip a leading mov/bti, find the B
        uint32_t insn = code[i];
        if ((insn & 0xFC000000) == 0x14000000) {              // unconditional B
            int32_t imm = (int32_t)(insn << 6) >> 6;          // sign-extend imm26, *4
            void *internal = (void *)((uintptr_t)&code[i] + (intptr_t)imm * 4);
            intptr_t delta = (intptr_t)internal - (intptr_t)code;
            if (delta > 0x800000 || delta < -0x800000) {      // must land in the same __TEXT
                SpecterLog(@"MG: target %p out of range (delta %ld) — NOT hooking (FIX for this build)", internal, (long)delta);
                return;
            }
            MSHookFunction(internal, (void *)my_MGCopyAnswer_internal, (void **)&orig_MGCopyAnswer_internal);
            SpecterLog(@"MG: hooked MGCopyAnswer_internal at %p (export+%d instr)", internal, i);
            return;
        }
    }
    SpecterLog(@"MG: no branch in MGCopyAnswer prologue — build-specific, MobileGestalt NOT spoofed (FIX BEFORE TRUSTING)");
}

// ---- load / guard -----------------------------------------------------------------------------
%ctor {
    @autoreleasepool {
        // NOTE: never early-return here — Logos injects the %hook/%hookf registrations into this ctor,
        // so bailing early would skip installing them. Instead every hook self-gates on gProfile==nil
        // (returns %orig), so a process with no profile keeps the hooks installed but inert.
        NSString *bid = [[NSBundle mainBundle] bundleIdentifier];
        // Sandbox-safe: read the profile from the app's OWN container (always readable by the injected
        // dylib under the app sandbox). The Specter manager (root) drops it there per target app.
        NSString *local = [NSHomeDirectory() stringByAppendingPathComponent:@"Library/Specter/profile.plist"];
        gProfile = [NSDictionary dictionaryWithContentsOfFile:local];
        if (!gProfile && bid) {
            // Fallback: a central store — only works where the sandbox permits (e.g. via libSandy).
            NSString *central = [NSString stringWithFormat:@"/var/mobile/Library/Specter/%@.plist", bid];
            gProfile = [NSDictionary dictionaryWithContentsOfFile:central];
        }
        if (gProfile) {
            SpecterLog(@"loaded profile for %@ (%lu keys): %@ / %@",
                       bid, (unsigned long)gProfile.count, P(@"ProductType"), P(@"OSVersion"));
            // The MobileGestalt internal-worker hook memory-patches a scanned address and is
            // version-fragile (can crash if the prologue differs on this iOS build). Opt-in only,
            // per profile, once validated for the target build — never crash an app by default.
            if ([gProfile[@"EnableMGHook"] boolValue] && mgHostBuildValidated()) installMGHook();
        }
    }
}
