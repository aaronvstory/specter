TARGET := iphone:clang:latest:14.0
ARCHS = arm64 arm64e
THEOS_PACKAGE_SCHEME = rootless

include $(THEOS)/makefiles/common.mk

TWEAK_NAME = SpecterCanary
SpecterCanary_FILES = Tweak.xm
SpecterCanary_CFLAGS = -fobjc-arc
SpecterCanary_FRAMEWORKS = Foundation

include $(THEOS_MAKE_PATH)/tweak.mk
