'use strict';
// Actively READ the stable identifiers as the target app sees them in THIS container.
// Run once per Crane container, diff the outputs -> that shows what Crane does/doesn't make unique.
function out(k, v){ send({k:k, v: v===undefined||v===null ? null : String(v)}); }

setTimeout(function(){
  try {
    // HOME / container path (proves which Crane container we're in)
    out('HOME', ObjC.classes.NSProcessInfo.processInfo().environment().objectForKey_('HOME'));
    out('CFFIXED_USER_HOME', ObjC.classes.NSProcessInfo.processInfo().environment().objectForKey_('CFFIXED_USER_HOME'));

    // IDFV / IDFA
    var dev = ObjC.classes.UIDevice.currentDevice();
    var idfv = dev.identifierForVendor();
    out('identifierForVendor', idfv ? idfv.UUIDString() : null);
    if (ObjC.classes.ASIdentifierManager) {
      var idfa = ObjC.classes.ASIdentifierManager.sharedManager().advertisingIdentifier();
      out('advertisingIdentifier', idfa ? idfa.UUIDString() : null);
    }
    out('UIDevice.name', dev.name());
    out('UIDevice.model', dev.model());
    out('UIDevice.systemVersion', dev.systemVersion());

    // MobileGestalt hard IDs (jailbreak-readable)
    var MG = Module.findExportByName('libMobileGestalt.dylib','MGCopyAnswer') || Module.findExportByName(null,'MGCopyAnswer');
    if (MG) {
      var f = new NativeFunction(MG, 'pointer', ['pointer']);
      ['UniqueDeviceID','SerialNumber','MLBSerialNumber','ProductType','HWModelStr','RegionInfo','DeviceColor'].forEach(function(key){
        try {
          var k = ObjC.classes.NSString.stringWithString_(key);
          var r = f(k);
          out('MG.'+key, r.isNull() ? null : new ObjC.Object(r).toString());
        } catch(e){ out('MG.'+key, 'ERR '+e); }
      });
    }

    // sysctl hardware + boot time
    var scbn = new NativeFunction(Module.findExportByName(null,'sysctlbyname'),'int',['pointer','pointer','pointer','pointer','pointer']);
    function sc_str(name){
      var namep = Memory.allocUtf8String(name);
      var sz = Memory.alloc(8); sz.writeU64(256);
      var buf = Memory.alloc(256);
      if (scbn(namep, buf, sz, NULL, NULL)===0) return buf.readCString();
      return null;
    }
    function sc_u64(name){
      var namep = Memory.allocUtf8String(name);
      var sz = Memory.alloc(8); sz.writeU64(8);
      var buf = Memory.alloc(8);
      if (scbn(namep, buf, sz, NULL, NULL)===0) return buf.readU64().toString();
      return null;
    }
    out('sysctl.hw.machine', sc_str('hw.machine'));
    out('sysctl.hw.model', sc_str('hw.model'));
    out('sysctl.hw.memsize', sc_u64('hw.memsize'));
    out('sysctl.kern.osversion', sc_str('kern.osversion'));
    // kern.boottime via mib
    var mib = Memory.alloc(8); mib.writeU32(1); mib.add(4).writeU32(21); // CTL_KERN, KERN_BOOTTIME
    var tv = Memory.alloc(16); var tsz = Memory.alloc(8); tsz.writeU64(16);
    var sysctl = new NativeFunction(Module.findExportByName(null,'sysctl'),'int',['pointer','uint','pointer','pointer','pointer','uint']);
    if (sysctl(mib,2,tv,tsz,NULL,0)===0) out('kern.boottime', tv.readU64().toString()+'.'+tv.add(8).readU64().toString());

    // iCloud / Apple-ID linker (the thing "Separate System Accounts" is supposed to redirect)
    try {
      var tok = ObjC.classes.NSFileManager.defaultManager().ubiquityIdentityToken();
      out('iCloud.ubiquityIdentityToken', tok ? tok.description() : null);
    } catch(e){ out('iCloud.ubiquityIdentityToken', 'ERR '+e); }

    out('__done__','1');
  } catch(e){ out('FATAL', String(e)); }
}, 4000);
