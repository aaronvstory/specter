#!/bin/bash
# reinstall the specter_zygisk .so, reboot, wait for boot, wake screen.
set -e
SER=9B151FFAZ00FPF
cd "$(dirname "$0")/../build/module"
adb -s $SER shell "rm -rf /data/local/tmp/spz && mkdir -p /data/local/tmp/spz/zygisk"
adb -s $SER push zygisk/arm64-v8a.so /data/local/tmp/spz/zygisk/ >/dev/null 2>&1
adb -s $SER push module.prop sepolicy.rule /data/local/tmp/spz/ >/dev/null 2>&1
adb -s $SER push "$(dirname "$0")/spz_install.sh" /data/local/tmp/spz_install.sh >/dev/null 2>&1
adb -s $SER shell su -M -c "sh /data/local/tmp/spz_install.sh" >/dev/null 2>&1
echo "[reinstall] rebooting"
adb -s $SER reboot
for i in $(seq 1 40); do
  bc=$(adb -s $SER shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$bc" = "1" ] && { echo "[reinstall] booted ~$((i*5))s"; break; }
  sleep 5
done
adb -s $SER shell input keyevent KEYCODE_WAKEUP
sleep 2
echo "[reinstall] ready"
