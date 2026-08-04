// SpecterCanary — an INERT injected dylib: it loads and logs, and hooks NOTHING.
//
// It exists purely as a control for the detection experiment (docs/ios/DETECTION-EXPERIMENT.md).
// If a target app rejects/loops with ONLY this injected (no hooks, no changed values), the app detects
// the mere PRESENCE of an injected image — not our hooks or our spoofed values. That isolates
// "detects any injection" from "detects our specific hooks/values", which SpecterTweak can't distinguish
// on its own (it always installs hook trampolines).
#import <Foundation/Foundation.h>

%ctor {
    @autoreleasepool {
        NSLog(@"[SpecterCanary] inert dylib loaded into %@ — no hooks installed",
              [[NSBundle mainBundle] bundleIdentifier] ?: @"(no bundle id)");
    }
}
