// SpecterProbe — the iOS efficacy instrument (analog of the Android probe).
//
// Reads every device-identity signal SpecterTweak spoofs, via the SAME public read paths a
// fingerprinting SDK uses, and writes JSON to /var/mobile/Library/Specter/probe_result.json (world
// -readable) plus shows it on screen. Deterministic; no Frida, no UI-scraping.
//
// Efficacy test: install the probe + tweak, add com.specter.iosprobe to the tweak Filter, run once
// with NO profile (baseline = real device), then drop a Specter profile for com.specter.iosprobe and
// run again. ios/verify.py diffs the two → per-field ✅/❌. If the tweak works, every spoofed field flips.

#import <UIKit/UIKit.h>
#import <sys/sysctl.h>
#import <sys/utsname.h>
#import <dlfcn.h>

static NSString *sysctlByNameStr(const char *name) {
    size_t len = 0;
    if (sysctlbyname(name, NULL, &len, NULL, 0) != 0 || len == 0) return nil;
    char *buf = malloc(len);
    NSString *s = (sysctlbyname(name, buf, &len, NULL, 0) == 0) ? @(buf) : nil;
    free(buf);
    return s;
}
static NSNumber *sysctlByNameU64(const char *name) {
    uint64_t v = 0; size_t len = sizeof(v);
    return sysctlbyname(name, &v, &len, NULL, 0) == 0 ? @(v) : nil;
}
static NSString *unameMachine(void) {
    struct utsname u; return uname(&u) == 0 ? @(u.machine) : nil;
}
static NSString *bootTime(void) {
    struct timeval tv; size_t len = sizeof(tv);
    int mib[2] = {CTL_KERN, KERN_BOOTTIME};
    if (sysctl(mib, 2, &tv, &len, NULL, 0) != 0) return nil;
    return [NSString stringWithFormat:@"%ld.%06d", (long)tv.tv_sec, (int)tv.tv_usec];
}
static NSString *mgAnswer(NSString *key) {
    static CFTypeRef (*MGCopyAnswer)(CFStringRef) = NULL;
    static dispatch_once_t once; dispatch_once(&once, ^{ MGCopyAnswer = dlsym(RTLD_DEFAULT, "MGCopyAnswer"); });
    if (!MGCopyAnswer) return nil;
    CFTypeRef r = MGCopyAnswer((__bridge CFStringRef)key);
    if (!r) return nil;
    NSString *out = [(__bridge id)r isKindOfClass:[NSString class]] ? (__bridge NSString *)r : [(__bridge id)r description];
    CFRelease(r);
    return out;
}

static NSDictionary *collect(void) {
    UIDevice *d = UIDevice.currentDevice;
    NSMutableDictionary *m = [NSMutableDictionary dictionary];
    // UIKit / ObjC
    m[@"UIDevice.identifierForVendor"] = d.identifierForVendor.UUIDString ?: NSNull.null;
    m[@"UIDevice.name"] = d.name ?: NSNull.null;
    m[@"UIDevice.systemVersion"] = d.systemVersion ?: NSNull.null;
    m[@"UIDevice.model"] = d.model ?: NSNull.null;
    // sysctl (string + numeric MIB both matter)
    m[@"sysctl.hw.machine"] = sysctlByNameStr("hw.machine") ?: NSNull.null;
    m[@"sysctl.hw.model"] = sysctlByNameStr("hw.model") ?: NSNull.null;
    m[@"sysctl.hw.memsize"] = sysctlByNameU64("hw.memsize") ?: NSNull.null;
    m[@"sysctl.hw.ncpu"] = sysctlByNameU64("hw.ncpu") ?: NSNull.null;
    m[@"sysctl.kern.osversion"] = sysctlByNameStr("kern.osversion") ?: NSNull.null;
    m[@"kern.boottime"] = bootTime() ?: NSNull.null;
    m[@"uname.machine"] = unameMachine() ?: NSNull.null;
    // MobileGestalt (exported path; validates the tweak's internal-worker hook propagates)
    for (NSString *k in @[@"ProductType", @"HWModelStr", @"ProductVersion", @"BuildVersion",
                          @"RegionInfo", @"SerialNumber", @"UniqueDeviceID"])
        m[[@"MG." stringByAppendingString:k]] = mgAnswer(k) ?: NSNull.null;
    // obfuscated-hash form of the same keys — apps often query these instead of the plaintext names,
    // so we read them too or the test would mask a leak (base64(md5("MGCopyAnswer"+key))[:22]).
    m[@"MG.obf.ProductType"] = mgAnswer(@"h9jDsbgj7xIVeIQ8S3/X3Q") ?: NSNull.null;
    m[@"MG.obf.HWModelStr"] = mgAnswer(@"/YYygAofPDbhrwToVsXdeA") ?: NSNull.null;
    return m;
}

static NSString *writeResult(NSDictionary *m) {
    NSError *e = nil;
    NSData *json = [NSJSONSerialization dataWithJSONObject:m options:NSJSONWritingPrettyPrinted|NSJSONWritingSortedKeys error:&e];
    // Write inside our OWN container (sandbox-safe). Root/SSH reads it from the container path.
    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSString *path = [docs stringByAppendingPathComponent:@"probe_result.json"];
    BOOL ok = [json writeToFile:path atomically:YES];
    if (ok) [NSFileManager.defaultManager setAttributes:@{NSFilePosixPermissions:@0644} ofItemAtPath:path error:nil];
    return ok ? path : [@"WRITE FAILED " stringByAppendingString:(e.localizedDescription ?: @"")];
}

@interface AppDelegate : UIResponder <UIApplicationDelegate>
@property (strong, nonatomic) UIWindow *window;
@end
@implementation AppDelegate
- (BOOL)application:(UIApplication *)app didFinishLaunchingWithOptions:(NSDictionary *)opts {
    NSDictionary *result = collect();
    NSString *path = writeResult(result);

    self.window = [[UIWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
    UIViewController *vc = [UIViewController new];
    vc.view.backgroundColor = UIColor.systemBackgroundColor;

    UITextView *tv = [[UITextView alloc] initWithFrame:vc.view.bounds];
    tv.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    tv.editable = NO;
    tv.font = [UIFont monospacedSystemFontOfSize:12 weight:UIFontWeightRegular];
    NSMutableString *s = [NSMutableString stringWithFormat:@"SpecterProbe\nwrote: %@\n\n", path];
    for (NSString *k in [result.allKeys sortedArrayUsingSelector:@selector(compare:)])
        [s appendFormat:@"%@ = %@\n", k, result[k]];
    tv.text = s;
    [vc.view addSubview:tv];

    self.window.rootViewController = vc;
    [self.window makeKeyAndVisible];
    return YES;
}
@end

int main(int argc, char *argv[]) {
    @autoreleasepool { return UIApplicationMain(argc, argv, nil, NSStringFromClass(AppDelegate.class)); }
}
