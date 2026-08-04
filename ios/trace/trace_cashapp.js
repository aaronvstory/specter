'use strict';
// Specter-iOS device-read tracer. Hooks the signal read-paths a fingerprinting SDK uses,
// records key/args/return + a short module backtrace, streams to the Python driver as JSON.

const SEEN = {};        // dedupe key -> count, so a chatty poll doesn't flood
function emit(o) {
  o.t = Date.now();
  send(o);
}
function bt(ctx) {
  try {
    return Thread.backtrace(ctx, Backtracer.FUZZY)
      .slice(0, 6)
      .map(a => { const m = Process.findModuleByAddress(a); return m ? m.name : a.toString(); })
      .filter((v, i, s) => s.indexOf(v) === i);   // unique module names, order-preserving
  } catch (e) { return []; }
}
function note(kind, key, val, ctx) {
  const dk = kind + ':' + key + '=' + String(val);
  SEEN[dk] = (SEEN[dk] || 0) + 1;
  if (SEEN[dk] > 3) return;                        // first 3 of each distinct (key,value)
  emit({ kind, key, value: val === undefined ? null : String(val), n: SEEN[dk], modules: bt(ctx) });
}

// ---- 1. sysctlbyname(name, ...) — hw.machine, hw.model, hw.memsize, kern.*, boottime ----
['sysctlbyname'].forEach(sym => {
  const p = Module.findExportByName(null, sym);
  if (!p) return;
  // sysctlbyname(name, oldp, oldlenp, newp, newlen): value is oldp=a[1], size is a[2]
  Interceptor.attach(p, {
    onEnter(a) { this.name = a[0].readUtf8String(); this.oldp = a[1]; this.oldlenp = a[2]; },
    onLeave(r) {
      let out = null;
      try {
        if (this.oldp && !this.oldp.isNull()) {
          let len = 0; try { len = this.oldlenp.readU64().valueOf(); } catch (e) {}
          const s = this.oldp.readCString();
          if (s && /^[\x20-\x7e]+$/.test(s)) out = s;          // printable string (hw.machine, kern.version)
          else if (len === 8) out = this.oldp.readU64().toString();
          else if (len === 4) out = this.oldp.readS32().toString();
          else if (len) out = this.oldp.readByteArray(Math.min(len, 32)) ? '(bytes:' + len + ')' : null;
        }
      } catch (e) {}
      note('sysctlbyname', this.name, out, this.context);
    }
  });
});

// ---- 2. sysctl(mib[], namelen, ...) — CTL_HW/HW_MACHINE, KERN_BOOTTIME, etc. ----
{
  const p = Module.findExportByName(null, 'sysctl');
  if (p) Interceptor.attach(p, {
    onEnter(a) {
      const namelen = a[1].toInt32();
      const mib = [];
      for (let i = 0; i < Math.min(namelen, 6); i++) mib.push(a[0].add(i * 4).readS32());
      this.mib = mib.join('.'); this.oldp = a[2];
    },
    onLeave(r) { note('sysctl', 'mib[' + this.mib + ']', this.oldp && !this.oldp.isNull() ? '(buf)' : null, this.context); }
  });
}

// ---- 3. MobileGestalt: MGCopyAnswer (+ internal). Hook the exported one's callers safely. ----
['MGCopyAnswer', 'MGCopyAnswerWithError', 'MGGetBoolAnswer'].forEach(sym => {
  const p = Module.findExportByName('libMobileGestalt.dylib', sym) || Module.findExportByName(null, sym);
  if (!p) return;
  try {
    Interceptor.attach(p, {
      onEnter(a) { try { this.key = new ObjC.Object(a[0]).toString(); } catch (e) { try { this.key = a[0].readUtf8String(); } catch (e2) { this.key = '?'; } } },
      onLeave(r) {
        let v = null;
        try { if (r && !r.isNull()) v = new ObjC.Object(r).toString(); } catch (e) {}
        note(sym, this.key, v, this.context);
      }
    });
  } catch (e) { emit({ kind: 'hook-error', key: sym, value: String(e) }); }
});

// ---- 4. IOKit registry property reads (IOPlatformSerialNumber, model, board-id, MAC) ----
['IORegistryEntryCreateCFProperty', 'IORegistryEntrySearchCFProperty'].forEach(sym => {
  const p = Module.findExportByName('IOKit', sym) || Module.findExportByName(null, sym);
  if (!p) return;
  Interceptor.attach(p, {
    onEnter(a) { try { this.key = new ObjC.Object(a[1]).toString(); } catch (e) { this.key = '?'; } },
    onLeave(r) { let v = null; try { if (r && !r.isNull()) v = new ObjC.Object(r).toString(); } catch (e) {} note('IORegistry', this.key, v, this.context); }
  });
});

