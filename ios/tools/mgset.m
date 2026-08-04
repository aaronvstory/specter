// mgset — read/set the IODeviceTree root "model" + "compatible" properties from
// userspace via IOKit (root). These back sysctl hw.machine (<- "model") and
// hw.model (<- "compatible"[0]). If IORegistryEntrySetCFProperty is permitted on
// the DT root, this spoofs sysctl device-wide with NO kernel memory write and
// NO panic risk — a reboot rebuilds the device tree from the boot blob, undoing it.
//
//   mgset                      # print current model + compatible
//   mgset set <model> <board>  # set model=<model>, compatible[0]=<board>, read back
//
// Build: ios/tools/build-tools.sh ; run from a jbroot path (/var/jb/usr/bin) as root.
#import <Foundation/Foundation.h>
#import <IOKit/IOKitLib.h>
#include <string.h>
#include <dlfcn.h>

// IORegistryEntrySetCFProperty is header-blocked on iOS but present in the runtime
// framework — resolve it dynamically to bypass the availability annotation.
typedef kern_return_t (*setprop_t)(io_registry_entry_t, CFStringRef, CFTypeRef);
static setprop_t g_set;

static void readback(io_registry_entry_t e, const char *key) {
    CFStringRef k = CFStringCreateWithCString(NULL, key, kCFStringEncodingUTF8);
    CFTypeRef r = IORegistryEntryCreateCFProperty(e, k, kCFAllocatorDefault, 0);
    CFRelease(k);
    if (!r) { printf("   %-11s = (null)\n", key); return; }
    if (CFGetTypeID(r) == CFDataGetTypeID()) {
        const UInt8 *b = CFDataGetBytePtr(r); CFIndex n = CFDataGetLength(r);
        printf("   %-11s = CFData[%ld] \"", key, (long)n);
        for (CFIndex i = 0; i < n; i++) putchar(b[i] ? b[i] : '|');  // NUL shown as |
        printf("\"\n");
    } else {
        CFStringRef d = CFCopyDescription(r); char bb[256] = {0};
        CFStringGetCString(d, bb, sizeof(bb), kCFStringEncodingUTF8);
        printf("   %-11s = (type!=CFData) %s\n", key, bb); CFRelease(d);
    }
    CFRelease(r);
}

int main(int argc, char **argv) {
    void *iokit = dlopen("/System/Library/Frameworks/IOKit.framework/IOKit", RTLD_NOW);
    g_set = iokit ? (setprop_t)dlsym(iokit, "IORegistryEntrySetCFProperty") : NULL;
    io_registry_entry_t e = IORegistryEntryFromPath((mach_port_t)0, "IODeviceTree:/");
    printf("IODeviceTree:/ entry = %u ; set-fn = %s\n", (unsigned)e, g_set ? "resolved" : "MISSING");
    printf("--- current ---\n"); readback(e, "model"); readback(e, "compatible");

    if (argc >= 4 && strcmp(argv[1], "set") == 0) {
        const char *model = argv[2], *board = argv[3];
        // model: replace with a NUL-terminated CFData (same shape as original OSData)
        CFDataRef md = CFDataCreate(NULL, (const UInt8 *)model, strlen(model) + 1);
        kern_return_t k1 = g_set ? g_set(e, CFSTR("model"), md) : KERN_FAILURE;
        printf("SET model=%s        -> kr=0x%x %s\n", model, k1, k1 == KERN_SUCCESS ? "OK" : "FAIL");
        CFRelease(md);
        // compatible: keep the packed NUL-list, replace only the first token
        CFTypeRef cur = IORegistryEntryCreateCFProperty(e, CFSTR("compatible"), kCFAllocatorDefault, 0);
        if (cur && CFGetTypeID(cur) == CFDataGetTypeID()) {
            const UInt8 *b = CFDataGetBytePtr(cur); CFIndex n = CFDataGetLength(cur);
            CFIndex f = 0; while (f < n && b[f]) f++;                 // first token length
            CFMutableDataRef nd = CFDataCreateMutable(NULL, 0);
            CFDataAppendBytes(nd, (const UInt8 *)board, strlen(board) + 1);  // new token + NUL
            if (f < n) CFDataAppendBytes(nd, b + f + 1, n - f - 1);          // remainder of list
            kern_return_t k2 = g_set ? g_set(e, CFSTR("compatible"), nd) : KERN_FAILURE;
            printf("SET compatible[0]=%s -> kr=0x%x %s\n", board, k2, k2 == KERN_SUCCESS ? "OK" : "FAIL");
            CFRelease(nd);
        } else {
            printf("compatible not CFData — skipped\n");
        }
        if (cur) CFRelease(cur);
        printf("--- after ---\n"); readback(e, "model"); readback(e, "compatible");
    }
    return 0;
}
