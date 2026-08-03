TARGET := iphone:clang:latest:14.0
ARCHS = arm64 arm64e
THEOS_PACKAGE_SCHEME = rootless

include $(THEOS)/makefiles/common.mk

TWEAK_NAME = SpecterTweak
SpecterTweak_FILES = Tweak.xm
SpecterTweak_CFLAGS = -fobjc-arc
SpecterTweak_FRAMEWORKS = UIKit Foundation

include $(THEOS_MAKE_PATH)/tweak.mk