// ---- 5. statfs/statvfs — disk size/free ----
['statfs', 'statfs64', 'statvfs'].forEach(sym => {
  const p = Module.findExportByName(null, sym);
  if (!p) return;
  Interceptor.attach(p, { onEnter(a) { try { note(sym, a[0].readUtf8String(), '(fs)', this.context); } catch (e) {} } });
});

// ---- 6. uname ----
{
  const p = Module.findExportByName(null, 'uname');
  if (p) Interceptor.attach(p, { onLeave(r) { note('uname', 'utsname', '(struct)', this.context); } });
}

// ---- 7. ObjC identity: UIDevice, IDFV/IDFA, keychain, ProcessInfo, CoreTelephony, TimeZone/Locale ----
if (ObjC.available) {
  function hookSel(cls, sel, label) {
    try {
      const m = ObjC.classes[cls] && ObjC.classes[cls][sel];
      if (!m) return;
      Interceptor.attach(m.implementation, {
        onLeave(r) {
          let v = null;
          try { if (r && !r.isNull()) v = new ObjC.Object(r).toString(); } catch (e) {}
          note('objc', label, v, this.context);
        }
      });
    } catch (e) {}
  }
  hookSel('UIDevice', '- systemVersion', 'UIDevice.systemVersion');
  hookSel('UIDevice', '- systemName', 'UIDevice.systemName');
  hookSel('UIDevice', '- model', 'UIDevice.model');
  hookSel('UIDevice', '- name', 'UIDevice.name');
  hookSel('UIDevice', '- identifierForVendor', 'UIDevice.identifierForVendor');
  hookSel('ASIdentifierManager', '- advertisingIdentifier', 'IDFA');
  hookSel('NSProcessInfo', '- operatingSystemVersionString', 'ProcessInfo.osVersionString');
  hookSel('NSProcessInfo', '- physicalMemory', 'ProcessInfo.physicalMemory');
  hookSel('NSProcessInfo', '- systemUptime', 'ProcessInfo.systemUptime');
  hookSel('NSProcessInfo', '- hostName', 'ProcessInfo.hostName');
  hookSel('NSTimeZone', '- name', 'TimeZone.name');
  hookSel('CTCarrier', '- mobileCountryCode', 'CTCarrier.mcc');
  hookSel('CTCarrier', '- mobileNetworkCode', 'CTCarrier.mnc');
  hookSel('CTCarrier', '- carrierName', 'CTCarrier.name');
  // ---- iCloud / Apple-ID linkers (stable per-Apple-ID, survive app reinstall, cross-account tell) ----
  hookSel('NSFileManager', '- ubiquityIdentityToken', 'iCloud.ubiquityIdentityToken');
  hookSel('CKContainer', '- fetchUserRecordIDWithCompletionHandler:', 'CloudKit.fetchUserRecordID');
  hookSel('CKContainer', '- accountStatusWithCompletionHandler:', 'CloudKit.accountStatus');
  hookSel('NSUbiquitousKeyValueStore', '- objectForKey:', 'iCloudKVS.read');
  hookSel('UIPasteboard', '- string', 'Pasteboard.string');   // cross-app clipboard linker
  hookSel('LAContext', '- biometryType', 'biometryType');
  // App Attest / DeviceCheck — hook the actual CALLS (not just class presence) to prove enforcement
  ['DCDevice', 'DCAppAttestService'].forEach(cls => {
    ['- generateTokenWithCompletionHandler:', '- generateKeyWithCompletionHandler:',
     '- attestKey:clientDataHash:completionHandler:', '- generateAssertion:clientDataHash:completionHandler:',
     '- isSupported'].forEach(sel => hookSel(cls, sel, 'ATTEST ' + cls + ' ' + sel));
  });
  // keychain (IDFV persistence anchor) + attestation
  ['SecItemCopyMatching', 'SecItemAdd'].forEach(sym => {
    const p = Module.findExportByName(null, sym);
    if (p) Interceptor.attach(p, { onEnter(a) { note('keychain', sym, '(query)', this.context); } });
  });
  // App Attest / DeviceCheck presence — the ceiling. If these fire, the app gates on attestation.
  ['DCDevice', 'DCAppAttestService'].forEach(cls => {
    if (ObjC.classes[cls]) {
      const own = ObjC.classes[cls].$ownMethods || [];
      own.forEach(sel => hookSel(cls, sel, cls + ' ' + sel));
      emit({ kind: 'attestation-class-present', key: cls, value: 'YES' });
    }
  });
}

emit({ kind: 'tracer-ready', key: 'hooks', value: 'installed' });
