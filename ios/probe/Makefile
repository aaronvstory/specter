TARGET := iphone:clang:latest:14.0
ARCHS = arm64 arm64e
THEOS_PACKAGE_SCHEME = rootless

include $(THEOS)/makefiles/common.mk

APPLICATION_NAME = SpecterProbe
SpecterProbe_FILES = entry.m
SpecterProbe_FRAMEWORKS = UIKit
SpecterProbe_CFLAGS = -fobjc-arc
SpecterProbe_INSTALL_PATH = /Applications

include $(THEOS_MAKE_PATH)/application.mk

after-install::
	install.exec "uicache -p /Applications/SpecterProbe.app"
