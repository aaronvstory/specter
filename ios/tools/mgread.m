// mgread — a tiny lock-independent MobileGestalt reader for on-device verification.
// A fresh CLI process: reads MGCopyAnswer for the identity keys we spoof, plus the
// sysctl hw.* counterparts, and prints to stdout. Runs over SSH regardless of screen
// lock (no SpringBoard, no file write, no frida). Build with ios/tools/build-mgread.sh.
#import <Foundation/Foundation.h>
#include <dlfcn.h>
#include <sys/sysctl.h>

typedef CFTypeRef (*MGCopyAnswer_t)(CFStringRef);

static void printMG(MGCopyAnswer_t fn, const char *key) {
    CFStringRef k = CFStringCreateWithCString(NULL, key, kCFStringEncodingUTF8);
    CFTypeRef v = fn ? fn(k) : NULL;
    CFStringRef d = v ? CFCopyDescription(v) : CFSTR("(null)");
    char buf[512] = {0};
    CFStringGetCString(d, buf, sizeof(buf), kCFStringEncodingUTF8);
    printf("MG  %-18s = %s\n", key, buf);
    CFRelease(k); CFRelease(d); if (v) CFRelease(v);
}

static void printSysctl(const char *name) {
    char v[256] = {0};
    size_t s = sizeof(v);
    if (sysctlbyname(name, v, &s, NULL, 0) == 0) printf("sysctl %-15s = %s\n", name, v);
    else printf("sysctl %-15s = (err)\n", name);
}

int main(void) {
    void *h = dlopen("/usr/lib/libMobileGestalt.dylib", RTLD_NOW);
    MGCopyAnswer_t MGCopyAnswer = h ? (MGCopyAnswer_t)dlsym(h, "MGCopyAnswer") : NULL;
    const char *keys[] = {"ProductType","HWModelStr","HardwarePlatform","DeviceClass",
                          "ProductVersion","BuildVersion","RegionInfo", NULL};
    for (int i = 0; keys[i]; i++) printMG(MGCopyAnswer, keys[i]);
    printSysctl("hw.machine");
    printSysctl("hw.model");
    return 0;
}
