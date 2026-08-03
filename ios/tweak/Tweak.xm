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
#import <sys/utsname.h>
#import <sys/time.h>
#import <dlfcn.h>
#import <ptrauth.h>
#import <substrate.h>

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
static int writeStr(void *oldp, size_t *oldlenp, const char *s) {
    size_t need = strlen(s) + 1;
    if (!oldp) { if (oldlenp) *oldlenp = need; return 0; }
    if (oldlenp && *oldlenp < need) { errno = ENOMEM; return -1; }
    memcpy(oldp, s, need); if (oldlenp) *oldlenp = need; return 0;
}
static int writeU64(void *oldp, size_t *oldlenp, uint64_t v) {
    if (!oldp) { if (oldlenp) *oldlenp = sizeof(v); return 0; }
    memcpy(oldp, &v, sizeof(v)); if (oldlenp) *oldlenp = sizeof(v); return 0;
}

%hookf(int, sysctlbyname, const char *name, void *oldp, size_t *oldlenp, void *newp, size_t newlen) {
    int r = %orig;
    if (r != 0 || !name || !gProfile) return r;
    if (!strcmp(name, "hw.machine")) { NSString *m = P(@"HWMachine"); if (m) return writeStr(oldp, oldlenp, m.UTF8String); }
    else if (!strcmp(name, "hw.model")) { NSString *m = P(@"HWModel"); if (m) return writeStr(oldp, oldlenp, m.UTF8String); }
    else if (!strcmp(name, "hw.memsize")) { NSNumber *n = gProfile[@"MemSize"]; if (n) return writeU64(oldp, oldlenp, n.unsignedLongLongValue); }
    else if (!strcmp(name, "kern.osversion")) { NSString *m = P(@"OSBuild"); if (m) return writeStr(oldp, oldlenp, m.UTF8String); }
    return r;
}

// ---- sysctl(MIB) — the same fields via the numeric path (a fingerprinter reads BOTH) --------
%hookf(int, sysctl, int *name, u_int namelen, void *oldp, size_t *oldlenp, void *newp, size_t newlen) {
    int r = %orig;
    if (r != 0 || !name || namelen < 2 || !gProfile) return r;
    if (name[0] == CTL_HW) {
        if (name[1] == HW_MACHINE) { NSString *m = P(@"HWMachine"); if (m) return writeStr(oldp, oldlenp, m.UTF8String); }
        if (name[1] == HW_MODEL)   { NSString *m = P(@"HWModel");   if (m) return writeStr(oldp, oldlenp, m.UTF8String); }
        if (name[1] == HW_MEMSIZE) { NSNumber *n = gProfile[@"MemSize"]; if (n) return writeU64(oldp, oldlenp, n.unsignedLongLongValue); }
    } else if (name[0] == CTL_KERN && name[1] == KERN_OSVERSION) {
        NSString *m = P(@"OSBuild"); if (m) return writeStr(oldp, oldlenp, m.UTF8String);
    } else if (name[0] == CTL_KERN && name[1] == KERN_BOOTTIME) {
        // Shift the boot instant earlier by the per-profile offset. systemUptime is shifted by the same
        // amount below, so (now - boottime) stays consistent. kern.boottime is otherwise identical across
        // containers on one device — a strong cross-account linker.
        NSNumber *off = gProfile[@"BootOffsetSec"];
        if (off && oldp && oldlenp && *oldlenp >= sizeof(struct timeval)) {
            ((struct timeval *)oldp)->tv_sec -= (long)off.longLongValue;
        }
    }
    return r;
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
            if ([gProfile[@"EnableMGHook"] boolValue]) installMGHook();
        }
    }
}
